package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logboard.server.RotatedPattern.RotatedFile;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RotatedPatternTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final OffsetDateTime MIDNIGHT = OffsetDateTime.parse("2026-11-08T00:00:00+09:00");

    static List<String> paths(List<RotatedFile> files) {
        return files.stream().map(RotatedFile::path).toList();
    }

    @Test
    void dailyWithIndexAndGzSortedOldestFirst() {
        RotatedPattern p = RotatedPattern.parse("app.%d{yyyy-MM-dd}.%i.log.gz", "/var/log/app.log");
        assertThat(p.dir()).isEqualTo("/var/log");
        List<RotatedFile> files = p.select(List.of(
                        "app.2026-11-08.0.log.gz", "app.2026-11-07.10.log.gz", "app.2026-11-07.2.log",
                        "app.2026-11-06.0.log.gz", "app.log", "app.2026-11-07.x.log", "other.2026-11-07.0.log"),
                SEOUL, MIDNIGHT.minusMinutes(5), MIDNIGHT.plusMinutes(5));
        assertThat(paths(files)).containsExactly(
                "/var/log/app.2026-11-07.2.log", "/var/log/app.2026-11-07.10.log.gz", "/var/log/app.2026-11-08.0.log.gz");
        assertThat(files.get(1).gzip()).isTrue();
        assertThat(files.get(0).gzip()).isFalse();
        assertThat(files.get(2).date()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    /** Real server naming: application.log (today) + application-%d{yyyy-MM-dd}-%i.log.gz, same directory. */
    @Test
    void realServerNaming() {
        RotatedPattern p = RotatedPattern.parse("application-%d{yyyy-MM-dd}-%i.log.gz", "/var/log/myapp/application.log");
        assertThat(p.dir()).isEqualTo("/var/log/myapp");
        List<RotatedFile> files = p.select(List.of(
                        "application.log", "application-2026-11-07-2.log.gz", "application-2026-11-07-1.log.gz",
                        "application-2026-11-06-1.log.gz", "application-2026-11-07.log.gz", "application-2026-11-07-x.log.gz"),
                SEOUL, MIDNIGHT.minusMinutes(5), MIDNIGHT.plusMinutes(5));
        assertThat(paths(files)).containsExactly(
                "/var/log/myapp/application-2026-11-07-1.log.gz", "/var/log/myapp/application-2026-11-07-2.log.gz");
        assertThat(files).allSatisfy(f -> {
            assertThat(f.gzip()).isTrue();
            assertThat(f.date()).isEqualTo(LocalDate.of(2026, 11, 7));
        });
        // window inside today: no rotated file (today's log is the active application.log)
        assertThat(p.select(List.of("application.log", "application-2026-11-07-1.log.gz"),
                SEOUL, MIDNIGHT.plusHours(14), MIDNIGHT.plusHours(15))).isEmpty();
    }

    @Test
    void windowInsideOneDayMatchesThatDayOnly() {
        RotatedPattern p = RotatedPattern.parse("app.%d.log", "/l/app.log"); // %d = yyyy-MM-dd
        assertThat(paths(p.select(List.of("app.2026-11-07.log", "app.2026-11-08.log", "app.2026-11-08.log.gz"),
                SEOUL, MIDNIGHT.plusHours(14), MIDNIGHT.plusHours(15))))
                .containsExactly("/l/app.2026-11-08.log", "/l/app.2026-11-08.log.gz");
    }

    @Test
    void dateIsReadInServerTimezone() {
        RotatedPattern p = RotatedPattern.parse("app.%d{yyyy-MM-dd}.log", "/l/app.log");
        // 2026-11-07T16:00Z is 2026-11-08 01:00 in Seoul
        OffsetDateTime t = OffsetDateTime.parse("2026-11-07T16:00:00Z");
        assertThat(paths(p.select(List.of("app.2026-11-07.log", "app.2026-11-08.log"), SEOUL, t, t.plusMinutes(5))))
                .containsExactly("/l/app.2026-11-08.log");
        assertThat(paths(p.select(List.of("app.2026-11-07.log", "app.2026-11-08.log"), ZoneId.of("UTC"), t, t.plusMinutes(5))))
                .containsExactly("/l/app.2026-11-07.log");
    }

    @Test
    void hourlyPattern() {
        RotatedPattern p = RotatedPattern.parse("app-%d{yyyyMMdd_HH}.log[.gz]", "/l/app.log");
        assertThat(paths(p.select(List.of("app-20261108_13.log.gz", "app-20261108_14.log.gz", "app-20261108_15.log",
                        "app-20261108_16.log"),
                SEOUL, MIDNIGHT.plusHours(14).plusMinutes(55), MIDNIGHT.plusHours(15).plusMinutes(5))))
                .containsExactly("/l/app-20261108_14.log.gz", "/l/app-20261108_15.log");
    }

    @Test
    void absoluteDirectoryAndRegexCharsInLiterals() {
        RotatedPattern p = RotatedPattern.parse("/archive/app (1)+.%d{yyyy-MM-dd}.log", "/l/app.log");
        assertThat(p.dir()).isEqualTo("/archive");
        assertThat(paths(p.select(List.of("app (1)+.2026-11-08.log", "app 1.2026-11-08.log"),
                SEOUL, MIDNIGHT, MIDNIGHT.plusMinutes(1))))
                .containsExactly("/archive/app (1)+.2026-11-08.log");
    }

    @Test
    void invalidPatternsAreRejected() {
        assertThatThrownBy(() -> RotatedPattern.parse("app.%i.log", "/l/a.log")).hasMessageContaining("date token");
        assertThatThrownBy(() -> RotatedPattern.parse("app.%d.%d.log", "/l/a.log")).hasMessageContaining("one %d");
        assertThatThrownBy(() -> RotatedPattern.parse("app.%x.log", "/l/a.log")).hasMessageContaining("Unsupported token");
        assertThatThrownBy(() -> RotatedPattern.parse("/logs/%d{yyyy}/app.%d.log", "/l/a.log"))
                .hasMessageContaining("directory");
        assertThatThrownBy(() -> RotatedPattern.parse("app.%d{yyyy-MM-dd, UTC}.log", "/l/a.log"))
                .hasMessageContaining("Invalid date format");
        assertThatThrownBy(() -> RotatedPattern.parse("application-%d{yyyy-MM-dd, Asia/Seoul}-%i.log.gz", "/l/application.log"))
                .hasMessageContaining("Invalid date format");
        assertThatThrownBy(() -> RotatedPattern.parse("/l/%d{yyyy-MM-dd}/application-%d{yyyy-MM-dd}-%i.log.gz",
                "/l/application.log")).hasMessageContaining("directory");
    }
}
