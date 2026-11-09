package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

    long create(String authJson, String logPath) throws Exception {
        String json = """
                { "name": "ssh-%d", "host": "%s", "sshPort": %d, "username": "logboard", %s,
                  "logPath": "%s", "logPatternKey": "SPRING_BOOT_3" }"""
                .formatted(System.nanoTime(), sshd.getHost(), sshd.getMappedPort(2222), authJson, logPath);
        String res = mvc.perform(post("/api/servers").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.id")).longValue();
    }

    ResultActions test(long id) throws Exception {
        return mvc.perform(post("/api/servers/" + id + "/test")).andExpect(status().isOk());
    }

    @Test
    void passwordAuthFindsLogFile() throws Exception {
        test(create("\"authType\": \"PASSWORD\", \"password\": \"ssh-pw\"", "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.logFileFound").value(true))
                .andExpect(jsonPath("$.logFileSize").value(12))
                .andExpect(jsonPath("$.message").value(containsString("Log file found")));
    }

    @Test
    void ed25519KeyAuthFindsLogFile() throws Exception {
        String key = ed25519PrivateKey.replace("\n", "\\n");
        test(create("\"authType\": \"KEY\", \"privateKey\": \"" + key + "\"", "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.logFileSize").value(12));
    }

    @Test
    void wrongPasswordIsAuthFailure() throws Exception {
        test(create("\"authType\": \"PASSWORD\", \"password\": \"wrong\"", "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.logFileFound").value(false))
                .andExpect(jsonPath("$.logFileSize").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value(containsString("Authentication failed for logboard@")));
    }

    @Test
    void garbagePrivateKeyIsReported() throws Exception {
        test(create("\"authType\": \"KEY\", \"privateKey\": \"not a key\"", "/logs/app.log"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("Private key could not be read")));
    }

    @Test
    void missingLogFile() throws Exception {
        test(create("\"authType\": \"PASSWORD\", \"password\": \"ssh-pw\"", "/logs/nope.log"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.logFileFound").value(false))
                .andExpect(jsonPath("$.message").value("Connected, but log file not found: /logs/nope.log"));
    }

    @Test
    void directoryIsNotALogFile() throws Exception {
        test(create("\"authType\": \"PASSWORD\", \"password\": \"ssh-pw\"", "/logs"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("not a regular file")));
    }

    // ---- host key: trust on first use ----

    String fingerprint(long id) {
        return jdbc.queryForObject("SELECT host_key_fingerprint FROM servers WHERE id = ?", String.class, id);
    }

    @Test
    void firstLoginPinsHostKeyAndLaterLoginsCheckIt() throws Exception {
        long id = create("\"authType\": \"PASSWORD\", \"password\": \"ssh-pw\"", "/logs/app.log");
        assertThat(fingerprint(id)).isNull();
        test(id).andExpect(jsonPath("$.ok").value(true));
        String pinned = fingerprint(id);
        assertThat(pinned).startsWith("SHA256:");
        test(id).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.message").value(containsString(pinned)));
        assertThat(fingerprint(id)).isEqualTo(pinned);
        mvc.perform(get("/api/servers")).andExpect(jsonPath("$[0].hostKeyFingerprint").value(pinned));
    }

    @Test
    void changedHostKeyFailsUntilReset() throws Exception {
        long id = create("\"authType\": \"PASSWORD\", \"password\": \"ssh-pw\"", "/logs/app.log");
        test(id).andExpect(jsonPath("$.ok").value(true));
        String real = fingerprint(id);
        jdbc.update("UPDATE servers SET host_key_fingerprint = 'SHA256:somethingElse' WHERE id = ?", id);

        test(id).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.logFileFound").value(false))
                .andExpect(jsonPath("$.message").value(containsString("Host key of " + sshd.getHost() + ":"
                        + sshd.getMappedPort(2222) + " changed: trusted SHA256:somethingElse, but the server presented "
                        + real)))
                .andExpect(jsonPath("$.message").value(containsString("reset the host key")));
        assertThat(fingerprint(id)).isEqualTo("SHA256:somethingElse"); // not silently replaced

        mvc.perform(post("/api/servers/" + id + "/reset-host-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.hostKeyFingerprint").value(org.hamcrest.Matchers.nullValue()));
        test(id).andExpect(jsonPath("$.ok").value(true));
        assertThat(fingerprint(id)).isEqualTo(real);
        mvc.perform(post("/api/servers/999999/reset-host-key")).andExpect(status().isNotFound());
    }

    @Test
    void failedLoginDoesNotPinTheKey() throws Exception {
        long id = create("\"authType\": \"PASSWORD\", \"password\": \"wrong\"", "/logs/app.log");
        test(id).andExpect(jsonPath("$.ok").value(false));
        assertThat(fingerprint(id)).isNull();
    }

    @Test
    void changingHostOrPortForgetsTheKey() throws Exception {
        long id = create("\"authType\": \"PASSWORD\", \"password\": \"ssh-pw\"", "/logs/app.log");
        test(id);
        String body = """
                { "name": "renamed", "host": "%s", "sshPort": %d, "username": "logboard", "authType": "PASSWORD",
                  "logPath": "/logs/app.log", "logPatternKey": "SPRING_BOOT_3" }""";
        mvc.perform(put("/api/servers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(sshd.getHost(), sshd.getMappedPort(2222))))
                .andExpect(jsonPath("$.hostKeyFingerprint").value(containsString("SHA256:"))); // same host: kept
        mvc.perform(put("/api/servers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("other-host.invalid", 22)))
                .andExpect(jsonPath("$.hostKeyFingerprint").value(org.hamcrest.Matchers.nullValue()));
    }
}
