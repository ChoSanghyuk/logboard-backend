package com.logboard.errorlog;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.logboard.server.ServerInstance;
import io.swagger.v3.oas.annotations.media.Schema;

/** An instance whose logs could not be read during the search (as it was configured then). */
public record FetchFailure(
        @Schema(requiredMode = REQUIRED) long instanceId,
        @Schema(requiredMode = REQUIRED) String host,
        @Schema(requiredMode = REQUIRED) int sshPort,
        @Schema(requiredMode = REQUIRED) String logPath,
        @Schema(requiredMode = REQUIRED, example = "Timed out after 10s (connecting to app@10.0.1.13:22)") String message) {

    static FetchFailure of(ServerInstance i, String message) {
        return new FetchFailure(i.getId(), i.getHost(), i.getSshPort(), i.getLogPath(), message);
    }
}
