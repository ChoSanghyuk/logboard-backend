package com.logboard.server;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback-style rotated file name pattern, e.g. {@code app.%d{yyyy-MM-dd}.%i.log.gz}.
 * <ul>
 *   <li>Tokens: exactly one {@code %d{format}} ({@code %d} = {@code %d{yyyy-MM-dd}}), optional {@code %i} (index).</li>
 *   <li>A trailing {@code .gz} or {@code [.gz]} is optional: names match with and without {@code .gz}.</li>
 *   <li>No '/': files sit in the logPath's directory. With '/': the part before the last '/' is a literal directory.</li>
 * </ul>
 * The date is read in the server's timezone. A file matches if its date period overlaps the window.
 */
public final class RotatedPattern {

    private static final Pattern TOKEN = Pattern.compile("%d\\{([^}]*)}|%d|%i|%");
    private static final Pattern BRACES = Pattern.compile("\\{[^}]*}");

    private final String dir;
    private final Pattern nameRegex;
    private final DateTimeFormatter dateFormat;
    private final ChronoUnit period;
    private final boolean hasIndex;

    private RotatedPattern(String dir, Pattern nameRegex, DateTimeFormatter dateFormat, ChronoUnit period,
            boolean hasIndex) {
        this.dir = dir;
        this.hasIndex = hasIndex;
        this.nameRegex = nameRegex;
        this.dateFormat = dateFormat;
        this.period = period;
    }

    /** @throws IllegalArgumentException with a user-facing message if the pattern is invalid */
    public static RotatedPattern parse(String pattern, String logPath) {
        String dir;
        String name;
        // a '/' inside %d{...} (e.g. a zone like Asia/Seoul) is not a directory separator
        int slash = BRACES.matcher(pattern).replaceAll(m -> "x".repeat(m.group().length())).lastIndexOf('/');
        if (slash >= 0) {
            dir = pattern.substring(0, slash);
            name = pattern.substring(slash + 1);
            if (dir.contains("%")) {
                throw new IllegalArgumentException("Tokens are only supported in the file name, not in the directory");
            }
        } else {
            int logSlash = logPath.lastIndexOf('/');
            dir = logSlash >= 0 ? logPath.substring(0, logSlash) : ".";
            name = pattern;
        }
        if (dir.isEmpty()) {
            dir = "/";
        }
        name = name.endsWith("[.gz]") ? name.substring(0, name.length() - 5)
                : name.endsWith(".gz") ? name.substring(0, name.length() - 3) : name;

        StringBuilder regex = new StringBuilder("^");
        String format = null;
        boolean index = false;
        Matcher m = TOKEN.matcher(name);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(name.substring(last, m.start())));
            String token = m.group();
            if (token.startsWith("%d")) {
                if (format != null) {
                    throw new IllegalArgumentException("Only one %d token is supported");
                }
                format = m.group(1) != null ? m.group(1).trim() : "yyyy-MM-dd";
                regex.append("(?<d>.+?)");
            } else if (token.equals("%i") && !index) {
                index = true;
                regex.append("(?<i>\\d+)");
            } else {
                throw new IllegalArgumentException("Unsupported token in rotatedPattern (use %d{format} and %i)");
            }
            last = m.end();
        }
        regex.append(Pattern.quote(name.substring(last))).append("(?<gz>\\.gz)?$");
        if (format == null) {
            throw new IllegalArgumentException("rotatedPattern must contain a date token, e.g. %d{yyyy-MM-dd}");
        }
        DateTimeFormatter dateFormat;
        try {
            dateFormat = DateTimeFormatter.ofPattern(format);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid date format in %d{" + format + "}: " + e.getMessage());
        }
        return new RotatedPattern(dir, Pattern.compile(regex.toString()), dateFormat, smallestUnit(format), index);
    }

    /** Remote directory to list. */
    public String dir() {
        return dir;
    }

    /** A rotated file inside the window. {@code date} = the file's date (from its name), in the server timezone. */
    public record RotatedFile(String path, boolean gzip, LocalDate date) {}

    /** Files from {@code names} (in {@link #dir()}) whose date overlaps [from, to], oldest first. */
    public List<RotatedFile> select(Collection<String> names, ZoneId zone, OffsetDateTime from, OffsetDateTime to) {
        // every formatted date value inside the window → earliest instant with that value (gives the order)
        Map<String, ZonedDateTime> wanted = new LinkedHashMap<>();
        ZonedDateTime end = to.atZoneSameInstant(zone);
        for (ZonedDateTime t = from.atZoneSameInstant(zone); !t.isAfter(end); t = t.plus(1, period)) {
            wanted.putIfAbsent(dateFormat.format(t), t);
        }
        wanted.putIfAbsent(dateFormat.format(end), end);

        record Hit(RotatedFile file, ZonedDateTime when, long index) {}
        List<Hit> hits = new ArrayList<>();
        for (String n : names) {
            Matcher m = nameRegex.matcher(n);
            if (m.matches() && wanted.containsKey(m.group("d"))) {
                ZonedDateTime when = wanted.get(m.group("d"));
                long idx = hasIndex ? Long.parseLong(m.group("i")) : 0;
                String path = dir.endsWith("/") ? dir + n : dir + "/" + n;
                hits.add(new Hit(new RotatedFile(path, m.group("gz") != null, when.toLocalDate()), when, idx));
            }
        }
        hits.sort(Comparator.comparing(Hit::when).thenComparingLong(Hit::index));
        return hits.stream().map(Hit::file).toList();
    }

    /** The period one formatted date value covers, from the finest field in the format. */
    private static ChronoUnit smallestUnit(String format) {
        String letters = format.replaceAll("'[^']*'", "");
        if (letters.matches(".*[sSn].*")) return ChronoUnit.SECONDS;
        if (letters.contains("m")) return ChronoUnit.MINUTES;
        if (letters.matches(".*[HhKk].*")) return ChronoUnit.HOURS;
        if (letters.matches(".*[dDEe].*")) return ChronoUnit.DAYS;
        if (letters.matches(".*[wW].*")) return ChronoUnit.WEEKS;
        if (letters.matches(".*[ML].*")) return ChronoUnit.MONTHS;
        return ChronoUnit.YEARS;
    }
}
