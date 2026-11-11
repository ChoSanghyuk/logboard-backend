package com.logboard.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptTemplateUpsert(
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "Must contain {{errorLog}}. Optional: {{serverName}}, {{host}}, {{errorTime}}, {{exceptionClass}}")
                @NotBlank String content,
        @JsonProperty("isDefault") @Schema(defaultValue = "false") Boolean isDefault) {

    public PromptTemplateUpsert {
        if (isDefault == null) isDefault = false;
    }
}
