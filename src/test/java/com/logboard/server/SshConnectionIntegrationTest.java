package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@code POST /servers/{id}/test} against a real OpenSSH server. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SshConnectionIntegrationTest {

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

    static String ed25519PrivateKey;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void prepareServer() throws Exception {
        exec("mkdir -p /logs && printf 'line1\\nline2\\n' > /logs/app.log && chmod 644 /logs/app.log");
        exec("ssh-keygen -q -t ed25519 -N '' -f /tmp/id_ed25519"
                + " && mkdir -p /config/.ssh && cat /tmp/id_ed25519.pub >> /config/.ssh/authorized_keys"
                + " && chown -R logboard /config/.ssh && chmod 700 /config/.ssh && chmod 600 /config/.ssh/authorized_keys");
        ed25519PrivateKey = exec("cat /tmp/id_ed25519");
        // OpenSSH >= 9.8 penalizes a source address after failed logins (PerSourcePenalties): the auth-failure tests
        // would make later logins from this JVM fail. Turn it off and reload sshd (SIGHUP re-reads the config).
        exec("echo 'PerSourcePenalties no' >> /config/sshd/sshd_config && s6-svc -h /run/service/svc-openssh-server"
                + " && sleep 1 && for i in $(seq 50); do nc -z localhost 2222 && exit 0; sleep 0.2; done; exit 1");
    }

    static String exec(String cmd) throws Exception {
        var r = sshd.execInContainer("sh", "-c", cmd);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException(cmd + " failed: " + r.getStderr());
        }
        return r.getStdout();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM servers");
    }

    static String instance(String authJson, String logPath) {
        return """
                { "host": "%s", "sshPort": %d, "username": "logboard", %s, "logPath": "%s" }"""
                .formatted(sshd.getHost(), sshd.getMappedPort(2222), authJson, logPath);
    }

    long create(String authJson, String logPath) throws Exception {
        return createWith(instance(authJson, logPath));
    }

    long createWith(String... instances) throws Exception {
        String json = """
                { "name": "ssh-%d", "instances": [%s], "logPatternKey": "SPRING_BOOT_3" }"""
                .formatted(System.nanoTime(), String.join(",", instances));
        String res = mvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }

    ResultActions test(long id) throws Exception {
        return mvc.perform(post("/api/servers/" + id + "/test")).andExpect(status().isOk());
    }

    static final String PW = "\"authType\": \"PASSWORD\", \"password\": \"ssh-pw\"";

    @Test
    void passwordAuthFindsLogFile() throws Exception {
        test(create(PW, "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].instanceId").isNumber())
                .andExpect(jsonPath("$.results[0].host").value(sshd.getHost()))
                .andExpect(jsonPath("$.results[0].sshPort").value(sshd.getMappedPort(2222)))
                .andExpect(jsonPath("$.results[0].logPath").value("/logs/app.log"))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].logFileFound").value(true))
                .andExpect(jsonPath("$.results[0].logFileSize").value(12))
                .andExpect(jsonPath("$.results[0].message").value(containsString("Log file found")));
    }

    @Test
    void ed25519KeyAuthFindsLogFile() throws Exception {
        String key = ed25519PrivateKey.replace("\n", "\\n");
        test(create("\"authType\": \"KEY\", \"privateKey\": \"" + key + "\"", "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.results[0].logFileSize").value(12));
    }

    @Test
    void everyInstanceIsTestedAndOkOnlyIfAllAre() throws Exception {
        String key = ed25519PrivateKey.replace("\n", "\\n");
        long id = createWith(instance(PW, "/logs/app.log"),
                instance("\"authType\": \"KEY\", \"privateKey\": \"" + key + "\"", "/logs/nope.log"),
                instance("\"authType\": \"PASSWORD\", \"password\": \"wrong\"", "/logs/./app.log"));
        test(id).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.results", hasSize(3)))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[1].ok").value(false))
                .andExpect(jsonPath("$.results[1].logPath").value("/logs/nope.log"))
                .andExpect(jsonPath("$.results[1].message").value("Connected, but log file not found: /logs/nope.log"))
                .andExpect(jsonPath("$.results[2].ok").value(false))
                .andExpect(jsonPath("$.results[2].message").value(containsString("Authentication failed")));
    }

    @Test
    void wrongPasswordIsAuthFailure() throws Exception {
        test(create("\"authType\": \"PASSWORD\", \"password\": \"wrong\"", "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.results[0].logFileFound").value(false))
                .andExpect(jsonPath("$.results[0].logFileSize").value(nullValue()))
                .andExpect(jsonPath("$.results[0].message").value(containsString("Authentication failed for logboard@")));
    }

    @Test
    void garbagePrivateKeyIsReported() throws Exception {
        test(create("\"authType\": \"KEY\", \"privateKey\": \"not a key\"", "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.results[0].message").value(containsString("Private key could not be read")));
    }

    @Test
    void missingLogFile() throws Exception {
        test(create(PW, "/logs/nope.log"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.results[0].logFileFound").value(false))
                .andExpect(jsonPath("$.results[0].message").value("Connected, but log file not found: /logs/nope.log"));
    }

    @Test
    void directoryIsNotALogFile() throws Exception {
        test(create(PW, "/logs"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.results[0].message").value(containsString("not a regular file")));
    }

    // ---- host key: trust on first use, per instance ----

    long instance(long serverId, int position) {
        return jdbc.queryForObject("SELECT id FROM server_instances WHERE server_id = ? AND position = ?", Long.class,
                serverId, position);
    }

    String fingerprint(long instanceId) {
        return jdbc.queryForObject("SELECT host_key_fingerprint FROM server_instances WHERE id = ?", String.class,
                instanceId);
    }

    @Test
    void firstLoginPinsHostKeyAndLaterLoginsCheckIt() throws Exception {
        long id = create(PW, "/logs/app.log");
        long inst = instance(id, 0);
        assertThat(fingerprint(inst)).isNull();
        test(id).andExpect(jsonPath("$.ok").value(true));
        String pinned = fingerprint(inst);
        assertThat(pinned).startsWith("SHA256:");
        test(id).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.results[0].message").value(containsString(pinned)));
        assertThat(fingerprint(inst)).isEqualTo(pinned);
        mvc.perform(get("/api/servers")).andExpect(jsonPath("$[0].instances[0].hostKeyFingerprint").value(pinned));
    }

    @Test
    void changedHostKeyFailsOnlyThatInstanceUntilItsReset() throws Exception {
        long id = createWith(instance(PW, "/logs/app.log"), instance(PW, "/logs/./app.log"));
        long a = instance(id, 0);
        long b = instance(id, 1);
        test(id).andExpect(jsonPath("$.ok").value(true));
        String real = fingerprint(b);
        assertThat(fingerprint(a)).isEqualTo(real);
        jdbc.update("UPDATE server_instances SET host_key_fingerprint = 'SHA256:somethingElse' WHERE id = ?", b);

        test(id).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[1].ok").value(false))
                .andExpect(jsonPath("$.results[1].logFileFound").value(false))
                .andExpect(jsonPath("$.results[1].message").value(containsString("Host key of " + sshd.getHost() + ":"
                        + sshd.getMappedPort(2222) + " changed: trusted SHA256:somethingElse, but the server presented "
                        + real)))
                .andExpect(jsonPath("$.results[1].message").value(containsString("reset the host key")));
        assertThat(fingerprint(b)).isEqualTo("SHA256:somethingElse"); // not silently replaced

        mvc.perform(post("/api/servers/" + id + "/instances/" + b + "/reset-host-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.instances[0].hostKeyFingerprint").value(real))
                .andExpect(jsonPath("$.instances[1].hostKeyFingerprint").value(nullValue()));
        test(id).andExpect(jsonPath("$.ok").value(true));
        assertThat(fingerprint(b)).isEqualTo(real);
    }

    @Test
    void failedLoginDoesNotPinTheKey() throws Exception {
        long id = create("\"authType\": \"PASSWORD\", \"password\": \"wrong\"", "/logs/app.log");
        test(id).andExpect(jsonPath("$.ok").value(false));
        assertThat(fingerprint(instance(id, 0))).isNull();
    }

    @Test
    void changingHostOrPortForgetsTheKey() throws Exception {
        long id = create(PW, "/logs/app.log");
        long inst = instance(id, 0);
        test(id);
        String body = """
                { "name": "renamed", "instances": [{ "id": %d, "host": "%s", "sshPort": %d, "username": "logboard",
                  "authType": "PASSWORD", "logPath": "/logs/app.log" }], "logPatternKey": "SPRING_BOOT_3" }""";
        mvc.perform(put("/api/servers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(inst, sshd.getHost(), sshd.getMappedPort(2222))))
                .andExpect(jsonPath("$.instances[0].hostKeyFingerprint").value(containsString("SHA256:"))); // same: kept
        mvc.perform(put("/api/servers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(inst, "other-host.invalid", 22)))
                .andExpect(jsonPath("$.instances[0].hostKeyFingerprint").value(nullValue()));
    }
}
