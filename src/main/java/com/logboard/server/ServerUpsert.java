package com.logboard.server;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create/update body. Exactly one of {@code logPatternKey} / {@code customLogRegex}.
 * {@code password} (PASSWORD) or {@code privateKey} (KEY) is required on create and when authType changes;
 * on update, blank/absent keeps the stored secret. Secrets are write-only.
 */
public record ServerUpsert(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String host,
        @Schema(defaultValue = "22") @Min(1) @Max(65535) Integer sshPort,
        @NotBlank @Size(max = 100) String username,
        @NotNull AuthType authType,
        @Schema(description = "Write-only. Used when authType=PASSWORD") String password,
        @Schema(description = "Write-only. OpenSSH/PEM private key (no passphrase). Used when authType=KEY")
                String privateKey,
        @NotBlank @Size(max = 1024) String logPath,
        @Schema(description = "Rotated file name pattern, e.g. app.%d{yyyy-MM-dd}.%i.log.gz. One %d{format} required, "
                + "%i optional, .gz optional. Without '/' the files are in logPath's directory")
                @Size(max = 1024) String rotatedPattern,
        @Schema(description = "Preset key from GET /log-patterns") String logPatternKey,
        @Schema(description = "Custom header regex with named groups ts and level") String customLogRegex,
        @Schema(defaultValue = "Asia/Seoul") @Size(max = 64) String timezone,
        @Schema(defaultValue = "UTF-8") @Size(max = 32) String charset) {

    public ServerUpsert {
        if (sshPort == null) sshPort = 22;
        if (isBlank(timezone)) timezone = "Asia/Seoul";
        if (isBlank(charset)) charset = "UTF-8";
        if (isBlank(rotatedPattern)) rotatedPattern = null;
        if (isBlank(logPatternKey)) logPatternKey = null;
        if (isBlank(customLogRegex)) customLogRegex = null;
    }

    /** The secret for the selected authType, or null if blank/absent. */
    String secret() {
        String s = authType == AuthType.KEY ? privateKey : password;
        return isBlank(s) ? null : s;
    }

    /** Field name of the secret for the selected authType. */
    String secretField() {
        return authType == AuthType.KEY ? "privateKey" : "password";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
