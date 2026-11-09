package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.logboard.common.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ServerApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final String PASSWORD = "Sup3r-S3cret-PW";
    static final String PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nS3CR3T-KEY-BODY\n-----END OPENSSH PRIVATE KEY-----";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretCipher cipher;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM error_logs");
        jdbc.update("DELETE FROM servers");
    }

    static String body(String name, String extra) {
        return """
                { "name": "%s", "host": "10.0.0.1", "username": "app", "authType": "PASSWORD",
                  "password": "%s", "logPath": "/var/log/app.log", "logPatternKey": "SPRING_BOOT_3" %s }
                """.formatted(name, PASSWORD, extra);
    }

    ResultActions postServer(String json) throws Exception {
        return mvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions putServer(long id, String json) throws Exception {
        return mvc.perform(put("/api/servers/" + id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    long create(String name) throws Exception {
        String res = postServer(body(name, "")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }

    String storedSecret(long id) {
        return jdbc.queryForObject("SELECT secret_enc FROM servers WHERE id = ?", String.class, id);
    }

    // ---- CRUD ----

    @Test
    void createAppliesDefaultsAndReturnsDto() throws Exception {
        postServer(body("order-api", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("order-api"))
                .andExpect(jsonPath("$.sshPort").value(22))
                .andExpect(jsonPath("$.authType").value("PASSWORD"))
                .andExpect(jsonPath("$.logPatternKey").value("SPRING_BOOT_3"))
                .andExpect(jsonPath("$.customLogRegex").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.charset").value("UTF-8"))
                .andExpect(jsonPath("$.hasSecret").value(true))
                .andExpect(jsonPath("$.hostKeyFingerprint").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.endsWith("Z"))) // contract: UTC
                .andExpect(jsonPath("$.updatedAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.privateKey").doesNotExist())
                .andExpect(jsonPath("$.secretEnc").doesNotExist());
    }

    @Test
    void listReturnsPlainArray() throws Exception {
        create("b-api");
        create("a-api");
        mvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("a-api"));
    }

    @Test
    void updateChangesFields() throws Exception {
        long id = create("order-api");
        putServer(id, body("order-api-2", ", \"sshPort\": 2222, \"timezone\": \"UTC\", \"rotatedPattern\": \"app.%d{yyyy-MM-dd}.log.gz\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("order-api-2"))
                .andExpect(jsonPath("$.sshPort").value(2222))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.rotatedPattern").value("app.%d{yyyy-MM-dd}.log.gz"));
    }

    @Test
    void updateSwitchesToCustomRegex() throws Exception {
        long id = create("order-api");
        putServer(id, """
                { "name": "order-api", "host": "h", "username": "u", "authType": "PASSWORD", "logPath": "/a.log",
                  "customLogRegex": "^(?<ts>\\\\S+ \\\\S+) (?<level>\\\\w+)" }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logPatternKey").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.customLogRegex").value("^(?<ts>\\S+ \\S+) (?<level>\\w+)"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        putServer(999999, body("x", "")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete("/api/servers/999999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/servers/999999/test")).andExpect(status().isNotFound());
    }

    @Test
    void duplicateNameIs409() throws Exception {
        create("order-api");
        long other = create("pay-api");
        postServer(body("order-api", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));
        putServer(other, body("order-api", "")).andExpect(status().isConflict());
    }

    @Test
    void deleteWithoutErrorLogsIsHard() throws Exception {
        long id = create("order-api");
        mvc.perform(delete("/api/servers/" + id)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servers", Integer.class)).isZero();
    }

    @Test
    void deleteWithErrorLogsIsSoftAndHidesServer() throws Exception {
        long id = create("order-api");
        jdbc.update("""
                INSERT INTO error_logs (id, server_id, requested_time, window_min, raw_file_path, crop_start_line,
                    crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, status, created_at, updated_at)
                VALUES ('20261108-001', ?, now(), 5, '/raw', 1, 3, 2, 2, 3, '[]', 'CROPPED', now(), now())""", id);

        mvc.perform(delete("/api/servers/" + id)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM servers WHERE id = ?", Boolean.class, id)).isTrue();
        mvc.perform(get("/api/servers")).andExpect(jsonPath("$", hasSize(0)));
        putServer(id, body("order-api", "")).andExpect(status().isNotFound());
        mvc.perform(post("/api/servers/" + id + "/test")).andExpect(status().isNotFound());
        // the name of a soft-deleted server can be reused
        create("order-api");
    }

    // ---- secrets ----

    @Test
    void secretsAreEncryptedAndNeverReturned() throws Exception {
        String created = postServer(body("order-api", "")).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        String keyBody = """
                { "name": "key-api", "host": "h", "username": "u", "authType": "KEY", "privateKey": "%s",
                  "logPath": "/a.log", "logPatternKey": "SPRING_BOOT_2" }""".formatted(PRIVATE_KEY.replace("\n", "\\n"));
        String createdKey = postServer(keyBody).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String updated = putServer(id, body("order-api", "")).andReturn().getResponse().getContentAsString();
        String listed = mvc.perform(get("/api/servers")).andReturn().getResponse().getContentAsString();

        for (String response : new String[] {created, createdKey, updated, listed}) {
            assertThat(response).doesNotContain(PASSWORD).doesNotContain("S3CR3T-KEY-BODY")
                    .doesNotContain("\"password\"").doesNotContain("\"privateKey\"").doesNotContain("secretEnc");
        }
        assertThat(storedSecret(id)).doesNotContain(PASSWORD);
        assertThat(cipher.decrypt(storedSecret(id))).isEqualTo(PASSWORD);
    }

    @Test
    void blankOrAbsentSecretOnUpdateKeepsStoredOne() throws Exception {
        long id = create("order-api");
        String absent = """
                { "name": "order-api", "host": "h", "username": "u", "authType": "PASSWORD",
                  "logPath": "/a.log", "logPatternKey": "SPRING_BOOT_3" }""";
        putServer(id, absent).andExpect(status().isOk()).andExpect(jsonPath("$.hasSecret").value(true));
        assertThat(cipher.decrypt(storedSecret(id))).isEqualTo(PASSWORD);

        putServer(id, absent.replace("\"logPath\"", "\"password\": \"  \", \"logPath\"")).andExpect(status().isOk());
        assertThat(cipher.decrypt(storedSecret(id))).isEqualTo(PASSWORD);

        putServer(id, absent.replace("\"logPath\"", "\"password\": \"new-pw\", \"logPath\"")).andExpect(status().isOk());
        assertThat(cipher.decrypt(storedSecret(id))).isEqualTo("new-pw");
    }

    @Test
    void secretRequiredOnCreateAndOnAuthTypeChange() throws Exception {
        postServer(body("order-api", "").replace(PASSWORD, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.password").exists());

        long id = create("order-api");
        putServer(id, body("order-api", "").replace("\"PASSWORD\"", "\"KEY\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.privateKey").exists());
    }

    // ---- validation ----

    @Test
    void beanValidationErrors() throws Exception {
        postServer("{ \"sshPort\": 70000 }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.name").exists())
                .andExpect(jsonPath("$.details.fields.host").exists())
                .andExpect(jsonPath("$.details.fields.username").exists())
                .andExpect(jsonPath("$.details.fields.authType").exists())
                .andExpect(jsonPath("$.details.fields.logPath").exists())
                .andExpect(jsonPath("$.details.fields.sshPort").exists());
    }

    @Test
    void logPatternMustBeExactlyOneAndKnown() throws Exception {
        String neither = body("a", "").replace("\"logPatternKey\": \"SPRING_BOOT_3\"", "\"timezone\": \"Asia/Seoul\"");
        postServer(neither).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.logPatternKey").value(containsString("exactly one")));
        postServer(body("a", ", \"customLogRegex\": \"(?<ts>x)(?<level>y)\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.logPatternKey").value(containsString("exactly one")));
        postServer(body("a", "").replace("SPRING_BOOT_3", "LOG4J")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.logPatternKey").value(containsString("Unknown log pattern")));
    }

    @Test
    void invalidCustomRegexIsRejected() throws Exception {
        String base = body("a", "").replace("\"logPatternKey\": \"SPRING_BOOT_3\"", "\"customLogRegex\": \"%s\"");
        postServer(base.formatted("^(?<ts>\\\\S+) INFO")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.customLogRegex").value(containsString("Missing: level")));
        postServer(base.formatted("^(?<ts>[0-9")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.customLogRegex").value(containsString("does not compile")));
    }

    @Test
    void invalidRotatedPatternIsRejected() throws Exception {
        postServer(body("a", ", \"rotatedPattern\": \"app.%i.log.gz\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.rotatedPattern").value(containsString("date token")));
    }

    @Test
    void invalidTimezoneAndCharsetAreRejected() throws Exception {
        postServer(body("a", ", \"timezone\": \"Mars/Base\", \"charset\": \"NOPE-8\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.timezone").exists())
                .andExpect(jsonPath("$.details.fields.charset").exists());
    }

    // ---- log patterns & OpenAPI ----

    @Test
    void logPatternPresets() throws Exception {
        mvc.perform(get("/api/log-patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").value(org.hamcrest.Matchers.contains(
                        "SPRING_BOOT_3", "SPRING_BOOT_2", "LOGBACK_DEFAULT")))
                .andExpect(jsonPath("$[0].regex").value(containsString("?<ts>")))
                .andExpect(jsonPath("$[0].example").isString());
    }

    @Test
    void openApiDescribesServerEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/servers'].get").exists())
                .andExpect(jsonPath("$.paths['/api/servers'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}/test'].post").exists())
                .andExpect(jsonPath("$.paths['/api/log-patterns'].get").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}/reset-host-key'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ServerDto.properties.hostKeyFingerprint.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath("$.components.schemas.ServerDto.properties.hasSecret").exists())
                .andExpect(jsonPath("$.components.schemas.ServerDto.properties.password").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ServerUpsert.properties.password").exists())
                .andExpect(jsonPath("$.components.schemas.ConnectionTestResult.properties.logFileSize").exists());
    }
}
