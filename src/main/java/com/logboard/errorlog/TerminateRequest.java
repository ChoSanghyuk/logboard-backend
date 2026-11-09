package com.logboard.errorlog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /error-logs/{id}/terminate}. */
public record TerminateRequest(
        @Schema(description = "How it was resolved (e.g. fix summary, commit)") @NotBlank String resolution) {}
