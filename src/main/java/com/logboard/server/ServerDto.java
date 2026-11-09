package com.logboard.server;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** Server as returned by the API. Never contains secrets: only {@code hasSecret}. */
public record ServerDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String host,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sshPort,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuthType authType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String logPath,
        String rotatedPattern,
        String logPatternKey,
        String customLogRegex,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timezone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String charset,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasSecret,
        @Schema(description = "Trusted SSH host key (SHA256:…), pinned on the first successful login; null = not yet",
                example = "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s") String hostKeyFingerprint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime updatedAt) {

    static ServerDto of(Server s) {
        return new ServerDto(s.getId(), s.getName(), s.getHost(), s.getSshPort(), s.getUsername(), s.getAuthType(),
                s.getLogPath(), s.getRotatedPattern(), s.getLogPatternKey(), s.getCustomLogRegex(), s.getTimezone(),
                s.getCharset(), s.getSecretEnc() != null, s.getHostKeyFingerprint(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
