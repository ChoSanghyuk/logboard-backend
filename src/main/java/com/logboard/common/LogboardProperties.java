package com.logboard.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Binds the {@code logboard.*} section of application.yml. */
@Validated
@ConfigurationProperties("logboard")
public record LogboardProperties(
        @Valid @NotNull Storage storage,
        @Valid @NotNull Crypto crypto,
        @Valid @NotNull Mcp mcp,
        @Valid @NotNull Crop crop) {

    /** Root folder for copied raw logs ({root}/raw/…) and crop files ({root}/errors/…). */
    public record Storage(@NotBlank String root) {}

    /** Master key for AES-GCM encryption of stored secrets. */
    public record Crypto(@NotBlank String masterKey) {}

    /** Bearer token required on /mcp. Too short → the app refuses to start. */
    public record Mcp(
            @NotBlank(message = "LOGBOARD_MCP_TOKEN is required")
            @Size(min = MIN_TOKEN_LENGTH, message = "LOGBOARD_MCP_TOKEN must be at least 32 characters "
                    + "(generate one with: openssl rand -hex 32)") String token) {}

    public static final int MIN_TOKEN_LENGTH = 32;

    /** Default context lines around the anchor entry, the step used by expand, and the crop size cap. */
    public record Crop(int before, int after, int expandStep, int maxLines) {}
}
