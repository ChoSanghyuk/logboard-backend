package com.logboard.llm;

import jakarta.validation.constraints.NotNull;

/** {@code POST /error-logs/{id}/analyses}. */
public record AnalysisRequest(@NotNull Long llmEndpointId, @NotNull Long promptTemplateId) {}
