package com.logboard.crop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logboard.server.LogPatternPreset;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every fixture in src/test/resources/logs asserts its (start, end, anchor). */
class CropEngineTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final OffsetDateTime T = OffsetDateTime.parse("2026-11-08T14:32:00+09:00");

    /** Small context so the fixtures stay readable; the defaults (20/10) are tested on single-npe. */
    final CropEngine engine = new CropEngine(3, 2, 2000);

    static Path fixture(String name) {
        return Path.of("src/test/resources/logs", name);
    }

    static CropEngine.Scan scan(String name, OffsetDateTime errorTime, int windowMin) throws Exception {
        return scan(name, LogPatternPreset.SPRING_BOOT_3, List.of(), errorTime, windowMin);
    }

    static CropEngine.Scan scan(String name, LogPatternPreset preset, List<FileSegment> segments,
            OffsetDateTime errorTime, int windowMin) throws Exception {
        return new CropEngine(0, 0, 2000).scan(fixture(name), new LogEntryParser(preset.pattern(), SEOUL), segments,
                errorTime.minusMinutes(windowMin), errorTime.plusMinutes(windowMin));
    }

    Crop cropClosest(String name, CropEngine.Scan scan, OffsetDateTime errorTime) throws Exception {
        return engine.crop(fixture(name), scan, scan.closestTo(errorTime).orElseThrow());
    }

    static void assertRange(Crop crop, int start, int end, int anchor) {
        assertThat(List.of(crop.startLine(), crop.endLine(), crop.anchorLine())).containsExactly(start, end, anchor);
        assertThat(crop.lines()).extracting(Crop.Line::no).first().isEqualTo(start);
        assertThat(crop.lines()).hasSize(end - start + 1);
    }

    static List<Integer> linesOfKind(Crop crop, LineKind kind) {
        return crop.lines().stream().filter(l -> l.kind() == kind).map(Crop.Line::no).toList();
    }

    @Test
    void singleNpeWithDefaultContext() throws Exception {
        CropEngine.Scan scan = scan("single-npe.log", T, 5);
        assertThat(scan.totalLines()).isEqualTo(45);
        assertThat(scan.candidates()).hasSize(1);
        Candidate c = scan.candidates().get(0);
        assertThat(c.anchorLine()).isEqualTo(26);
        assertThat(c.endLine()).isEqualTo(30);
        assertThat(c.time()).isEqualTo(OffsetDateTime.parse("2026-11-08T14:31:58.123+09:00"));
        assertThat(c.exceptionClass()).isEqualTo("java.lang.NullPointerException");
        assertThat(c.message()).isEqualTo("Cannot invoke \"String.length()\" because \"name\" is null");

        Crop crop = new CropEngine(20, 10, 2000).crop(fixture("single-npe.log"), scan, c);
        assertRange(crop, 6, 40, 26);
        assertThat(crop.totalLines()).isEqualTo(45);
        assertThat(linesOfKind(crop, LineKind.ANCHOR)).containsExactly(26);
        assertThat(linesOfKind(crop, LineKind.STACK)).containsExactly(27, 28, 29, 30);
        assertThat(linesOfKind(crop, LineKind.CONTEXT)).hasSize(35 - 5);
        assertThat(crop.lines().get(26 - 6).text()).contains("ERROR").contains("Request failed");
        assertThat(crop.text()).startsWith(crop.lines().get(0).text() + "\n").endsWith(crop.lines().get(34).text() + "\n");
    }

    @Test
    void nestedCausedByChainIsOneEntry() throws Exception {
        CropEngine.Scan scan = scan("caused-by-chain.log", T, 5);
        Crop crop = cropClosest("caused-by-chain.log", scan, T);
        assertRange(crop, 3, 18, 6);
        assertThat(linesOfKind(crop, LineKind.STACK)).containsExactly(7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        Candidate c = scan.candidates().get(0);
        assertThat(c.exceptionClass()).isEqualTo("org.springframework.dao.DataIntegrityViolationException");
        assertThat(c.message()).isEqualTo("could not execute statement");
    }

    @Test
    void nMoreOnWarnEntryIsACandidate() throws Exception {
        CropEngine.Scan scan = scan("n-more.log", T, 5);
        assertThat(scan.candidates()).extracting(Candidate::anchorLine).containsExactly(3);
        assertThat(scan.candidates().get(0).exceptionClass()).isEqualTo("java.net.SocketTimeoutException");
        Crop crop = cropClosest("n-more.log", scan, T);
        assertRange(crop, 1, 7, 3); // start clamped (3 - 3 = 0 → 1)
        assertThat(linesOfKind(crop, LineKind.STACK)).containsExactly(4, 5);
    }

    @Test
    void multipleExceptionsAnchorClosestAndSwitch() throws Exception {
        OffsetDateTime errorTime = OffsetDateTime.parse("2026-11-08T14:32:30+09:00");
        CropEngine.Scan scan = scan("multiple-exceptions.log", errorTime, 5);
        assertThat(scan.candidates()).extracting(Candidate::anchorLine).containsExactly(6, 11, 16); // 2 is outside

        Crop crop = cropClosest("multiple-exceptions.log", scan, errorTime);
        assertRange(crop, 8, 15, 11);
        assertThat(scan.at(11).orElseThrow().exceptionClass()).isEqualTo("java.lang.NullPointerException");
        assertThat(scan.at(11).orElseThrow().message()).isNull();

        // switch anchor to the ERROR entry without a trace
        Candidate payment = scan.at(16).orElseThrow();
        assertThat(payment.exceptionClass()).isNull();
        assertThat(payment.message()).endsWith("c.f.PaymentClient : Payment gateway returned 500");
        Crop switched = engine.crop(fixture("multiple-exceptions.log"), scan, payment);
        assertRange(switched, 13, 18, 16);
        assertThat(linesOfKind(switched, LineKind.STACK)).isEmpty();
        // the other exception inside the context is CONTEXT, not STACK
        assertThat(switched.lines().get(0).kind()).isEqualTo(LineKind.CONTEXT);

        assertThat(scan.at(12)).isEmpty(); // not a header line
    }

    @Test
    void expandClampsToFileBounds() throws Exception {
        CropEngine.Scan scan = scan("multiple-exceptions.log", T, 5);
        Candidate anchor = scan.at(11).orElseThrow();
        Path file = fixture("multiple-exceptions.log");

        assertRange(engine.expand(file, scan, anchor, 8, 15, 2, 0), 6, 15, 11);
        assertRange(engine.expand(file, scan, anchor, 8, 15, 50, 1), 1, 16, 11);
        assertRange(engine.expand(file, scan, anchor, 8, 15, 0, 50), 8, 18, 11);
        assertThatThrownBy(() -> engine.expand(file, scan, anchor, 8, 15, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noExceptionInWindow() throws Exception {
        CropEngine.Scan scan = scan("no-exception-in-window.log", T, 5);
        assertThat(scan.totalLines()).isEqualTo(7);
        assertThat(scan.candidates()).isEmpty();
        assertThat(scan.closestTo(T)).isEmpty();
        // a wider window finds it
        assertThat(scan("no-exception-in-window.log", T, 6 * 60).candidates())
                .extracting(Candidate::anchorLine).containsExactly(2);
    }

    @Test
    void multiLineMessagesAreNotExceptions() throws Exception {
        CropEngine.Scan scan = scan("multiline-non-exception.log", T, 5);
        assertThat(scan.totalLines()).isEqualTo(9);
        assertThat(scan.candidates()).isEmpty();
    }

    @Test
    void boundaryPreambleAtFileStart() throws Exception {
        CropEngine.Scan scan = scan("boundary-start.log", T, 5);
        assertThat(scan.candidates()).extracting(Candidate::anchorLine).containsExactly(3); // preamble has no time
        Crop crop = new CropEngine(20, 10, 2000).crop(fixture("boundary-start.log"), scan, scan.candidates().get(0));
        assertRange(crop, 1, 6, 3);
        assertThat(linesOfKind(crop, LineKind.STACK)).containsExactly(4, 5);
        assertThat(linesOfKind(crop, LineKind.CONTEXT)).containsExactly(1, 2, 6);
    }

    @Test
    void boundaryTraceRunsToEndOfFile() throws Exception {
        CropEngine.Scan scan = scan("boundary-end.log", T, 5);
        assertThat(scan.totalLines()).isEqualTo(5);
        Candidate c = scan.candidates().get(0);
        assertThat(c.endLine()).isEqualTo(5);
        assertThat(c.exceptionClass()).isEqualTo("java.lang.OutOfMemoryError");
        assertRange(new CropEngine(20, 10, 2000).crop(fixture("boundary-end.log"), scan, c), 1, 5, 3);
    }

    @Test
    void boundarySingleLineFile() throws Exception {
        CropEngine.Scan scan = scan("boundary-single-line.log", T, 5);
        Crop crop = engine.crop(fixture("boundary-single-line.log"), scan, scan.candidates().get(0));
        assertRange(crop, 1, 1, 1);
        assertThat(crop.lines().get(0).kind()).isEqualTo(LineKind.ANCHOR);
        assertRange(engine.expand(fixture("boundary-single-line.log"), scan, scan.candidates().get(0), 1, 1, 50, 50), 1, 1, 1);
    }

    @Test
    void logbackDefaultAcrossMidnightInTwoMergedFiles() throws Exception {
        List<FileSegment> segments = List.of(
                new FileSegment(1, LocalDate.of(2026, 11, 7)), new FileSegment(6, LocalDate.of(2026, 11, 8)));
        OffsetDateTime errorTime = OffsetDateTime.parse("2026-11-08T00:00:02+09:00");
        CropEngine.Scan scan = scan("logback-midnight-merged.log", LogPatternPreset.LOGBACK_DEFAULT, segments, errorTime, 5);

        assertThat(scan.candidates()).extracting(Candidate::anchorLine).containsExactly(2, 6);
        assertThat(scan.at(2).orElseThrow().time()).isEqualTo(OffsetDateTime.parse("2026-11-07T23:59:58.100+09:00"));
        assertThat(scan.at(6).orElseThrow().time()).isEqualTo(OffsetDateTime.parse("2026-11-08T00:00:01.250+09:00"));

        Crop crop = cropClosest("logback-midnight-merged.log", scan, errorTime);
        assertRange(crop, 3, 9, 6);
        assertThat(linesOfKind(crop, LineKind.STACK)).containsExactly(7, 8);
    }

    @Test
    void logbackDefaultSingleFileSpanningMidnight() throws Exception {
        OffsetDateTime errorTime = OffsetDateTime.parse("2026-11-08T00:00:00+09:00");
        CropEngine.Scan scan = scan("logback-midnight-single-file.log", LogPatternPreset.LOGBACK_DEFAULT,
                List.of(new FileSegment(1, LocalDate.of(2026, 11, 8))), errorTime, 5);
        assertThat(scan.candidates()).extracting(Candidate::time)
                .containsExactly(OffsetDateTime.parse("2026-11-07T23:59:58+09:00"));
        assertRange(cropClosest("logback-midnight-single-file.log", scan, errorTime), 1, 4, 1);

        // without file dates a time-only log has no usable times
        assertThat(scan("logback-midnight-single-file.log", LogPatternPreset.LOGBACK_DEFAULT, List.of(), errorTime, 5)
                .candidates()).isEmpty();
    }

    // ---- maxLines cap ----

    @Test
    void defaultCropIsCappedPreferringLinesAbove() throws Exception {
        CropEngine.Scan scan = scan("single-npe.log", T, 5);
        Candidate c = scan.candidates().get(0); // entry 26..30 (5 lines)
        assertRange(new CropEngine(20, 10, 12).crop(fixture("single-npe.log"), scan, c), 19, 30, 26); // 7 above, 0 below
        assertRange(new CropEngine(3, 2, 12).crop(fixture("single-npe.log"), scan, c), 23, 32, 26);   // fits: 10 lines
    }

    @Test
    void expandIsClampedSilentlyAndKeepsExistingContext() throws Exception {
        CropEngine capped = new CropEngine(3, 2, 12);
        Path file = fixture("single-npe.log");
        CropEngine.Scan scan = scan("single-npe.log", T, 5);
        Candidate c = scan.candidates().get(0);

        Crop wider = capped.expand(file, scan, c, 23, 32, 50, 0);
        assertRange(wider, 21, 32, 26); // only 2 lines of room left
        assertRange(capped.expand(file, scan, c, 21, 32, 0, 50), 21, 32, 26); // full: unchanged
        assertRange(capped.expand(file, scan, c, 23, 32, 1, 50), 22, 33, 26); // room 2: 1 above, 1 below
    }

    @Test
    void anchorEntryLongerThanMaxLinesIsTheWholeCrop() throws Exception {
        CropEngine tiny = new CropEngine(20, 10, 5);
        CropEngine.Scan scan = scan("caused-by-chain.log", T, 5);
        Candidate c = scan.candidates().get(0); // entry 6..16 (11 lines)
        Crop crop = tiny.crop(fixture("caused-by-chain.log"), scan, c);
        assertRange(crop, 6, 16, 6);
        assertThat(linesOfKind(crop, LineKind.CONTEXT)).isEmpty();
        assertRange(tiny.expand(fixture("caused-by-chain.log"), scan, c, 6, 16, 50, 50), 6, 16, 6);
    }
}
