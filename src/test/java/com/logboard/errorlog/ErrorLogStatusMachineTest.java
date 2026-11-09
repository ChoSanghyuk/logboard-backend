package com.logboard.errorlog;

import static com.logboard.errorlog.ErrorLogStatusMachine.Action.*;
import static com.logboard.errorlog.ErrorStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logboard.common.ApiException;
import com.logboard.errorlog.ErrorLogStatusMachine.Action;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Full matrix: every status × every action, expectations written out from the contract. */
class ErrorLogStatusMachineTest {

    /** action → (from → to). Missing pairs must be rejected with 409 INVALID_STATUS. */
    static final Map<Action, Map<ErrorStatus, ErrorStatus>> ALLOWED = Map.of(
            RECROP, Map.of(CROPPED, CROPPED),
            CONFIRM, Map.of(CROPPED, CONFIRMED),
            DISCARD, Map.of(CROPPED, DISCARDED),
            START_ANALYSIS, Map.of(CONFIRMED, CONFIRMED, ANALYZED, ANALYZED),
            ANALYSIS_DONE, Map.of(CONFIRMED, ANALYZED, ANALYZED, ANALYZED),
            Action.DEV_REQUIRED, Map.of(ANALYZED, ErrorStatus.DEV_REQUIRED),
            TERMINATE_BY_USER, Map.of(CONFIRMED, TERMINATED, ANALYZED, TERMINATED, ErrorStatus.DEV_REQUIRED, TERMINATED),
            TERMINATE_BY_AGENT, Map.of(ErrorStatus.DEV_REQUIRED, TERMINATED));

    @Test
    void matrixCoversEveryAction() {
        assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(Action.values());
    }

    @Test
    void transitionMatrix() {
        int checked = 0;
        for (Action action : Action.values()) {
            for (ErrorStatus from : ErrorStatus.values()) {
                ErrorStatus expected = ALLOWED.get(action).get(from);
                if (expected != null) {
                    assertThat(ErrorLogStatusMachine.allowed(from, action)).as(action + " from " + from).isTrue();
                    assertThat(ErrorLogStatusMachine.next(from, action)).as(action + " from " + from).isEqualTo(expected);
                } else {
                    assertThat(ErrorLogStatusMachine.allowed(from, action)).as(action + " from " + from).isFalse();
                    assertThatThrownBy(() -> ErrorLogStatusMachine.next(from, action))
                            .as(action + " from " + from)
                            .isInstanceOfSatisfying(ApiException.class, e -> {
                                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                                assertThat(e.getCode()).isEqualTo("INVALID_STATUS");
                                assertThat(e.getDetails()).containsEntry("status", from.name())
                                        .containsEntry("action", action.name());
                            });
                }
                checked++;
            }
        }
        assertThat(checked).isEqualTo(Action.values().length * ErrorStatus.values().length);
    }

    @Test
    void terminalStatusesAllowNothing() {
        for (Action action : Action.values()) {
            assertThat(ErrorLogStatusMachine.allowed(TERMINATED, action)).isFalse();
            assertThat(ErrorLogStatusMachine.allowed(DISCARDED, action)).isFalse();
        }
    }
}
