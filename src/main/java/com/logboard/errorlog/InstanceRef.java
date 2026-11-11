package com.logboard.errorlog;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.logboard.server.ServerInstance;
import io.swagger.v3.oas.annotations.media.Schema;

/** A server instance as shown with error logs (also for soft-deleted instances). */
public record InstanceRef(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) String host,
        @Schema(requiredMode = REQUIRED) int sshPort,
        @Schema(requiredMode = REQUIRED) String logPath) {

    static InstanceRef of(ServerInstance i) {
        return new InstanceRef(i.getId(), i.getHost(), i.getSshPort(), i.getLogPath());
    }
}
