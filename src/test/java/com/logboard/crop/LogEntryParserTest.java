package com.logboard.crop;

import static org.assertj.core.api.Assertions.assertThat;

import com.logboard.server.LogPatternPreset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogEntryParserTest {

    @TempDir Path dir;

    List<LogEntry> parse(Pattern header, String zone, String... lines) throws Exception {
        Path f = Files.writeString(dir.resolve("t.log"), String.join("\n", lines) + "\n");
        List<LogEntry> out = new ArrayList<>();
        new LogEntryParser(header, ZoneId.of(zone)).parse(f, List.of(), out::add);
        return out;
    }

    @Test
    void entriesWithPreambleAndContinuations() throws Exception {
        List<LogEntry> entries = parse(LogPatternPreset.SPRING_BOOT_2.pattern(), "Asia/Seoul",
                "\tat leftover(A.java:1)",
                "2026-11-08 14:31:58.123 ERROR 1 --- [main] c.f.A : boom",
                "java.lang.IllegalStateException: x",
                "",
                "2026-11-08 14:31:59.000  INFO 1 --- [main] c.f.A : ok");
        assertThat(entries).extracting(LogEntry::firstLine).containsExactly(1, 2, 5);
        assertThat(entries).extracting(LogEntry::endLine).containsExactly(1, 4, 5);
        assertThat(entries.get(0).header()).isNull();
        assertThat(entries.get(0).time()).isNull();
        assertThat(entries.get(1).level()).isEqualTo("ERROR");
        assertThat(entries.get(1).time()).isEqualTo(OffsetDateTime.parse("2026-11-08T14:31:58.123+09:00")); // server tz
        assertThat(entries.get(1).continuation()).containsExactly("java.lang.IllegalStateException: x", "");
        assertThat(entries.get(1).headerRest()).isEqualTo("1 --- [main] c.f.A : boom");
    }

    @Test
    void headerMustMatchAtLineStart() throws Exception {
        List<LogEntry> entries = parse(Pattern.compile("(?<ts>\\S+ \\S+) (?<level>[A-Z]+)"), "UTC",
                "2026-11-08 14:31:58 ERROR boom",
                "  message mentions 2026-11-08 14:32:00 ERROR inside");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).endLine()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2026-11-08T14:31:58.123+09:00 | 2026-11-08T14:31:58.123+09:00
            2026-11-08T05:31:58.123Z      | 2026-11-08T14:31:58.123+09:00
            2026-11-08T14:31:58+0900      | 2026-11-08T14:31:58+09:00
            2026-11-08 14:31:58,123       | 2026-11-08T14:31:58.123+09:00
            2026/11/08 14:31:58           | 2026-11-08T14:31:58+09:00
            2026-11-08 14:31:58.123456    | 2026-11-08T14:31:58.123456+09:00
            """)
    void timestampShapes(String ts, String expected) throws Exception {
        List<LogEntry> entries = parse(Pattern.compile("^\\[(?<ts>[^\\]]+)] (?<level>\\w+)"), "Asia/Seoul",
                "[" + ts + "] ERROR x");
        assertThat(entries.get(0).time()).isEqualTo(OffsetDateTime.parse(expected));
    }

    @Test
    void unparseableTimestampGivesNullTime() throws Exception {
        List<LogEntry> entries = parse(Pattern.compile("^\\[(?<ts>[^\\]]+)] (?<level>\\w+)"), "UTC",
                "[Nov 8 14:31:58] ERROR x", "[2026-13-08 14:31:58] ERROR y");
        assertThat(entries).extracting(LogEntry::time).containsOnlyNulls();
    }
}
