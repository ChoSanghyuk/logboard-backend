package com.logboard.fetch;

import com.logboard.server.RotatedPattern;
import com.logboard.server.RotatedPattern.RotatedFile;
import com.logboard.server.Server;
import com.logboard.server.ServerInstance;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import com.logboard.server.SshConnector.SshFailure;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.stereotype.Component;

/** Lists an instance's rotated log files (SFTP {@code ls}) whose date overlaps the time window. */
@Component
public class RotatedFileResolver {

    /** Oldest first. Empty if the server has no rotatedPattern. Never includes the instance's active logPath. */
    public List<RotatedFile> resolve(SftpClient sftp, Server server, ServerInstance instance, OffsetDateTime from,
            OffsetDateTime to)
            throws IOException {
        if (server.getRotatedPattern() == null) {
            return List.of();
        }
        RotatedPattern pattern = RotatedPattern.parse(server.getRotatedPattern(), instance.getLogPath());
        List<String> names = new ArrayList<>();
        try {
            sftp.stat(pattern.dir()); // readDir is lazy and hides the SFTP status, so check the directory first
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw new SshFailure("Connected, but rotated log directory not found: " + pattern.dir());
            }
            throw e;
        }
        for (SftpClient.DirEntry e : sftp.readDir(pattern.dir())) {
            if (e.getAttributes().isRegularFile()) {
                names.add(e.getFilename());
            }
        }
        return pattern.select(names, ZoneId.of(server.getTimezone()), from, to).stream()
                .filter(f -> !f.path().equals(instance.getLogPath()))
                .toList();
    }
}
