package com.logboard.crop;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Design §6 steps 4-7. {@link #scan} finds the candidates in a raw file (one streaming pass, only candidates are
 * kept); {@link #crop} cuts the anchor entry plus {@code before}/{@code after} context lines; {@link #expand}
 * widens an existing crop. Ranges are clamped to the file and to {@code maxLines}: the anchor entry is always kept
 * whole (if it alone is longer than {@code maxLines}, the crop is exactly the anchor entry); context above the anchor
 * gets the room first. Pure Java (no Spring).
 */
public final class CropEngine {

    private final int before;
    private final int after;
    private final int maxLines;

    public CropEngine(int before, int after, int maxLines) {
        this.before = before;
        this.after = after;
        this.maxLines = maxLines;
    }

    /** Candidates in [from, to] in line order, plus the file's line count. */
    public record Scan(int totalLines, List<Candidate> candidates) {

        /** Candidate closest in time to {@code t} (the earlier line on a tie); empty if there is none. */
        public Optional<Candidate> closestTo(OffsetDateTime t) {
            return candidates.stream()
                    .min(Comparator.comparing((Candidate c) -> Duration.between(c.time(), t).abs()));
        }

        /** The candidate whose header is {@code anchorLine}. */
        public Optional<Candidate> at(int anchorLine) {
            return candidates.stream().filter(c -> c.anchorLine() == anchorLine).findFirst();
        }
    }

    public Scan scan(Path raw, LogEntryParser parser, List<FileSegment> segments, OffsetDateTime from,
            OffsetDateTime to) throws IOException {
        List<Candidate> found = new ArrayList<>();
        int total = parser.parse(raw, segments, e -> ExceptionDetector.detect(e, from, to).ifPresent(found::add));
        return new Scan(total, found);
    }

    /** Default crop: the whole anchor entry, {@code before} lines above and {@code after} lines below. */
    public Crop crop(Path raw, Scan scan, Candidate anchor) throws IOException {
        int room = Math.max(0, maxLines - (anchor.endLine() - anchor.anchorLine() + 1));
        int above = Math.min(Math.min(before, room), anchor.anchorLine() - 1);
        int below = Math.min(Math.min(after, room - above), scan.totalLines() - anchor.endLine());
        return read(raw, scan.totalLines(), anchor, anchor.anchorLine() - above, anchor.endLine() + below);
    }

    /**
     * Widens the crop {@code start..end} by the given line counts (≥ 0). The anchor entry stays fully inside.
     * Only the room left under {@code maxLines} is used (above first), so existing context never shrinks.
     */
    public Crop expand(Path raw, Scan scan, Candidate anchor, int start, int end, int addBefore, int addAfter)
            throws IOException {
        if (addBefore < 0 || addAfter < 0) {
            throw new IllegalArgumentException("expand amounts must be >= 0");
        }
        start = Math.min(start, anchor.anchorLine());
        end = Math.max(end, anchor.endLine());
        int room = Math.max(0, maxLines - (end - start + 1));
        int above = Math.min(Math.min(addBefore, room), start - 1);
        int below = Math.min(Math.min(addAfter, room - above), scan.totalLines() - end);
        return read(raw, scan.totalLines(), anchor, start - above, end + below);
    }

    private static Crop read(Path raw, int total, Candidate anchor, int start, int end) throws IOException {
        List<Crop.Line> lines = new ArrayList<>(end - start + 1);
        try (BufferedReader in = Files.newBufferedReader(raw, StandardCharsets.UTF_8)) {
            int no = 0;
            for (String text; no < end && (text = in.readLine()) != null; ) {
                no++;
                if (no >= start) {
                    lines.add(new Crop.Line(no, text, LineKind.of(no, anchor.anchorLine(), anchor.endLine())));
                }
            }
        }
        return new Crop(start, end, anchor.anchorLine(), total, lines);
    }
}
