package com.logboard.fetch;

import com.logboard.common.LogboardProperties;
import com.logboard.server.RotatedPattern.RotatedFile;
import com.logboard.server.Server;
import com.logboard.server.ServerInstance;
import com.logboard.server.SshConnector;
import com.logboard.server.SshConnector.SshFailure;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.stereotype.Component;

/**
 * Per instance: copies the rotated files in the window plus the active logPath over SFTP into one raw file
 * {@code {root}/raw/{serverId}/{instanceId}/{yyyyMMddHHmmss}-{logFileName}} (time = now, Asia/Seoul).
 * Instances are never merged with each other.
 * Files are merged oldest first (active last), {@code .gz} is decompressed, the server charset is converted to UTF-8.
 * A missing active file is tolerated if at least one rotated file matches. Metadata goes to {@code {raw}.meta.json}.
 */
@Component
public class SftpLogFetcher {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Seoul");

    private final SshConnector ssh;
    private final RotatedFileResolver resolver;
    private final Path rawRoot;

    public SftpLogFetcher(SshConnector ssh, RotatedFileResolver resolver, LogboardProperties props) {
        this.ssh = ssh;
        this.resolver = resolver;
        this.rawRoot = Path.of(props.storage().root(), "raw");
    }

    /** Fetch result of one instance: {@code log} if it worked, else {@code error} (a clear message). */
    public record Outcome(ServerInstance instance, FetchedLog log, String error) {}

    /**
     * Fetches every instance in parallel (bounded, see {@link SshConnector#inParallel}) and waits for all.
     * Outcomes are in input order. Remote failures become {@code Outcome.error}; nothing else is caught.
     */
    public List<Outcome> fetchAll(Server server, List<ServerInstance> instances, OffsetDateTime from, OffsetDateTime to) {
        return SshConnector.inParallel(instances, i -> {
            try {
                return new Outcome(i, fetch(server, i, from, to), null);
            } catch (SshFailure e) {
                return new Outcome(i, null, e.getMessage());
            }
        });
    }

    /** @throws SshFailure with a clear message if anything remote fails (the raw file is removed then) */
    public FetchedLog fetch(Server server, ServerInstance instance, OffsetDateTime from, OffsetDateTime to) {
        Path raw;
        try {
            raw = newRawFile(server, instance);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create raw file under " + rawRoot, e);
        }
        try {
            return ssh.withSftp(instance, sftp -> copyAll(sftp, server, instance, from, to, raw));
        } catch (RuntimeException e) {
            deleteQuietly(raw);
            throw e;
        }
    }

    private FetchedLog copyAll(SftpClient sftp, Server server, ServerInstance instance, OffsetDateTime from,
            OffsetDateTime to, Path raw) throws IOException {
        String logPath = instance.getLogPath();
        ZoneId zone = ZoneId.of(server.getTimezone());
        SftpClient.Attributes active = null;
        try {
            active = sftp.stat(logPath);
        } catch (SftpException e) {
            if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
        if (active != null && !active.isRegularFile()) {
            throw new SshFailure("Connected, but logPath is not a regular file: " + logPath);
        }
        List<RotatedFile> files = new ArrayList<>(resolver.resolve(sftp, server, instance, from, to));
        if (active == null && files.isEmpty()) {
            throw new SshFailure("Connected, but log file not found: " + logPath
                    + (server.getRotatedPattern() != null ? " (and no rotated file matches the window)" : ""));
        }
        if (active != null) {
            files.add(new RotatedFile(logPath, false,
                    active.getModifyTime().toInstant().atZone(zone).toLocalDate()));
        }

        Charset charset = Charset.forName(server.getCharset());
        List<FetchedLog.Source> sources = new ArrayList<>();
        int total = 0;
        try (Writer out = Files.newBufferedWriter(raw, StandardCharsets.UTF_8)) {
            for (RotatedFile f : files) {
                int count = copy(sftp, f, charset, out);
                sources.add(new FetchedLog.Source(f.path(), total + 1, count, f.date()));
                total += count;
            }
        }
        FetchedLog log = new FetchedLog(raw, total, active == null, sources);
        log.writeMeta();
        return log;
    }

    /** Appends the remote file's lines to {@code out}; returns the line count. */
    private static int copy(SftpClient sftp, RotatedFile f, Charset charset, Writer out) throws IOException {
        int count = 0;
        try (InputStream remote = sftp.read(f.path());
             InputStream in = f.gzip() ? new GZIPInputStream(remote) : remote;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
            for (String line; (line = reader.readLine()) != null; count++) {
                out.write(line);
                out.write('\n');
            }
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw new SshFailure("Connected, but log file not found: " + f.path());
            }
            if (e.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
                throw new SshFailure("Connected, but permission denied for " + f.path());
            }
            throw e;
        } catch (java.util.zip.ZipException e) {
            throw new SshFailure("Cannot decompress " + f.path() + ": " + e.getMessage());
        }
        return count;
    }

    /** Creates an empty, unique raw file (a suffix is added if two fetches start in the same second). */
    private Path newRawFile(Server server, ServerInstance instance) throws IOException {
        Path dir = Files.createDirectories(rawRoot.resolve(String.valueOf(server.getId()))
                .resolve(String.valueOf(instance.getId())));
        String logName = instance.getLogPath().substring(instance.getLogPath().lastIndexOf('/') + 1);
        String stamp = STAMP.format(ZonedDateTime.now(APP_ZONE));
        for (int n = 1; ; n++) {
            try {
                return Files.createFile(dir.resolve(n == 1 ? stamp + "-" + logName : stamp + "-" + n + "-" + logName));
            } catch (FileAlreadyExistsException e) {
                // try the next suffix
            }
        }
    }

    private static void deleteQuietly(Path raw) {
        try {
            Files.deleteIfExists(raw);
            Files.deleteIfExists(FetchedLog.metaPath(raw));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
