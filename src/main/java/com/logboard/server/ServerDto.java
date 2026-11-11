package com.logboard.server;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** Server as returned by the API. Never contains secrets: instances only have {@code hasSecret}. */
public record ServerDto(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED, description = "Active instances, by position") List<InstanceDto> instances,
        String rotatedPattern,
        String logPatternKey,
        String customLogRegex,
        @Schema(requiredMode = REQUIRED) String timezone,
        @Schema(requiredMode = REQUIRED) String charset,
        @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt,
        @Schema(requiredMode = REQUIRED) OffsetDateTime updatedAt) {

    static ServerDto of(Server s, List<ServerInstance> instances) {
        return new ServerDto(s.getId(), s.getName(), instances.stream().map(InstanceDto::of).toList(),
                s.getRotatedPattern(), s.getLogPatternKey(), s.getCustomLogRegex(), s.getTimezone(), s.getCharset(),
                s.getCreatedAt(), s.getUpdatedAt());
    }

    public record InstanceDto(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String host,
            @Schema(requiredMode = REQUIRED) int sshPort,
            @Schema(requiredMode = REQUIRED) String logPath,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(requiredMode = REQUIRED) AuthType authType,
            @Schema(requiredMode = REQUIRED) boolean hasSecret,
            @Schema(description = "Trusted SSH host key (SHA256:…), pinned on the first successful login; null = not yet",
                    example = "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s") String hostKeyFingerprint,
            @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt,
            @Schema(requiredMode = REQUIRED) OffsetDateTime updatedAt) {

        static InstanceDto of(ServerInstance i) {
            return new InstanceDto(i.getId(), i.getHost(), i.getSshPort(), i.getLogPath(), i.getUsername(),
                    i.getAuthType(), i.getSecretEnc() != null, i.getHostKeyFingerprint(), i.getCreatedAt(),
                    i.getUpdatedAt());
        }
    }
}
