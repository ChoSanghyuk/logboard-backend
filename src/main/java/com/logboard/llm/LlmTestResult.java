package com.logboard.llm;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /llm-endpoints/{id}/test}: always 200; failures are {@code ok=false} with a specific message. */
public record LlmTestResult(
        @Schema(requiredMode = REQUIRED) boolean ok,
        @Schema(requiredMode = REQUIRED) String message,
        @Schema(requiredMode = REQUIRED, description = "Time until the reply or the failure") long latencyMs) {}
