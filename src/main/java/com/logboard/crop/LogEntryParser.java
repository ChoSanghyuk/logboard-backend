package com.logboard.crop;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a UTF-8 raw log into entries: a line where the header regex matches at the start begins an entry,
 * other lines are continuation lines. Streams the file; entries are passed to a sink one by one.
 *
 * <p>Time-only timestamps (LOGBACK_DEFAULT) get their date from the {@link FileSegment} of the line. The segment
 * date is the date of the file's <em>last</em> line; going backwards, a jump of more than 12 h forward in time means
 * the previous day (the file spans midnight). This needs one extra pass over the file, done only when needed.
 */
public final class LogEntryParser {

    private static final int HALF_DAY_SECONDS = 12 * 3600;

    private final Pattern header;
    private final ZoneId zone;

    /** @param header regex with named groups {@code ts} and {@code level} */
    public LogEntryParser(Pattern header, ZoneId zone) {
        this.header = header;
        this.zone = zone;
    }

    /**
     * @param segments sorted by firstLine; may be empty
     * @return total number of lines
     */
    public int parse(Path file, List<FileSegment> segments, Consumer<LogEntry> sink) throws IOException {
        int[] wrapsPerSegment = null;
        DayCounter days = new DayCounter();
        int lineNo = 0;
        Builder current = new Builder(1, null, null, null, null);
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                lineNo++;
                Matcher m = header.matcher(line);
                if (!m.lookingAt()) {
                    current.continuation.add(line);
                    continue;
                }
                if (lineNo > 1) {
                    sink.accept(current.build(lineNo - 1));
                }
                TimestampParser.Stamp stamp = TimestampParser.parse(m.group("ts"));
                OffsetDateTime time = null;
                if (stamp != null && stamp.date() != null) {
                    time = stamp.offset() != null
                            ? OffsetDateTime.of(stamp.date(), stamp.time(), stamp.offset())
                            : ZonedDateTime.of(stamp.date(), stamp.time(), zone).toOffsetDateTime();
                } else if (stamp != null && !segments.isEmpty()) {
                    if (wrapsPerSegment == null) {
                        wrapsPerSegment = countWraps(file, segments);
                    }
                    int seg = segmentIndex(segments, lineNo);
                    if (seg >= 0) {
                        int dayIndex = days.advance(seg, stamp.time().toSecondOfDay());
                        LocalDate date = segments.get(seg).date().minusDays(wrapsPerSegment[seg] - dayIndex);
                        time = ZonedDateTime.of(date, stamp.time(), zone).toOffsetDateTime();
                    }
                }
                current = new Builder(lineNo, time, m.group("level"), line, line.substring(m.end()).trim());
            }
        }
        if (lineNo > 0) {
            sink.accept(current.build(lineNo));
        }
        return lineNo;
    }

    /** For each segment, how many times the time-only clock wraps past midnight inside it. */
    private int[] countWraps(Path file, List<FileSegment> segments) throws IOException {
        int[] wraps = new int[segments.size()];
        DayCounter days = new DayCounter();
        int lineNo = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                lineNo++;
                Matcher m = header.matcher(line);
                if (m.lookingAt()) {
                    TimestampParser.Stamp stamp = TimestampParser.parse(m.group("ts"));
                    int seg = segmentIndex(segments, lineNo);
                    if (stamp != null && stamp.date() == null && seg >= 0) {
                        wraps[seg] = days.advance(seg, stamp.time().toSecondOfDay());
                    }
                }
            }
        }
        return wraps;
    }

    /** Index of the segment containing lineNo, or -1 if before the first segment. */
    private static int segmentIndex(List<FileSegment> segments, int lineNo) {
        int idx = -1;
        for (int i = 0; i < segments.size() && segments.get(i).firstLine() <= lineNo; i++) {
            idx = i;
        }
        return idx;
    }

    /** Counts midnight wraps (time going back by more than 12 h) within the current segment. */
    private static final class DayCounter {
        private int segment = -1;
        private int lastSecond = -1;
        private int days;

        int advance(int seg, int secondOfDay) {
            if (seg != segment) {
                segment = seg;
                lastSecond = -1;
                days = 0;
            }
            if (lastSecond >= 0 && lastSecond - secondOfDay > HALF_DAY_SECONDS) {
                days++;
            }
            lastSecond = secondOfDay;
            return days;
        }
    }

    private record Builder(int firstLine, OffsetDateTime time, String level, String header, String headerRest,
                           List<String> continuation) {
        Builder(int firstLine, OffsetDateTime time, String level, String header, String headerRest) {
            this(firstLine, time, level, header, headerRest, new ArrayList<>());
        }

        LogEntry build(int endLine) {
            return new LogEntry(firstLine, endLine, time, level, header, headerRest, List.copyOf(continuation));
        }
    }
}
