package com.logboard.errorlog;

import static com.logboard.errorlog.ErrorStatus.ANALYZED;
import static com.logboard.errorlog.ErrorStatus.CONFIRMED;
import static com.logboard.errorlog.ErrorStatus.CROPPED;
import static com.logboard.errorlog.ErrorStatus.DEV_REQUIRED;
import static com.logboard.errorlog.ErrorStatus.DISCARDED;
import static com.logboard.errorlog.ErrorStatus.TERMINATED;

import com.logboard.common.ApiException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * The only place that changes {@link ErrorLog#getStatus()} (design §5 lifecycle). Every action lists the statuses
 * it is allowed from and the status it leads to. Anything else → {@code 409 INVALID_STATUS}.
 */
public final class ErrorLogStatusMachine {

    public enum Action {
        RECROP(Set.of(CROPPED), CROPPED),
        CONFIRM(Set.of(CROPPED), CONFIRMED),
        DISCARD(Set.of(CROPPED), DISCARDED),
        /** Starting an analysis does not change the status. */
        START_ANALYSIS(Set.of(CONFIRMED, ANALYZED), null),
        /** An analysis finished with DONE (re-analyze allowed). A FAILED analysis is no transition. */
        ANALYSIS_DONE(Set.of(CONFIRMED, ANALYZED), ANALYZED),
        DEV_REQUIRED(Set.of(ANALYZED), ErrorStatus.DEV_REQUIRED),
        TERMINATE_BY_USER(Set.of(CONFIRMED, ANALYZED, ErrorStatus.DEV_REQUIRED), TERMINATED),
        TERMINATE_BY_AGENT(Set.of(ErrorStatus.DEV_REQUIRED), TERMINATED);

        private final Set<ErrorStatus> from;
        private final ErrorStatus to;

        Action(Set<ErrorStatus> from, ErrorStatus to) {
            this.from = from;
            this.to = to;
        }
    }

    private ErrorLogStatusMachine() {}

    public static boolean allowed(ErrorStatus status, Action action) {
        return action.from.contains(status);
    }

    /** Status after {@code action}, or 409 INVALID_STATUS if not allowed from {@code status}. */
    public static ErrorStatus next(ErrorStatus status, Action action) {
        if (!allowed(status, action)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS",
                    "Cannot " + action.name().toLowerCase().replace('_', ' ') + " an error log in status " + status,
                    Map.of("status", status.name(), "action", action.name()));
        }
        return action.to != null ? action.to : status;
    }

    /** Checks and applies {@code action} to {@code log}. */
    public static void apply(ErrorLog log, Action action) {
        log.setStatus(next(log.getStatus(), action));
    }
}
