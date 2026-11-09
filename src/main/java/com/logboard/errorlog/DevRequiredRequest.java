package com.logboard.errorlog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /error-logs/{id}/dev-required}. */
public record DevRequiredRequest(
        @Schema(description = "What the developer / code agent should look at") @NotBlank String note) {}
