package com.logboard.crop;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code ts} group of a log header. Accepted shapes:
 * {@code [yyyy-MM-dd(T| )]HH:mm:ss[(.|,)fraction][Z|±HH[:]mm]} ('/' also allowed as date separator).
 * Missing date → taken from the file; missing offset → server timezone.
 */
final class TimestampParser {

    /** {@code date} and {@code offset} may be null. */
    record Stamp(LocalDate date, LocalTime time, ZoneOffset offset) {}

    private static final Pattern TS = Pattern.compile(
            "(?:(\\d{4})[-/](\\d{2})[-/](\\d{2})[T ])?(\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?\\s*"
                    + "(Z|[+-]\\d{2}(?::?\\d{2})?)?");

    private TimestampParser() {}

    /** @return null if {@code ts} has none of the accepted shapes */
    static Stamp parse(String ts) {
        Matcher m = TS.matcher(ts.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            LocalDate date = m.group(1) == null ? null
                    : LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            int nanos = m.group(7) == null ? 0 : Integer.parseInt((m.group(7) + "00000000").substring(0, 9));
            LocalTime time = LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)), nanos);
            ZoneOffset offset = m.group(8) == null ? null : ZoneOffset.of(normalizeOffset(m.group(8)));
            return new Stamp(date, time, offset);
        } catch (RuntimeException e) { // e.g. month 13, hour 25
            return null;
        }
    }

    private static String normalizeOffset(String o) {
        if (o.equals("Z") || o.contains(":") || o.length() == 3) {
            return o;
        }
        return o.substring(0, 3) + ":" + o.substring(3); // +0900 → +09:00
    }
}
