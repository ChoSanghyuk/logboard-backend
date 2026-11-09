package com.logboard.fetch;

import com.logboard.common.ApiException;
import com.logboard.common.LogboardProperties;
import com.logboard.server.RotatedPattern.RotatedFile;
import com.logboard.server.Server;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Copies the rotated files in the window plus the active logPath over SFTP into one raw file
 * {@code {root}/raw/{serverId}/{yyyyMMddHHmmss}-{logFileName}} (time = now, Asia/Seoul).
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

    /** @throws ApiException 502 {@code FETCH_FAILED} with a clear message if anything remote fails */
    public FetchedLog fetch(Server server, OffsetDateTime from, OffsetDateTime to) {
        Path raw;
        try {
            raw = newRawFile(server);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create raw file under " + rawRoot, e);
        }
        try {
            return ssh.withSftp(server, sftp -> copyAll(sftp, server, from, to, raw));
        } catch (SshFailure e) {
            deleteQuietly(raw);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FETCH_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            deleteQuietly(raw);
            throw e;
        }
    }

    private FetchedLog copyAll(SftpClient sftp, Server server, OffsetDateTime from, OffsetDateTime to, Path raw)
            throws IOException {
        ZoneId zone = ZoneId.of(server.getTimezone());
        SftpClient.Attributes active = null;
        try {
            active = sftp.stat(server.getLogPath());
        } catch (SftpException e) {
            if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
        if (active != null && !active.isRegularFile()) {
            throw new SshFailure("Connected, but logPath is not a regular file: " + server.getLogPath());
        }
        List<RotatedFile> files = new ArrayList<>(resolver.resolve(sftp, server, from, to));
        if (active == null && files.isEmpty()) {
            throw new SshFailure("Connected, but log file not found: " + server.getLogPath()
                    + (server.getRotatedPattern() != null ? " (and no rotated file matches the window)" : ""));
        }
        if (active != null) {
            files.add(new RotatedFile(server.getLogPath(), false,
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
    private Path newRawFile(Server server) throws IOException {
        Path dir = Files.createDirectories(rawRoot.resolve(String.valueOf(server.getId())));
        String logName = server.getLogPath().substring(server.getLogPath().lastIndexOf('/') + 1);
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
