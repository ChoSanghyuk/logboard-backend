package com.logboard.llm;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** Never contains the apiKey: only {@code hasSecret}. */
public record LlmEndpointDto(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) String baseUrl,
        @Schema(requiredMode = REQUIRED) String model,
        @Schema(requiredMode = REQUIRED) int timeoutSec,
        @JsonProperty("isDefault") @Schema(requiredMode = REQUIRED) boolean isDefault,
        @Schema(requiredMode = REQUIRED) boolean hasSecret,
        @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt,
        @Schema(requiredMode = REQUIRED) OffsetDateTime updatedAt) {

    static LlmEndpointDto of(LlmEndpoint e) {
        return new LlmEndpointDto(e.getId(), e.getName(), e.getBaseUrl(), e.getModel(), e.getTimeoutSec(),
                e.isDefault(), e.getApiKeyEnc() != null, e.getCreatedAt(), e.getUpdatedAt());
    }
}
