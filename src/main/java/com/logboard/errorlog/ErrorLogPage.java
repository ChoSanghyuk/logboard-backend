package com.logboard.errorlog;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Page of {@code GET /error-logs}, sorted by createdAt desc. */
public record ErrorLogPage(
        @Schema(requiredMode = REQUIRED) List<ErrorLogSummary> items,
        @Schema(requiredMode = REQUIRED) long total,
        @Schema(requiredMode = REQUIRED) int page,
        @Schema(requiredMode = REQUIRED) int size) {}
