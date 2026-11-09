package com.logboard.llm;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PromptTemplateDto(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) String content,
        @JsonProperty("isDefault") @Schema(requiredMode = REQUIRED) boolean isDefault,
        @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt,
        @Schema(requiredMode = REQUIRED) OffsetDateTime updatedAt) {

    static PromptTemplateDto of(PromptTemplate p) {
        return new PromptTemplateDto(p.getId(), p.getName(), p.getContent(), p.isDefault(), p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
