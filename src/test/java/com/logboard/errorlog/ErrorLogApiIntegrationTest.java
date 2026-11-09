package com.logboard.errorlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.logboard.server.AuthType;
import com.logboard.server.ServerService;
import com.logboard.server.ServerUpsert;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Every error log endpoint; search fetches from a real OpenSSH server holding the crop fixtures. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ErrorLogApiIntegrationTest {

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
    static final String NPE_TIME = "2026-11-08T14:32:00+09:00";

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry r) {
        r.add("logboard.storage.root", ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ServerService servers;

    long npeServer;
    long multiServer;

    @BeforeAll
    static void putFiles() throws Exception {
        put("/logs/npe.log", fixture("single-npe.log"));
        put("/logs/multi.log", fixture("multiple-exceptions.log"));
        List<String> midnight = Files.readAllLines(Path.of("src/test/resources/logs/logback-midnight-merged.log"));
        put("/logs/lb/app.2026-11-07.0.log", (String.join("\n", midnight.subList(0, 5)) + "\n").getBytes());
        put("/logs/lb/app.log", (String.join("\n", midnight.subList(5, 9)) + "\n").getBytes());
        // active file last written on 2026-11-08 (Seoul): its lines are dated from it
        sshd.execInContainer("touch", "-d", "2026-11-08 03:00:00", "/logs/lb/app.log");
    }

    static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/logs", name));
    }

    static void put(String path, byte[] content) {
        sshd.copyFileToContainer(Transferable.of(content), path);
    }

    static Path createTempDir() {
        try {
            return Files.createTempDirectory("logboard-errorlog");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM error_logs");
        jdbc.update("DELETE FROM servers");
        npeServer = server("npe-api", "/logs/npe.log", null, "SPRING_BOOT_3");
        multiServer = server("multi-api", "/logs/multi.log", null, "SPRING_BOOT_3", "Europe/Berlin");
    }

    long server(String name, String logPath, String rotatedPattern, String patternKey) {
        return server(name, logPath, rotatedPattern, patternKey, "Asia/Seoul");
    }

    long server(String name, String logPath, String rotatedPattern, String patternKey, String timezone) {
        return servers.create(new ServerUpsert(name, sshd.getHost(), sshd.getMappedPort(2222), "logboard",
                AuthType.PASSWORD, "ssh-pw", null, logPath, rotatedPattern, patternKey, null, timezone, "UTF-8")).id();
    }

    ResultActions search(long serverId, String errorTime, String extra) throws Exception {
        return mvc.perform(post("/api/error-logs/search").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"serverId\": %d, \"errorTime\": \"%s\" %s }".formatted(serverId, errorTime, extra)));
    }

    String searchId(long serverId, String errorTime) throws Exception {
        String body = search(serverId, errorTime, "").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    ResultActions patchCrop(String id, String json) throws Exception {
        return mvc.perform(patch("/api/error-logs/" + id + "/crop").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    Path cropFile(String id) {
        return ROOT.resolve("errors").resolve(id + ".log");
    }

    // ---- search ----

    @Test
    void searchCreatesCroppedErrorLog() throws Exception {
        String body = search(npeServer, NPE_TIME, "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("\\d{8}-\\d{3}")))
                .andExpect(jsonPath("$.serverId").value(npeServer))
                .andExpect(jsonPath("$.serverName").value("npe-api"))
                .andExpect(jsonPath("$.serverTimezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.requestedTime").value("2026-11-08T05:32:00Z"))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.windowMin").value(5))
                .andExpect(jsonPath("$.status").value("CROPPED"))
                .andExpect(jsonPath("$.exceptionClass").value("java.lang.NullPointerException"))
                .andExpect(jsonPath("$.exceptionMessage").value("Cannot invoke \"String.length()\" because \"name\" is null"))
                .andExpect(jsonPath("$.note").value(nullValue()))
                .andExpect(jsonPath("$.crop.startLine").value(6))
                .andExpect(jsonPath("$.crop.endLine").value(40))
                .andExpect(jsonPath("$.crop.anchorLine").value(26))
                .andExpect(jsonPath("$.crop.totalLines").value(45))
                .andExpect(jsonPath("$.crop.expandStep").value(50))
                .andExpect(jsonPath("$.crop.maxLines").value(2000))
                .andExpect(jsonPath("$.crop.lines", hasSize(35)))
                .andExpect(jsonPath("$.crop.lines[0].no").value(6))
                .andExpect(jsonPath("$.crop.lines[20].no").value(26))
                .andExpect(jsonPath("$.crop.lines[20].kind").value("ANCHOR"))
                .andExpect(jsonPath("$.crop.lines[21].kind").value("STACK"))
                .andExpect(jsonPath("$.crop.lines[24].kind").value("STACK"))
                .andExpect(jsonPath("$.crop.lines[25].kind").value("CONTEXT"))
                .andExpect(jsonPath("$.candidates", hasSize(1)))
                .andExpect(jsonPath("$.candidates[0].anchorLine").value(26))
                .andExpect(jsonPath("$.candidates[0].time").value("2026-11-08T05:31:58.123Z"))
                .andExpect(jsonPath("$.candidates[0].exceptionClass").value("java.lang.NullPointerException"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        assertThat(Files.readAllLines(cropFile(id))).hasSize(35).element(20).asString().contains("ERROR");
        String raw = jdbc.queryForObject("SELECT raw_file_path FROM error_logs WHERE id = ?", String.class, id);
        assertThat(raw).matches("raw/" + npeServer + "/\\d{14}-npe\\.log");
        assertThat(ROOT.resolve(raw)).exists();
        assertThat(Path.of(ROOT.resolve(raw) + ".meta.json")).exists();
    }

    @Test
    void searchTimeOnlyLogAcrossMidnightUsesFileDates() throws Exception {
        long lb = server("lb-api", "/logs/lb/app.log", "app.%d{yyyy-MM-dd}.%i.log", "LOGBACK_DEFAULT");
        search(lb, "2026-11-08T00:00:02+09:00", "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidates[*].anchorLine").value(contains(2, 6)))
                .andExpect(jsonPath("$.candidates[0].time").value("2026-11-07T14:59:58.1Z"))
                .andExpect(jsonPath("$.candidates[1].time").value("2026-11-07T15:00:01.25Z"))
                .andExpect(jsonPath("$.crop.anchorLine").value(6))
                .andExpect(jsonPath("$.exceptionClass").value("java.lang.NullPointerException"));
    }

    @Test
    void searchWithoutExceptionIs404AndCreatesNothing() throws Exception {
        search(npeServer, "2026-11-08T10:00:00+09:00", ", \"windowMin\": 2")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_EXCEPTION_FOUND"))
                .andExpect(jsonPath("$.details.errorTime").value("2026-11-08T01:00:00Z"))
                .andExpect(jsonPath("$.details.windowMin").value(2))
                .andExpect(jsonPath("$.details.from").value("2026-11-08T00:58:00Z"))
                .andExpect(jsonPath("$.details.to").value("2026-11-08T01:02:00Z"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM error_logs", Integer.class)).isZero();
        try (Stream<Path> files = Files.list(ROOT.resolve("raw").resolve(String.valueOf(npeServer)))) {
            assertThat(files).isEmpty(); // raw copy and meta removed
        }
    }

    @Test
    void searchFetchFailureIs502() throws Exception {
        long missing = server("missing-api", "/logs/nope.log", null, "SPRING_BOOT_3");
        search(missing, NPE_TIME, "")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("FETCH_FAILED"))
                .andExpect(jsonPath("$.message").value("Connected, but log file not found: /logs/nope.log"));
    }

    @Test
    void searchValidation() throws Exception {
        mvc.perform(post("/api/error-logs/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.serverId").exists())
                .andExpect(jsonPath("$.details.fields.errorTime").exists());
        search(npeServer, NPE_TIME, ", \"windowMin\": 0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.windowMin").exists());
        search(npeServer, "2026-11-08T14:32:00", "").andExpect(status().isBadRequest()); // no offset
        search(999999, NPE_TIME, "").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void softDeletedServerCannotBeSearchedButItsLogsStayReadable() throws Exception {
        String id = searchId(npeServer, NPE_TIME);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/servers/" + npeServer))
                .andExpect(status().isNoContent());
        search(npeServer, NPE_TIME, "").andExpect(status().isNotFound());
        mvc.perform(get("/api/error-logs/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.serverName").value("npe-api"))
                .andExpect(jsonPath("$.serverTimezone").value("Asia/Seoul"));
        mvc.perform(get("/api/error-logs?serverId=" + npeServer))
                .andExpect(jsonPath("$.items[0].serverTimezone").value("Asia/Seoul"));
    }

    // ---- get ----

    @Test
    void getReturnsSameDetailAsSearch() throws Exception {
        String created = search(npeServer, NPE_TIME, "").andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String fetched = mvc.perform(get("/api/error-logs/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Object>read(fetched, "$")).isEqualTo(JsonPath.read(created, "$"));
        mvc.perform(get("/api/error-logs/20000101-001")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- crop ----

    @Test
    void expandRecropsFromRawAndRewritesCropFile() throws Exception {
        String id = searchId(npeServer, NPE_TIME);
        patchCrop(id, "{ \"expandBefore\": 50 }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crop.startLine").value(1))
                .andExpect(jsonPath("$.crop.endLine").value(40))
                .andExpect(jsonPath("$.crop.anchorLine").value(26))
                .andExpect(jsonPath("$.crop.lines", hasSize(40)))
                .andExpect(jsonPath("$.crop.lines[25].kind").value("ANCHOR"));
        patchCrop(id, "{ \"expandAfter\": 3 }")
                .andExpect(jsonPath("$.crop.startLine").value(1))
                .andExpect(jsonPath("$.crop.endLine").value(43));
        assertThat(Files.readAllLines(cropFile(id))).hasSize(43);
        mvc.perform(get("/api/error-logs/" + id))
                .andExpect(jsonPath("$.crop.endLine").value(43))
                .andExpect(jsonPath("$.crop.lines", hasSize(43)))
                .andExpect(jsonPath("$.crop.lines[42].no").value(43))
                .andExpect(jsonPath("$.crop.lines[26].kind").value("STACK"));
    }

    @Test
    void anchorSwitchUsesAnotherCandidate() throws Exception {
        String body = search(multiServer, "2026-11-08T14:32:30+09:00", "")
                .andExpect(jsonPath("$.candidates[*].anchorLine").value(contains(6, 11, 16)))
                .andExpect(jsonPath("$.crop.anchorLine").value(11))
                .andExpect(jsonPath("$.serverTimezone").value("Europe/Berlin"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        patchCrop(id, "{ \"anchorLine\": 16 }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crop.anchorLine").value(16))
                .andExpect(jsonPath("$.crop.startLine").value(1))
                .andExpect(jsonPath("$.crop.endLine").value(18))
                .andExpect(jsonPath("$.crop.lines[15].kind").value("ANCHOR"))
                .andExpect(jsonPath("$.crop.lines[11].kind").value("CONTEXT")) // other exception's trace
                .andExpect(jsonPath("$.exceptionClass").value(nullValue()))
                .andExpect(jsonPath("$.exceptionMessage").value(endsWith("Payment gateway returned 500")))
                .andExpect(jsonPath("$.candidates", hasSize(3)));
        mvc.perform(get("/api/error-logs?serverId=" + multiServer))
                .andExpect(jsonPath("$.items[0].exceptionClass").value(nullValue()));
    }

    @Test
    void cropValidation() throws Exception {
        String id = searchId(npeServer, NPE_TIME);
        patchCrop(id, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        patchCrop(id, "{ \"expandBefore\": 0 }").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.expandBefore").exists());
        patchCrop(id, "{ \"anchorLine\": 27 }").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.anchorLine").value(endsWith("Candidates: [26]")));
        patchCrop("20000101-001", "{ \"expandBefore\": 5 }").andExpect(status().isNotFound());
    }

    // ---- confirm / discard ----

    @Test
    void confirmThenOnlyLaterTransitions() throws Exception {
        String id = searchId(npeServer, NPE_TIME);
        mvc.perform(post("/api/error-logs/" + id + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.crop.lines", hasSize(35)));
        mvc.perform(post("/api/error-logs/" + id + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                .andExpect(jsonPath("$.details.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.details.action").value("CONFIRM"));
        mvc.perform(post("/api/error-logs/" + id + "/discard")).andExpect(status().isConflict());
        patchCrop(id, "{ \"expandBefore\": 5 }").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(jdbc.queryForObject("SELECT status FROM error_logs WHERE id = ?", String.class, id)).isEqualTo("CONFIRMED");
    }

    @Test
    void discard() throws Exception {
        String id = searchId(npeServer, NPE_TIME);
        mvc.perform(post("/api/error-logs/" + id + "/discard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        mvc.perform(post("/api/error-logs/" + id + "/confirm")).andExpect(status().isConflict());
        mvc.perform(post("/api/error-logs/20000101-001/discard")).andExpect(status().isNotFound());
    }

    // ---- list ----

    void insert(String id, long serverId, String status, String requestedTime, String createdAt) {
        jdbc.update("""
                INSERT INTO error_logs (id, server_id, requested_time, window_min, raw_file_path, crop_start_line,
                    crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, exception_class, status,
                    created_at, updated_at)
                VALUES (?, ?, ?::timestamptz, 5, 'raw', 1, 1, 1, 1, 1, '[]', 'X', ?, ?::timestamptz, ?::timestamptz)""",
                id, serverId, requestedTime, status, createdAt, createdAt);
    }

    @Test
    void listFiltersPagesAndSortsByCreatedAtDesc() throws Exception {
        insert("20261101-001", npeServer, "CROPPED", "2026-11-01T10:00:00+09:00", "2026-11-01T10:01:00+09:00");
        insert("20261102-001", npeServer, "CONFIRMED", "2026-11-02T10:00:00+09:00", "2026-11-02T10:01:00+09:00");
        insert("20261103-001", multiServer, "DISCARDED", "2026-11-03T10:00:00+09:00", "2026-11-03T10:01:00+09:00");
        insert("20261104-001", multiServer, "CROPPED", "2026-11-04T10:00:00+09:00", "2026-11-04T10:01:00+09:00");

        mvc.perform(get("/api/error-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains("20261104-001", "20261103-001", "20261102-001", "20261101-001")))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].serverName").value("multi-api"))
                .andExpect(jsonPath("$.items[0].serverTimezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.items[3].serverTimezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.items[0].requestedTime").value("2026-11-04T01:00:00Z"))
                .andExpect(jsonPath("$.items[0].crop").doesNotExist());
        mvc.perform(get("/api/error-logs?status=CROPPED&status=CONFIRMED"))
                .andExpect(jsonPath("$.items[*].id").value(contains("20261104-001", "20261102-001", "20261101-001")));
        mvc.perform(get("/api/error-logs?serverId=" + npeServer + "&status=CROPPED"))
                .andExpect(jsonPath("$.items[*].id").value(contains("20261101-001")));
        mvc.perform(get("/api/error-logs").param("from", "2026-11-02T10:00:00+09:00").param("to", "2026-11-03T01:00:00Z"))
                .andExpect(jsonPath("$.items[*].id").value(contains("20261103-001", "20261102-001")));
        mvc.perform(get("/api/error-logs?page=1&size=3"))
                .andExpect(jsonPath("$.items[*].id").value(contains("20261101-001")))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3));
        mvc.perform(get("/api/error-logs?size=0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.size").exists());
        mvc.perform(get("/api/error-logs?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/error-logs?status=NOPE")).andExpect(status().isBadRequest());
    }

    // ---- OpenAPI ----

    @Test
    void openApiDescribesErrorLogEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/error-logs/search'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/search'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/search'].post.responses['502']").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs'].get.parameters[*].name")
                        .value(contains("status", "serverId", "from", "to", "page", "size")))
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/crop'].patch.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/confirm'].post").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/discard'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ErrorLogDetail.properties.crop").exists())
                .andExpect(jsonPath("$.components.schemas.ErrorLogDetail.properties.candidates").exists())
                .andExpect(jsonPath("$.components.schemas.CropDto.properties.expandStep").exists())
                .andExpect(jsonPath("$.components.schemas.CropDto.properties.maxLines").exists())
                .andExpect(jsonPath("$.components.schemas.CropLineDto.properties.kind.enum")
                        .value(contains("CONTEXT", "ANCHOR", "STACK")))
                .andExpect(jsonPath("$.components.schemas.ErrorLogPage.properties.items").exists())
                .andExpect(jsonPath("$.components.schemas.ErrorLogSummary.properties.status.enum", hasSize(6)));
    }
}
