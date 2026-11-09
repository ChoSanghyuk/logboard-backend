package com.logboard.crop;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Design §6 step 5. An entry whose time is inside the window is a candidate if its level is ERROR, or a
 * continuation line is a stack frame ({@code at x.y(...)}), {@code Caused by:}, {@code ... N more},
 * or an exception header line ({@code a.b.SomeException: msg}).
 */
public final class ExceptionDetector {

    /** Requires "(" after the frame so prose like "  at least once" is not a frame. */
    static final Pattern FRAME = Pattern.compile("^\\s+at\\s+\\S+\\(");
    static final Pattern CAUSED_BY = Pattern.compile("^Caused by:");
    static final Pattern MORE = Pattern.compile("^\\s*\\.\\.\\. \\d+ more");
    static final Pattern EXCEPTION_LINE = Pattern.compile("^([\\w.$]+(?:Exception|Error))(?:: (.*))?$");
    /** Fallback for the line right above the first frame, e.g. {@code com.foo.Boom: msg}. */
    private static final Pattern THROWABLE_LINE = Pattern.compile("^([\\w$]+(?:\\.[\\w$]+)+)(?:: (.*))?$");

    private ExceptionDetector() {}

    public static Optional<Candidate> detect(LogEntry e, OffsetDateTime from, OffsetDateTime to) {
        if (e.time() == null || e.time().isBefore(from) || e.time().isAfter(to)) {
            return Optional.empty();
        }
        boolean error = "ERROR".equalsIgnoreCase(e.level());
        boolean trace = e.continuation().stream().anyMatch(l -> FRAME.matcher(l).find()
                || CAUSED_BY.matcher(l).find() || MORE.matcher(l).find() || EXCEPTION_LINE.matcher(l).matches());
        if (!error && !trace) {
            return Optional.empty();
        }
        Matcher ex = exceptionLine(e.continuation());
        return Optional.of(ex != null
                ? new Candidate(e.firstLine(), e.endLine(), e.time(), ex.group(1), ex.group(2))
                : new Candidate(e.firstLine(), e.endLine(), e.time(), null, e.headerRest()));
    }

    /** The top-level exception line: the first exception-like line, else the line above the first frame. */
    private static Matcher exceptionLine(List<String> lines) {
        for (String l : lines) {
            Matcher m = EXCEPTION_LINE.matcher(l);
            if (m.matches()) {
                return m;
            }
        }
        for (int i = 1; i < lines.size(); i++) {
            if (FRAME.matcher(lines.get(i)).find()) {
                Matcher m = THROWABLE_LINE.matcher(lines.get(i - 1));
                return m.matches() ? m : null;
            }
        }
        return null;
    }
}
