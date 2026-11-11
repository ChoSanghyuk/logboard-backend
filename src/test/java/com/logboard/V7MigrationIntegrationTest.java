package com.logboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.logboard.common.LogboardProperties;
import com.logboard.common.SecretCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * B12: a DB at V6 with servers and error logs (incl. a soft-deleted server) is migrated to V7 by the app start;
 * the old error logs still open, re-crop and analyze, and the servers have one instance each.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V7MigrationIntegrationTest {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final Path ROOT = createTempDir();
    static final String RAW = "raw/1/20261108143300-app.log";
    static final List<String> LINES = List.of(
            "2026-11-08T14:31:00.000+09:00  INFO 1 --- [a] [main] c.f.X : before",
            "2026-11-08T14:31:58.000+09:00 ERROR 1 --- [a] [main] c.f.X : Request failed",
            "java.lang.NullPointerException: name is null",
            "\tat com.foo.Order.place(Order.java:42)",
            "2026-11-08T14:32:10.000+09:00  INFO 1 --- [a] [main] c.f.X : after",
            "2026-11-08T14:33:00.000+09:00  INFO 1 --- [a] [main] c.f.X : later");
    static String secretEnc;

    static {
        postgres.start();
        prepareV6();
    }

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry r) {
        r.add("logboard.storage.root", ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretCipher cipher;

    static void prepareV6() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).target("6").load().migrate();
        secretEnc = new SecretCipher(new LogboardProperties(null, new LogboardProperties.Crypto("test-master-key"),
                null, null)).encrypt("old-pw");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("""
                INSERT INTO servers (id, name, host, ssh_port, username, auth_type, secret_enc, log_path, rotated_pattern,
                    log_pattern_key, timezone, charset, host_key_fingerprint, created_at, updated_at)
                VALUES (1, 'order-api', '10.0.1.11', 2200, 'app', 'PASSWORD', ?, '/var/log/app.log', NULL,
                    'SPRING_BOOT_3', 'Asia/Seoul', 'UTF-8', 'SHA256:old', now(), now())""", secretEnc);
        jdbc.update("""
                INSERT INTO servers (id, name, host, ssh_port, username, auth_type, log_path, log_pattern_key,
                    deleted_at, created_at, updated_at)
                VALUES (2, 'gone-api', '10.0.9.9', 22, 'app', 'KEY', '/x.log', 'SPRING_BOOT_3', now(), now(), now())""");
        String candidates = """
                [{"anchorLine":2,"endLine":4,"time":"2026-11-08T05:31:58Z","exceptionClass":"java.lang.NullPointerException",\
                "message":"name is null"},{"anchorLine":5,"endLine":5,"time":"2026-11-08T05:32:10Z","exceptionClass":null,\
                "message":"after"}]""";
        for (String[] row : new String[][] {{"20261108-001", "CROPPED", "1"}, {"20261108-002", "CONFIRMED", "1"},
                {"20261108-003", "CONFIRMED", "2"}}) {
            jdbc.update("""
                    INSERT INTO error_logs (id, server_id, requested_time, window_min, raw_file_path, crop_start_line,
                        crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, exception_class,
                        exception_message, status, created_at, updated_at)
                    VALUES (?, ?, '2026-11-08T14:32:00+09:00', 5, ?, 2, 5, 2, 4, 6, ?,
                        'java.lang.NullPointerException', 'name is null', ?, now(), now())""",
                    row[0], Long.parseLong(row[2]), RAW, candidates, row[1]);
            writeFile("errors/" + row[0] + ".log", String.join("\n", LINES.subList(1, 5)) + "\n");
        }
        writeFile(RAW, String.join("\n", LINES) + "\n");
        jdbc.update("INSERT INTO llm_endpoints (name, base_url, model, timeout_sec) VALUES ('llm', 'http://127.0.0.1:1', 'm', 1)");
        jdbc.update("INSERT INTO prompt_templates (name, content) VALUES ('p', 'On {{serverName}} {{host}}: {{errorLog}}')");
    }

    static void writeFile(String rel, String content) {
        try {
            Files.createDirectories(ROOT.resolve(rel).getParent());
            Files.writeString(ROOT.resolve(rel), content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Path createTempDir() {
        try {
            return Files.createTempDirectory("logboard-v7");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    long instanceOf(long serverId) {
        return jdbc.queryForObject("SELECT id FROM server_instances WHERE server_id = ?", Long.class, serverId);
    }

    @Test
    void serversHaveOneInstanceEachWithTheOldValues() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM server_instances", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT secret_enc FROM server_instances WHERE server_id = 1", String.class))
                .isEqualTo(secretEnc);
        assertThat(cipher.decrypt(jdbc.queryForObject("SELECT secret_enc FROM server_instances WHERE server_id = 1",
                String.class))).isEqualTo("old-pw");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM server_instances WHERE server_id = 2",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'servers'",
                String.class)).doesNotContain("host", "ssh_port", "log_path", "username", "auth_type", "secret_enc",
                "host_key_fingerprint");
        mvc.perform(get("/api/servers"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].instances", hasSize(1)))
                .andExpect(jsonPath("$[0].instances[0].host").value("10.0.1.11"))
                .andExpect(jsonPath("$[0].instances[0].sshPort").value(2200))
                .andExpect(jsonPath("$[0].instances[0].logPath").value("/var/log/app.log"))
                .andExpect(jsonPath("$[0].instances[0].username").value("app"))
                .andExpect(jsonPath("$[0].instances[0].authType").value("PASSWORD"))
                .andExpect(jsonPath("$[0].instances[0].hasSecret").value(true))
                .andExpect(jsonPath("$[0].instances[0].hostKeyFingerprint").value("SHA256:old"));
    }

    @Test
    void oldErrorLogsOpenWithTheirInstance() throws Exception {
        long inst = instanceOf(1);
        assertThat(jdbc.queryForObject("SELECT raw_files FROM error_logs WHERE id = '20261108-001'", String.class))
                .isEqualTo("{\"" + inst + "\" : {\"path\" : \"" + RAW + "\", \"totalLines\" : 6}}");
        mvc.perform(get("/api/error-logs/20261108-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(inst))
                .andExpect(jsonPath("$.instance.host").value("10.0.1.11"))
                .andExpect(jsonPath("$.instance.logPath").value("/var/log/app.log"))
                .andExpect(jsonPath("$.fetchFailures", hasSize(0)))
                .andExpect(jsonPath("$.candidates", hasSize(2)))
                .andExpect(jsonPath("$.candidates[0].instanceId").value(inst))
                .andExpect(jsonPath("$.candidates[0].host").value("10.0.1.11"))
                .andExpect(jsonPath("$.candidates[1].anchorLine").value(5))
                .andExpect(jsonPath("$.crop.lines", hasSize(4)))
                .andExpect(jsonPath("$.crop.lines[0].kind").value("ANCHOR"));
        // error log of the soft-deleted server: its instance is soft-deleted too, still shown
        mvc.perform(get("/api/error-logs/20261108-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverName").value("gone-api"))
                .andExpect(jsonPath("$.instance.host").value("10.0.9.9"));
        mvc.perform(get("/api/error-logs?serverId=1")).andExpect(jsonPath("$.items[*].instance.id").value(
                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is((int) inst))));
    }

    @Test
    void oldErrorLogReCrops() throws Exception {
        mvc.perform(patch("/api/error-logs/20261108-001/crop").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"expandBefore\": 5, \"expandAfter\": 5 }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crop.startLine").value(1))
                .andExpect(jsonPath("$.crop.endLine").value(6));
        mvc.perform(patch("/api/error-logs/20261108-001/crop").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"anchorLine\": 5 }")) // old clients: no instanceId
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crop.anchorLine").value(5))
                .andExpect(jsonPath("$.exceptionClass").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void oldErrorLogAnalyzesWithTheInstanceHost() throws Exception {
        long ep = jdbc.queryForObject("SELECT id FROM llm_endpoints", Long.class);
        long pr = jdbc.queryForObject("SELECT id FROM prompt_templates", Long.class);
        String res = mvc.perform(post("/api/error-logs/20261108-002/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"llmEndpointId\": %d, \"promptTemplateId\": %d }".formatted(ep, pr)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long analysis = ((Number) JsonPath.read(res, "$.id")).longValue();
        assertThat(jdbc.queryForObject("SELECT rendered_prompt FROM analyses WHERE id = ?", String.class, analysis))
                .startsWith("On order-api 10.0.1.11: 2026-11-08T14:31:58.000+09:00 ERROR");
    }
}
