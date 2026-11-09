package com.logboard.server;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code GET /log-patterns} item. */
public record LogPatternDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "SPRING_BOOT_3") String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String regex,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String example) {

    static LogPatternDto of(LogPatternPreset p) {
        return new LogPatternDto(p.name(), p.regex(), p.example());
    }
}
