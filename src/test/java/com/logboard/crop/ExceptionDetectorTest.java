package com.logboard.crop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExceptionDetectorTest {

    static final OffsetDateTime T = OffsetDateTime.parse("2026-11-08T14:32:00+09:00");

    static LogEntry entry(String level, OffsetDateTime time, String... continuation) {
        return new LogEntry(10, 10 + continuation.length, time, level, "header", "rest", List.of(continuation));
    }

    static boolean candidate(LogEntry e) {
        return ExceptionDetector.detect(e, T.minusMinutes(5), T.plusMinutes(5)).isPresent();
    }

    @Test
    void errorLevelAloneIsEnough() {
        assertThat(candidate(entry("ERROR", T))).isTrue();
        assertThat(candidate(entry("error", T))).isTrue();
        assertThat(candidate(entry("INFO", T))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\tat com.foo.A.b(A.java:1)",
            "    at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]",
            "Caused by: java.io.IOException: x",
            "\t... 12 more",
            "java.lang.IllegalStateException",
            "com.foo.CustomError: bad"})
    void traceLinesMakeNonErrorEntriesCandidates(String line) {
        assertThat(candidate(entry("WARN", T, line))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  at least once (see docs)", "  ValidationError: x", "Error while reading", "caused by: x"})
    void proseIsNotATrace(String line) {
        assertThat(candidate(entry("INFO", T, line))).isFalse();
    }

    @Test
    void windowIsInclusiveAndNullTimeNeverMatches() {
        assertThat(candidate(entry("ERROR", T.minusMinutes(5)))).isTrue();
        assertThat(candidate(entry("ERROR", T.plusMinutes(5)))).isTrue();
        assertThat(candidate(entry("ERROR", T.plusMinutes(5).plusNanos(1)))).isFalse();
        assertThat(candidate(entry("ERROR", null))).isFalse();
    }

    @Test
    void exceptionClassFallsBackToLineAboveFirstFrame() {
        Candidate c = ExceptionDetector.detect(entry("ERROR", T, "com.foo.Boom: kaput", "\tat com.foo.A.b(A.java:1)"),
                T.minusMinutes(1), T.plusMinutes(1)).orElseThrow();
        assertThat(c.exceptionClass()).isEqualTo("com.foo.Boom");
        assertThat(c.message()).isEqualTo("kaput");
        assertThat(c.anchorLine()).isEqualTo(10);
        assertThat(c.endLine()).isEqualTo(12);
    }

    @Test
    void errorWithoutTraceUsesHeaderTextAsMessage() {
        Candidate c = ExceptionDetector.detect(entry("ERROR", T), T, T).orElseThrow();
        assertThat(c.exceptionClass()).isNull();
        assertThat(c.message()).isEqualTo("rest");
    }
}
