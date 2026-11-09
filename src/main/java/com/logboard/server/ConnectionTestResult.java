package com.logboard.server;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /servers/{id}/test} result. {@code logFileSize} (bytes) is set only when the file was found. */
public record ConnectionTestResult(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ok,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean logFileFound,
        Long logFileSize) {

    static ConnectionTestResult failed(String message) {
        return new ConnectionTestResult(false, message, false, null);
    }
}
