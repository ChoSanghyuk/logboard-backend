package com.logboard.errorlog;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** List item of {@code GET /error-logs}. Times are UTC. */
public record ErrorLogSummary(
        @Schema(requiredMode = REQUIRED, example = "20261108-001") String id,
        @Schema(requiredMode = REQUIRED) long serverId,
        @Schema(requiredMode = REQUIRED) String serverName,
        @Schema(requiredMode = REQUIRED, description = "IANA timezone of the server (also for soft-deleted servers), "
                + "for displaying times", example = "Asia/Seoul") String serverTimezone,
        @Schema(requiredMode = REQUIRED) OffsetDateTime requestedTime,
        @Schema(description = "Top-level exception class; null for an ERROR entry without a trace") String exceptionClass,
        String exceptionMessage,
        @Schema(requiredMode = REQUIRED) ErrorStatus status,
        @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt) {

    static ErrorLogSummary of(ErrorLog e) {
        return new ErrorLogSummary(e.getId(), e.getServer().getId(), e.getServer().getName(), e.getServer().getTimezone(),
                e.getRequestedTime(), e.getExceptionClass(), e.getExceptionMessage(), e.getStatus(),
                e.getCreatedAt());
    }
}
