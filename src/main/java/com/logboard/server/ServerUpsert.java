package com.logboard.server;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Create/update body. Exactly one of {@code logPatternKey} / {@code customLogRegex}. {@code instances} (1-20, order =
 * position): with {@code id} = update that instance, without = new; existing instances missing from the list are
 * removed (soft-deleted if error logs reference them).
 */
public record ServerUpsert(
        @NotBlank @Size(max = 100) String name,
        @ArraySchema(minItems = 1, maxItems = ServerUpsert.MAX_INSTANCES)
                @NotNull @Size(min = 1, max = ServerUpsert.MAX_INSTANCES) List<@Valid @NotNull InstanceUpsert> instances,
        @Schema(description = "Rotated file name pattern, e.g. app.%d{yyyy-MM-dd}.%i.log.gz. One %d{format} required, "
                + "%i optional, .gz optional. Without '/' the files are in the directory of each instance's logPath")
                @Size(max = 1024) String rotatedPattern,
        @Schema(description = "Preset key from GET /log-patterns") String logPatternKey,
        @Schema(description = "Custom header regex with named groups ts and level") String customLogRegex,
        @Schema(defaultValue = "Asia/Seoul") @Size(max = 64) String timezone,
        @Schema(defaultValue = "UTF-8") @Size(max = 32) String charset) {

    static final int MAX_INSTANCES = 20;

    public ServerUpsert {
        if (isBlank(timezone)) timezone = "Asia/Seoul";
        if (isBlank(charset)) charset = "UTF-8";
        if (isBlank(rotatedPattern)) rotatedPattern = null;
        if (isBlank(logPatternKey)) logPatternKey = null;
        if (isBlank(customLogRegex)) customLogRegex = null;
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
