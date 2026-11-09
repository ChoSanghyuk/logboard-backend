package com.logboard.errorlog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/** {@code POST /error-logs/search}. The window is {@code errorTime ± windowMin}. */
public record SearchRequest(
        @NotNull Long serverId,
        @Schema(description = "ISO-8601 with offset, e.g. 2026-11-08T14:32:00+09:00") @NotNull OffsetDateTime errorTime,
        @Schema(defaultValue = "5", minimum = "1", maximum = "1440") @Min(1) @Max(1440) Integer windowMin) {

    public SearchRequest {
        if (windowMin == null) windowMin = 5;
    }
}
