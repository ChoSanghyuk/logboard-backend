package com.logboard.server;

import java.util.regex.Pattern;

/**
 * Built-in log header patterns. Each regex matches the first line of an entry and has the named groups
 * {@code ts} and {@code level}; lines that don't match are continuation lines (stack traces).
 */
public enum LogPatternPreset {

    SPRING_BOOT_3(
            "^(?<ts>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:?\\d{2}))\\s+"
                    + "(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s",
            "2026-11-08T14:31:58.123+09:00 ERROR 123 --- [app] [thread] c.f.Cls : msg"),
    SPRING_BOOT_2(
            "^(?<ts>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+"
                    + "(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s",
            "2026-11-08 14:31:58.123 ERROR 123 --- [thread] c.f.Cls : msg"),
    /** Time only: the date is taken from the log file. */
    LOGBACK_DEFAULT(
            "^(?<ts>\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[[^\\]]*\\] (?<level>TRACE|DEBUG|INFO|WARN|ERROR)\\s",
            "14:31:58.123 [thread] ERROR c.f.Cls - msg");

    private final String regex;
    private final String example;
    private final Pattern pattern;

    LogPatternPreset(String regex, String example) {
        this.regex = regex;
        this.example = example;
        this.pattern = Pattern.compile(regex);
    }

    public String regex() { return regex; }
    public String example() { return example; }
    public Pattern pattern() { return pattern; }
}
