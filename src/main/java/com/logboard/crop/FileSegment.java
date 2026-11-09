package com.logboard.crop;

import java.time.LocalDate;

/**
 * Lines from {@code firstLine} (1-based) up to the next segment came from one source file dated {@code date}
 * (the date of that file's last line). Used only when the log timestamps have no date (e.g. LOGBACK_DEFAULT).
 */
public record FileSegment(int firstLine, LocalDate date) {}
