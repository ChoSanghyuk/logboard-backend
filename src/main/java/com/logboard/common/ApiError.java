package com.logboard.common;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Error body for every non-2xx response: {@code { code, message, details }}. */
public record ApiError(
        @Schema(requiredMode = REQUIRED, example = "NO_EXCEPTION_FOUND") String code,
        @Schema(requiredMode = REQUIRED) String message,
        @Schema(description = "Always an object ({} when empty)") Map<String, Object> details) {}
