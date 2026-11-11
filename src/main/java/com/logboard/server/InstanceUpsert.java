package com.logboard.server;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One instance in {@link ServerUpsert}. {@code password} (PASSWORD) or {@code privateKey} (KEY) is required for a new
 * instance and when authType changes; otherwise blank/absent keeps the stored secret. Secrets are write-only.
 */
public record InstanceUpsert(
        @Schema(description = "Existing instance of this server to update; absent = new instance") Long id,
        @NotBlank @Size(max = 255) String host,
        @Schema(defaultValue = "22") @Min(1) @Max(65535) Integer sshPort,
        @NotBlank @Size(max = 1024) String logPath,
        @NotBlank @Size(max = 100) String username,
        @NotNull AuthType authType,
        @Schema(description = "Write-only. Used when authType=PASSWORD") String password,
        @Schema(description = "Write-only. OpenSSH/PEM private key (no passphrase). Used when authType=KEY")
                String privateKey) {

    public InstanceUpsert {
        if (sshPort == null) sshPort = 22;
    }

    /** The secret for the selected authType, or null if blank/absent. */
    String secret() {
        String s = authType == AuthType.KEY ? privateKey : password;
        return ServerUpsert.isBlank(s) ? null : s;
    }

    /** Field name of the secret for the selected authType. */
    String secretField() {
        return authType == AuthType.KEY ? "privateKey" : "password";
    }
}
