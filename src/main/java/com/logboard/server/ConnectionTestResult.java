package com.logboard.server;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** {@code POST /servers/{id}/test} result: one result per active instance (by position); {@code ok} = all ok. */
public record ConnectionTestResult(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ok,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<InstanceTestResult> results) {

    /** {@code logFileSize} (bytes) is set only when the file was found. */
    public record InstanceTestResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long instanceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String host,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sshPort,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String logPath,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ok,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean logFileFound,
            Long logFileSize) {

        static InstanceTestResult of(ServerInstance i, boolean ok, String message, Long logFileSize) {
            return new InstanceTestResult(i.getId() != null ? i.getId() : 0, i.getHost(), i.getSshPort(),
                    i.getLogPath(), ok, message, logFileSize != null, logFileSize);
        }
    }
}
