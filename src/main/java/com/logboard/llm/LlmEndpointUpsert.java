package com.logboard.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/update body. {@code apiKey} is write-only: on PUT, blank/absent keeps the stored key and
 * {@code clearApiKey: true} removes it (not together with apiKey).
 */
public record LlmEndpointUpsert(
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "Server root without /v1, e.g. http://vllm:8000. Requests go to {baseUrl}/v1/chat/completions",
                example = "http://vllm:8000") @NotBlank @Size(max = 1024) String baseUrl,
        @NotBlank @Size(max = 255) String model,
        @Schema(description = "Write-only. Sent as Authorization: Bearer") String apiKey,
        @Schema(description = "PUT only: true removes the stored apiKey", defaultValue = "false") Boolean clearApiKey,
        @Schema(defaultValue = "120", minimum = "1", maximum = "3600") @Min(1) @Max(3600) Integer timeoutSec,
        @JsonProperty("isDefault") @Schema(defaultValue = "false") Boolean isDefault) {

    public LlmEndpointUpsert {
        if (apiKey != null && apiKey.isBlank()) apiKey = null;
        if (clearApiKey == null) clearApiKey = false;
        if (timeoutSec == null) timeoutSec = 120;
        if (isDefault == null) isDefault = false;
    }
}
