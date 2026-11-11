package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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

    static String instance(String host, String extra) {
        return """
                { "host": "%s", "username": "app", "authType": "PASSWORD", "password": "%s",
                  "logPath": "/var/log/app.log" %s }""".formatted(host, PASSWORD, extra);
    }

    static String body(String name, String extra) {
        return withInstances(name, extra, instance("10.0.0.1", ""));
    }

    static String withInstances(String name, String extra, String... instances) {
        return """
                { "name": "%s", "instances": [%s], "logPatternKey": "SPRING_BOOT_3" %s }
                """.formatted(name, String.join(",", instances), extra);
    }

    ResultActions postServer(String json) throws Exception {
        return mvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions putServer(long id, String json) throws Exception {
        return mvc.perform(put("/api/servers/" + id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    long create(String name) throws Exception {
        return id(postServer(body(name, "")).andExpect(status().isCreated()));
    }

    static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    static long instanceId(ResultActions r, int n) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.instances[" + n + "].id"))
                .longValue();
    }

    long firstInstance(long serverId) {
        return jdbc.queryForObject("SELECT min(id) FROM server_instances WHERE server_id = ?", Long.class, serverId);
    }

    String storedSecret(long instanceId) {
        return jdbc.queryForObject("SELECT secret_enc FROM server_instances WHERE id = ?", String.class, instanceId);
    }

    void insertErrorLog(String id, long serverId, long instanceId) {
        jdbc.update("""
                INSERT INTO error_logs (id, server_id, instance_id, requested_time, window_min, raw_file_path,
                    crop_start_line, crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, raw_files,
                    fetch_failures, status, created_at, updated_at)
                VALUES (?, ?, ?, now(), 5, '/raw', 1, 3, 2, 2, 3, '[]', '{}', '[]', 'CROPPED', now(), now())""",
                id, serverId, instanceId);
    }

    // ---- CRUD ----

    @Test
    void createAppliesDefaultsAndReturnsDto() throws Exception {
        postServer(body("order-api", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("order-api"))
                .andExpect(jsonPath("$.instances", hasSize(1)))
                .andExpect(jsonPath("$.instances[0].id").isNumber())
                .andExpect(jsonPath("$.instances[0].host").value("10.0.0.1"))
                .andExpect(jsonPath("$.instances[0].sshPort").value(22))
                .andExpect(jsonPath("$.instances[0].logPath").value("/var/log/app.log"))
                .andExpect(jsonPath("$.instances[0].username").value("app"))
                .andExpect(jsonPath("$.instances[0].authType").value("PASSWORD"))
                .andExpect(jsonPath("$.instances[0].hasSecret").value(true))
                .andExpect(jsonPath("$.instances[0].hostKeyFingerprint").value(nullValue()))
                .andExpect(jsonPath("$.instances[0].createdAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.logPatternKey").value("SPRING_BOOT_3"))
                .andExpect(jsonPath("$.customLogRegex").value(nullValue()))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.charset").value("UTF-8"))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z"))) // contract: UTC
                .andExpect(jsonPath("$.updatedAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.host").doesNotExist()) // moved to instances
                .andExpect(jsonPath("$.hasSecret").doesNotExist())
                .andExpect(jsonPath("$.instances[0].password").doesNotExist())
                .andExpect(jsonPath("$.instances[0].privateKey").doesNotExist())
                .andExpect(jsonPath("$.instances[0].secretEnc").doesNotExist());
    }

    @Test
    void listReturnsPlainArrayWithInstances() throws Exception {
        create("b-api");
        postServer(withInstances("a-api", "", instance("h1", ""), instance("h2", ", \"sshPort\": 2200")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("a-api"))
                .andExpect(jsonPath("$[0].instances[*].host").value(contains("h1", "h2")))
                .andExpect(jsonPath("$[0].instances[1].sshPort").value(2200))
                .andExpect(jsonPath("$[1].instances", hasSize(1)));
    }

    @Test
    void updateChangesFields() throws Exception {
        long id = create("order-api");
        long inst = firstInstance(id);
        putServer(id, withInstances("order-api-2", ", \"timezone\": \"UTC\", \"rotatedPattern\": \"app.%d{yyyy-MM-dd}.log.gz\"",
                instance("10.0.0.1", ", \"id\": " + inst + ", \"sshPort\": 2222")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("order-api-2"))
                .andExpect(jsonPath("$.instances[0].id").value(inst))
                .andExpect(jsonPath("$.instances[0].sshPort").value(2222))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.rotatedPattern").value("app.%d{yyyy-MM-dd}.log.gz"));
    }

    @Test
    void updateSwitchesToCustomRegex() throws Exception {
        long id = create("order-api");
        putServer(id, """
                { "name": "order-api", "instances": [{ "id": %d, "host": "h", "username": "u", "authType": "PASSWORD",
                  "logPath": "/a.log" }], "customLogRegex": "^(?<ts>\\\\S+ \\\\S+) (?<level>\\\\w+)" }
                """.formatted(firstInstance(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logPatternKey").value(nullValue()))
                .andExpect(jsonPath("$.customLogRegex").value("^(?<ts>\\S+ \\S+) (?<level>\\w+)"));
    }

    @Test
    void addUpdateReorderAndRemoveInstances() throws Exception {
        ResultActions created = postServer(withInstances("order-api", "", instance("a", ""), instance("b", ""),
                instance("c", "")));
        long id = id(created);
        long a = instanceId(created, 0);
        long b = instanceId(created, 1);
        long c = instanceId(created, 2);
        insertErrorLog("20261108-001", id, c); // c is referenced → soft delete; b is not → hard delete

        ResultActions updated = putServer(id, withInstances("order-api", "",
                instance("d", ""), // new
                instance("a2", ", \"id\": " + a + ", \"password\": \"\""))) // moved to position 1, renamed
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instances[*].host").value(contains("d", "a2")))
                .andExpect(jsonPath("$.instances[1].id").value(a))
                .andExpect(jsonPath("$.instances[1].hasSecret").value(true));
        assertThat(instanceId(updated, 0)).isNotIn(a, b, c);
        assertThat(jdbc.queryForObject("SELECT position FROM server_instances WHERE id = ?", Integer.class, a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM server_instances WHERE id = ?", Integer.class, b)).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM server_instances WHERE id = ?", Boolean.class, c))
                .isTrue();
        mvc.perform(get("/api/servers")).andExpect(jsonPath("$[0].instances", hasSize(2)));

        // a removed instance can't be updated any more
        putServer(id, withInstances("order-api", "", instance("x", ", \"id\": " + c)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[0].id']").exists());
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
        putServer(other, withInstances("order-api", "", instance("10.0.0.1", ", \"id\": " + firstInstance(other))))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteWithoutErrorLogsIsHard() throws Exception {
        long id = create("order-api");
        mvc.perform(delete("/api/servers/" + id)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM server_instances", Integer.class)).isZero();
    }

    @Test
    void deleteWithErrorLogsIsSoftAndHidesServer() throws Exception {
        long id = create("order-api");
        long inst = firstInstance(id);
        insertErrorLog("20261108-001", id, inst);

        mvc.perform(delete("/api/servers/" + id)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM servers WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM server_instances WHERE id = ?", Boolean.class,
                inst)).isTrue();
        mvc.perform(get("/api/servers")).andExpect(jsonPath("$", hasSize(0)));
        putServer(id, body("order-api", "")).andExpect(status().isNotFound());
        mvc.perform(post("/api/servers/" + id + "/test")).andExpect(status().isNotFound());
        mvc.perform(post("/api/servers/" + id + "/instances/" + inst + "/reset-host-key")).andExpect(status().isNotFound());
        // the name of a soft-deleted server can be reused
        create("order-api");
    }

    // ---- host key reset ----

    @Test
    void resetHostKeyIsPerInstance() throws Exception {
        ResultActions created = postServer(withInstances("order-api", "", instance("a", ""), instance("b", "")));
        long id = id(created);
        long a = instanceId(created, 0);
        long b = instanceId(created, 1);
        jdbc.update("UPDATE server_instances SET host_key_fingerprint = 'SHA256:pinned' WHERE server_id = ?", id);

        mvc.perform(post("/api/servers/" + id + "/instances/" + b + "/reset-host-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.instances[0].hostKeyFingerprint").value("SHA256:pinned"))
                .andExpect(jsonPath("$.instances[1].hostKeyFingerprint").value(nullValue()));

        long other = create("pay-api");
        mvc.perform(post("/api/servers/" + other + "/instances/" + a + "/reset-host-key")) // not its instance
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/servers/" + id + "/instances/999999/reset-host-key")).andExpect(status().isNotFound());
        mvc.perform(post("/api/servers/999999/instances/" + a + "/reset-host-key")).andExpect(status().isNotFound());
        mvc.perform(post("/api/servers/" + id + "/reset-host-key")).andExpect(status().isNotFound()); // removed
    }

    @Test
    void hostOrPortChangeClearsOnlyThatFingerprint() throws Exception {
        ResultActions created = postServer(withInstances("order-api", "", instance("a", ""), instance("b", ""),
                instance("c", "")));
        long id = id(created);
        long a = instanceId(created, 0);
        long b = instanceId(created, 1);
        long c = instanceId(created, 2);
        jdbc.update("UPDATE server_instances SET host_key_fingerprint = 'SHA256:pinned' WHERE server_id = ?", id);

        putServer(id, withInstances("order-api", "",
                instance("a", ", \"id\": " + a + ", \"logPath\": \"/other.log\", \"username\": \"other\""),
                instance("b2", ", \"id\": " + b),
                instance("c", ", \"id\": " + c + ", \"sshPort\": 2222")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instances[0].hostKeyFingerprint").value("SHA256:pinned")) // same host:port
                .andExpect(jsonPath("$.instances[1].hostKeyFingerprint").value(nullValue()))
                .andExpect(jsonPath("$.instances[2].hostKeyFingerprint").value(nullValue()));
    }

    // ---- secrets ----

    @Test
    void secretsAreEncryptedPerInstanceAndNeverReturned() throws Exception {
        String keyInstance = """
                { "host": "h", "username": "u", "authType": "KEY", "privateKey": "%s", "logPath": "/a.log" }"""
                .formatted(PRIVATE_KEY.replace("\n", "\\n"));
        ResultActions createdRes = postServer(withInstances("order-api", "", instance("10.0.0.1", ""), keyInstance))
                .andExpect(status().isCreated());
        String created = createdRes.andReturn().getResponse().getContentAsString();
        long id = id(createdRes);
        long pw = instanceId(createdRes, 0);
        long key = instanceId(createdRes, 1);
        String updated = putServer(id, withInstances("order-api", "", instance("10.0.0.1", ", \"id\": " + pw),
                keyInstance.replace("{", "{ \"id\": " + key + ", ")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String listed = mvc.perform(get("/api/servers")).andReturn().getResponse().getContentAsString();

        for (String response : new String[] {created, updated, listed}) {
            assertThat(response).doesNotContain(PASSWORD).doesNotContain("S3CR3T-KEY-BODY")
                    .doesNotContain("\"password\"").doesNotContain("\"privateKey\"").doesNotContain("secretEnc");
        }
        assertThat(storedSecret(pw)).doesNotContain(PASSWORD);
        assertThat(cipher.decrypt(storedSecret(pw))).isEqualTo(PASSWORD);
        assertThat(cipher.decrypt(storedSecret(key))).isEqualTo(PRIVATE_KEY);
    }

    @Test
    void blankOrAbsentSecretOnUpdateKeepsThatInstancesStoredOne() throws Exception {
        long id = create("order-api");
        long inst = firstInstance(id);
        String absent = """
                { "name": "order-api", "instances": [{ "id": %d, "host": "h", "username": "u", "authType": "PASSWORD",
                  "logPath": "/a.log" %%s }], "logPatternKey": "SPRING_BOOT_3" }""".formatted(inst);
        putServer(id, absent.formatted("")).andExpect(status().isOk())
                .andExpect(jsonPath("$.instances[0].hasSecret").value(true));
        assertThat(cipher.decrypt(storedSecret(inst))).isEqualTo(PASSWORD);

        putServer(id, absent.formatted(", \"password\": \"  \"")).andExpect(status().isOk());
        assertThat(cipher.decrypt(storedSecret(inst))).isEqualTo(PASSWORD);

        putServer(id, absent.formatted(", \"password\": \"new-pw\"")).andExpect(status().isOk());
        assertThat(cipher.decrypt(storedSecret(inst))).isEqualTo("new-pw");
    }

    @Test
    void secretRequiredOnNewInstanceAndOnAuthTypeChange() throws Exception {
        postServer(withInstances("order-api", "", instance("a", ""), instance("b", "").replace(PASSWORD, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['instances[1].password']").exists())
                .andExpect(jsonPath("$.details.fields['instances[0].password']").doesNotExist());

        long id = create("order-api");
        long inst = firstInstance(id);
        putServer(id, withInstances("order-api", "", instance("10.0.0.1", ", \"id\": " + inst).replace("\"PASSWORD\"", "\"KEY\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[0].privateKey']").exists());
        // a new instance added on update needs its own secret
        putServer(id, withInstances("order-api", "", instance("10.0.0.1", ", \"id\": " + inst),
                instance("new", "").replace(PASSWORD, " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[1].password']").exists());
    }

    // ---- validation ----

    @Test
    void beanValidationErrors() throws Exception {
        postServer("{ }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.name").exists())
                .andExpect(jsonPath("$.details.fields.instances").exists());
        postServer(withInstances("a", "", instance("ok", ""), "{ \"sshPort\": 70000 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[1].host']").exists())
                .andExpect(jsonPath("$.details.fields['instances[1].username']").exists())
                .andExpect(jsonPath("$.details.fields['instances[1].authType']").exists())
                .andExpect(jsonPath("$.details.fields['instances[1].logPath']").exists())
                .andExpect(jsonPath("$.details.fields['instances[1].sshPort']").exists())
                .andExpect(jsonPath("$.details.fields['instances[0].host']").doesNotExist());
    }

    @Test
    void instanceCountMustBe1To20() throws Exception {
        postServer(withInstances("a", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.instances").exists());
        String[] many = new String[21];
        for (int i = 0; i < many.length; i++) {
            many[i] = instance("h" + i, "");
        }
        postServer(withInstances("a", "", many)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.instances").exists());
        postServer(withInstances("a", "", java.util.Arrays.copyOf(many, 20))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.instances", hasSize(20)));
    }

    @Test
    void sameTargetTwiceIsRejectedOnTheLaterOne() throws Exception {
        postServer(withInstances("a", "", instance("h", ""), instance("other", ""), instance("h", ", \"sshPort\": 22")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[2].host']").value(containsString("Same host")))
                .andExpect(jsonPath("$.details.fields['instances[0].host']").doesNotExist());
        // other port or other log path = another instance
        postServer(withInstances("a", "", instance("h", ""), instance("h", ", \"sshPort\": 2222"),
                instance("h", ", \"logPath\": \"/other.log\""))).andExpect(status().isCreated());
    }

    @Test
    void instanceIdMustBelongToTheServer() throws Exception {
        long id = create("order-api");
        long own = firstInstance(id);
        long foreign = firstInstance(create("pay-api"));
        putServer(id, withInstances("order-api", "", instance("10.0.0.1", ", \"id\": " + foreign)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[0].id']").exists());
        putServer(id, withInstances("order-api", "", instance("a", ", \"id\": " + own), instance("b", ", \"id\": " + own)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[1].id']").value(containsString("twice")));
        postServer(withInstances("new-api", "", instance("10.0.0.1", ", \"id\": " + own)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields['instances[0].id']").exists());
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
                .andExpect(jsonPath("$[*].key").value(contains("SPRING_BOOT_3", "SPRING_BOOT_2", "LOGBACK_DEFAULT")))
                .andExpect(jsonPath("$[0].regex").value(containsString("?<ts>")))
                .andExpect(jsonPath("$[0].example").isString());
    }

    @Test
    void openApiDescribesServerEndpoints() throws Exception {
        String s = "$.components.schemas.";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/servers'].get").exists())
                .andExpect(jsonPath("$.paths['/api/servers'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}/test'].post").exists())
                .andExpect(jsonPath("$.paths['/api/log-patterns'].get").exists())
                .andExpect(jsonPath("$.paths['/api/servers/{id}/reset-host-key']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/servers/{id}/instances/{instanceId}/reset-host-key'].post").exists())
                .andExpect(jsonPath(s + "ServerDto.properties.host").doesNotExist())
                .andExpect(jsonPath(s + "ServerDto.properties.instances.items['$ref']").value(endsWith("/InstanceDto")))
                .andExpect(jsonPath(s + "InstanceDto.properties.hostKeyFingerprint.type").value(contains("string", "null")))
                .andExpect(jsonPath(s + "InstanceDto.properties.hasSecret.type").value("boolean"))
                .andExpect(jsonPath(s + "InstanceDto.properties.password").doesNotExist())
                .andExpect(jsonPath(s + "InstanceDto.required").value(hasItems("id", "host", "hostKeyFingerprint")))
                .andExpect(jsonPath(s + "ServerUpsert.required").value(hasItems("name", "instances")))
                .andExpect(jsonPath(s + "ServerUpsert.properties.instances.items['$ref']").value(endsWith("/InstanceUpsert")))
                .andExpect(jsonPath(s + "InstanceUpsert.properties.password").exists())
                .andExpect(jsonPath(s + "InstanceUpsert.properties.id.type").value(contains("integer", "null")))
                .andExpect(jsonPath(s + "ConnectionTestResult.properties.results.items['$ref']")
                        .value(endsWith("/InstanceTestResult")))
                .andExpect(jsonPath(s + "InstanceTestResult.properties.logFileSize.type").value(contains("integer", "null")))
                .andExpect(jsonPath(s + "InstanceTestResult.required")
                        .value(hasItems("instanceId", "host", "sshPort", "logPath", "ok", "message", "logFileFound")));
    }
}
