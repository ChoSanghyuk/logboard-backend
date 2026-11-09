package com.logboard.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logboard.common.ApiException;
import com.logboard.server.AuthType;
import com.logboard.server.Server;
import com.logboard.server.ServerService;
import com.logboard.server.ServerUpsert;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Fetch from a real OpenSSH server holding an active file, rotated plain files and rotated .gz files. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SftpLogFetcherIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static GenericContainer<?> sshd = new GenericContainer<>("lscr.io/linuxserver/openssh-server:latest")
            .withEnv("USER_NAME", "logboard")
            .withEnv("PASSWORD_ACCESS", "true")
            .withEnv("USER_PASSWORD", "ssh-pw")
            .withExposedPorts(2222)
            .waitingFor(Wait.forLogMessage(".*\\[ls.io-init\\] done.*", 1));

    static final Path ROOT = createTempDir();
    static final Charset EUC_KR = Charset.forName("EUC-KR");
    static final String PATTERN = "app.%d{yyyy-MM-dd}.%i.log.gz";
    static final OffsetDateTime MIDNIGHT = OffsetDateTime.parse("2026-11-08T00:00:00+09:00");

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry r) {
        r.add("logboard.storage.root", ROOT::toString);
    }

    @Autowired SftpLogFetcher fetcher;
    @Autowired ServerService servers;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void putFiles() throws Exception {
        put("/logs/app.log", "A1 에러\r\nA2\n".getBytes(EUC_KR));                       // active, CRLF, EUC-KR
        put("/logs/app.2026-11-07.0.log", "R0-1\nR0-2".getBytes(EUC_KR));               // no trailing newline
        put("/logs/app.2026-11-07.2.log.gz", gzip("R2 한글\n"));
        put("/logs/app.2026-11-07.10.log.gz", gzip("R10\n"));
        put("/logs/app.2026-11-08.0.log.gz", gzip("T0\n"));
        put("/logs/app.2026-11-06.0.log.gz", gzip("OLD\n"));                            // outside window
        put("/logs/other.2026-11-07.0.log", "OTHER\n".getBytes(EUC_KR));                // other pattern
        put("/logs/bad.2026-11-07.0.log.gz", "not gzip".getBytes(EUC_KR));              // corrupt .gz
        // real server naming: application.log + application-%d{yyyy-MM-dd}-%i.log.gz in the same directory
        put("/real/application.log", "TODAY\n".getBytes(EUC_KR));
        put("/real/application-2026-11-07-2.log.gz", gzip("P2\n"));
        put("/real/application-2026-11-07-1.log.gz", gzip("P1-a\nP1-b\n"));
        put("/real/application-2026-11-06-1.log.gz", gzip("OLD\n"));                  // outside window
    }

    static void put(String path, byte[] content) {
        sshd.copyFileToContainer(Transferable.of(content), path);
    }

    static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(EUC_KR));
        }
        return bytes.toByteArray();
    }

    static Path createTempDir() {
        try {
            return Files.createTempDirectory("logboard-fetch");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM servers");
    }

    Server server(String password, String logPath, String rotatedPattern) {
        long id = servers.create(new ServerUpsert("srv-" + System.nanoTime(), sshd.getHost(), sshd.getMappedPort(2222),
                "logboard", AuthType.PASSWORD, password, null, logPath, rotatedPattern, "SPRING_BOOT_3", null,
                "Asia/Seoul", "EUC-KR")).id();
        return servers.get(id);
    }

    @Test
    void mergesRotatedAndActiveFilesOldestFirst() throws Exception {
        Server s = server("ssh-pw", "/logs/app.log", PATTERN);

        FetchedLog log = fetcher.fetch(s, MIDNIGHT.minusMinutes(5), MIDNIGHT.plusMinutes(5));

        assertThat(log.rawFile().getParent()).isEqualTo(ROOT.resolve("raw").resolve(String.valueOf(s.getId())));
        assertThat(log.rawFile().getFileName().toString()).matches("\\d{14}-app\\.log");
        assertThat(Files.readAllLines(log.rawFile())).containsExactly(
                "R0-1", "R0-2", "R2 한글", "R10", "T0", "A1 에러", "A2");
        assertThat(log.totalLines()).isEqualTo(7);
        assertThat(log.sources()).extracting(FetchedLog.Source::remotePath).containsExactly(
                "/logs/app.2026-11-07.0.log", "/logs/app.2026-11-07.2.log.gz", "/logs/app.2026-11-07.10.log.gz",
                "/logs/app.2026-11-08.0.log.gz", "/logs/app.log");
        assertThat(log.sources()).extracting(FetchedLog.Source::firstLine).containsExactly(1, 3, 4, 5, 6);
        assertThat(log.sources()).extracting(FetchedLog.Source::lineCount).containsExactly(2, 1, 1, 1, 2);
        assertThat(log.sources().get(0).date()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(log.sources().get(3).date()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(log.sources().get(4).date()).isNotNull(); // active: last-modified date
        assertThat(log.activeFileMissing()).isFalse();
        assertThat(FetchedLog.read(log.rawFile())).isEqualTo(log); // {raw}.meta.json round trip
    }

    @Test
    void realServerNamingMergesPreviousDayIndexesThenActiveFile() throws Exception {
        Server s = server("ssh-pw", "/real/application.log", "application-%d{yyyy-MM-dd}-%i.log.gz");

        FetchedLog log = fetcher.fetch(s, MIDNIGHT.minusMinutes(5), MIDNIGHT.plusMinutes(5));

        assertThat(log.rawFile().getFileName().toString()).matches("\\d{14}-application\\.log");
        assertThat(Files.readAllLines(log.rawFile())).containsExactly("P1-a", "P1-b", "P2", "TODAY");
        assertThat(log.sources()).extracting(FetchedLog.Source::remotePath).containsExactly(
                "/real/application-2026-11-07-1.log.gz", "/real/application-2026-11-07-2.log.gz",
                "/real/application.log");
        assertThat(log.sources()).extracting(FetchedLog.Source::firstLine).containsExactly(1, 3, 4);
        assertThat(log.sources().get(1).date()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(log.activeFileMissing()).isFalse();
    }

    @Test
    void missingActiveFileIsToleratedWhenRotatedFilesMatch() throws Exception {
        FetchedLog log = fetcher.fetch(server("ssh-pw", "/logs/gone.log", PATTERN),
                MIDNIGHT.minusMinutes(5), MIDNIGHT.plusMinutes(5));
        assertThat(log.activeFileMissing()).isTrue();
        assertThat(log.sources()).extracting(FetchedLog.Source::remotePath).containsExactly(
                "/logs/app.2026-11-07.0.log", "/logs/app.2026-11-07.2.log.gz", "/logs/app.2026-11-07.10.log.gz",
                "/logs/app.2026-11-08.0.log.gz");
        assertThat(Files.readAllLines(log.rawFile())).containsExactly("R0-1", "R0-2", "R2 한글", "R10", "T0");
        assertThat(FetchedLog.read(log.rawFile()).activeFileMissing()).isTrue();
    }

    @Test
    void withoutRotatedPatternOnlyTheActiveFileIsFetched() throws Exception {
        FetchedLog log = fetcher.fetch(server("ssh-pw", "/logs/app.log", null), MIDNIGHT, MIDNIGHT.plusMinutes(5));
        assertThat(Files.readAllLines(log.rawFile())).containsExactly("A1 에러", "A2");
    }

    @Test
    void windowWithoutRotatedFilesFetchesActiveOnly() throws Exception {
        OffsetDateTime t = OffsetDateTime.parse("2026-10-01T12:00:00+09:00");
        FetchedLog log = fetcher.fetch(server("ssh-pw", "/logs/app.log", PATTERN), t, t.plusMinutes(5));
        assertThat(log.sources()).extracting(FetchedLog.Source::remotePath).containsExactly("/logs/app.log");
    }

    @Test
    void missingActiveFileWithoutRotatedMatchIsFetchFailedAndLeavesNoRawFile() {
        Server s = server("ssh-pw", "/logs/nope.log", PATTERN);
        OffsetDateTime t = OffsetDateTime.parse("2026-10-01T12:00:00+09:00");
        assertThatThrownBy(() -> fetcher.fetch(s, t, t.plusMinutes(5)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FETCH_FAILED");
                    assertThat(e.getMessage()).isEqualTo("Connected, but log file not found: /logs/nope.log"
                            + " (and no rotated file matches the window)");
                });
        assertFetchFailed(server("ssh-pw", "/logs/nope.log", null), "Connected, but log file not found: /logs/nope.log");
        assertThat(ROOT.resolve("raw").resolve(String.valueOf(s.getId())).toFile().list()).isEmpty();
    }

    @Test
    void wrongPasswordIsFetchFailed() {
        assertFetchFailed(server("wrong", "/logs/app.log", PATTERN), "Authentication failed for logboard@");
    }

    @Test
    void missingRotatedDirectoryIsFetchFailed() {
        assertFetchFailed(server("ssh-pw", "/logs/app.log", "/nowhere/app.%d.log"),
                "Connected, but rotated log directory not found: /nowhere");
    }

    @Test
    void changedHostKeyIsFetchFailed() throws Exception {
        Server s = server("ssh-pw", "/logs/app.log", null);
        fetcher.fetch(s, MIDNIGHT, MIDNIGHT.plusMinutes(1)); // pins the key
        jdbc.update("UPDATE servers SET host_key_fingerprint = 'SHA256:somethingElse' WHERE id = ?", s.getId());
        assertFetchFailed(servers.get(s.getId()), "Host key of " + sshd.getHost() + ":" + sshd.getMappedPort(2222)
                + " changed: trusted SHA256:somethingElse");
    }

    @Test
    void corruptGzipIsFetchFailed() {
        assertFetchFailed(server("ssh-pw", "/logs/app.log", "bad.%d{yyyy-MM-dd}.%i.log.gz"),
                "Cannot decompress /logs/bad.2026-11-07.0.log.gz");
    }

    void assertFetchFailed(Server s, String messageStart) {
        assertThatThrownBy(() -> fetcher.fetch(s, MIDNIGHT.minusMinutes(5), MIDNIGHT.plusMinutes(5)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getCode()).isEqualTo("FETCH_FAILED");
                    assertThat(e.getMessage()).startsWith(messageStart);
                });
    }
}
