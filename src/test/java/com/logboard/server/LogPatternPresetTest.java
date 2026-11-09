package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class LogPatternPresetTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            SPRING_BOOT_3   | 2026-11-08T14:31:58.123+09:00 ERROR 123 --- [app] [main] c.f.Cls : boom  | 2026-11-08T14:31:58.123+09:00 | ERROR
            SPRING_BOOT_3   | 2026-11-08T14:31:58.123Z  INFO 123 --- [app] [main] c.f.Cls : ok        | 2026-11-08T14:31:58.123Z      | INFO
            SPRING_BOOT_2   | 2026-11-08 14:31:58.123 ERROR 123 --- [main] c.f.Cls : boom             | 2026-11-08 14:31:58.123       | ERROR
            SPRING_BOOT_2   | 2026-11-08 14:31:58.123  WARN 123 --- [main] c.f.Cls : hm               | 2026-11-08 14:31:58.123       | WARN
            LOGBACK_DEFAULT | 14:31:58.123 [http-nio-8080-exec-1] ERROR c.f.Cls - boom                | 14:31:58.123                  | ERROR
            LOGBACK_DEFAULT | 14:31:58.123 [main] INFO  c.f.Cls - ok                                 | 14:31:58.123                  | INFO
            """)
    void presetExtractsTsAndLevel(LogPatternPreset preset, String line, String ts, String level) {
        Matcher m = preset.pattern().matcher(line);
        assertThat(m.find()).isTrue();
        assertThat(m.group("ts")).isEqualTo(ts);
        assertThat(m.group("level")).isEqualTo(level);
    }

    @ParameterizedTest
    @EnumSource(LogPatternPreset.class)
    void presetMatchesItsExampleButNotStackLines(LogPatternPreset preset) {
        assertThat(preset.pattern().matcher(preset.example()).find()).isTrue();
        assertThat(preset.pattern().matcher("\tat com.foo.Order.place(Order.java:42)").find()).isFalse();
        assertThat(preset.pattern().matcher("java.lang.NullPointerException: x").find()).isFalse();
        assertThat(ServerService.checkCustomRegex(preset.regex())).isNull();
    }

    @Test
    void customRegexValidation() {
        assertThat(ServerService.checkCustomRegex("^(?<ts>\\S+) (?<level>\\w+)")).isNull();
        assertThat(ServerService.checkCustomRegex("^(?<ts>\\S+) \\w+")).contains("Missing: level");
        assertThat(ServerService.checkCustomRegex("^\\S+ \\w+")).contains("Missing: ts, level");
        assertThat(ServerService.checkCustomRegex("^(?<ts>\\S+ (?<level>\\w+)")).startsWith("Regex does not compile");
    }
}
