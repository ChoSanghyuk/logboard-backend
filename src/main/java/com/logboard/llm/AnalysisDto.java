package com.logboard.llm;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** {@code reply} is set when DONE, {@code errorMessage} when FAILED, {@code finishedAt} when not PENDING. */
public record AnalysisDto(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) String errorLogId,
        @Schema(requiredMode = REQUIRED) long llmEndpointId,
        @Schema(requiredMode = REQUIRED) String llmEndpointName,
        @Schema(requiredMode = REQUIRED) long promptTemplateId,
        @Schema(requiredMode = REQUIRED) String promptTemplateName,
        @Schema(requiredMode = REQUIRED) AnalysisStatus status,
        @Schema(description = "Markdown") String reply,
        String errorMessage,
        @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {

    /** Needs llmEndpoint and promptTemplate loaded. */
    public static AnalysisDto of(Analysis a) {
        return new AnalysisDto(a.getId(), a.getErrorLog().getId(), a.getLlmEndpoint().getId(),
                a.getLlmEndpoint().getName(), a.getPromptTemplate().getId(), a.getPromptTemplate().getName(), a.getStatus(), a.getReply(), a.getErrorMessage(), a.getCreatedAt(),
                a.getFinishedAt());
    }
}
