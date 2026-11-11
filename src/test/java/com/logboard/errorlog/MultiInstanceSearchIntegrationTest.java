package com.logboard.errorlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.logboard.server.AuthType;
import com.logboard.server.InstanceUpsert;
import com.logboard.server.ServerDto;
import com.logboard.server.ServerService;
import com.logboard.server.ServerUpsert;
import java.net.ServerSocket;
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

/**
 * Multi-instance search against two real OpenSSH servers with different logins: A = user alice with a password,
 * B = user bob with an ed25519 key only. Each holds the same log files; a server picks one file per instance.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MultiInstanceSearchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static GenericContainer<?> sshdA = sshd("alice", true);

    @Container
    static GenericContainer<?> sshdB = sshd("bob", false);

    static final Path ROOT = createTempDir();
    static final String T = "2026-11-08T14:32:00+09:00";
    static String bobKey;

    static GenericContainer<?> sshd(String user, boolean password) {
        return new GenericContainer<>("lscr.io/linuxserver/openssh-server:latest")
                .withEnv("USER_NAME", user)
                .withEnv("PASSWORD_ACCESS", String.valueOf(password))
                .withEnv("USER_PASSWORD", "alice-pw")
                .withExposedPorts(2222)
                .waitingFor(Wait.forLogMessage(".*\\[ls.io-init\\] done.*", 1));
    }

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry r) {
        r.add("logboard.storage.root", ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ServerService servers;

    @BeforeAll
    static void prepare() throws Exception {
        for (GenericContainer<?> c : List.of(sshdA, sshdB)) {
            for (String f : List.of("single-npe.log", "multiple-exceptions.log", "no-exception-in-window.log")) {
                c.copyFileToContainer(Transferable.of(Files.readAllBytes(Path.of("src/test/resources/logs", f))),
                        "/logs/" + f);
            }
        }
        exec(sshdA, "ssh-keygen -q -t ed25519 -N '' -f /tmp/bob");
        bobKey = exec(sshdA, "cat /tmp/bob");
        String pub = exec(sshdA, "cat /tmp/bob.pub").strip();
        exec(sshdB, "mkdir -p /config/.ssh && echo '" + pub + "' >> /config/.ssh/authorized_keys"
                + " && chown -R bob /config/.ssh && chmod 700 /config/.ssh && chmod 600 /config/.ssh/authorized_keys");
    }

    static String exec(GenericContainer<?> c, String cmd) throws Exception {
        var r = c.execInContainer("sh", "-c", cmd);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException(cmd + " failed: " + r.getStderr());
        }
        return r.getStdout();
    }

    static Path createTempDir() {
        try {
            return Files.createTempDirectory("logboard-multi");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM error_logs");
        jdbc.update("DELETE FROM servers");
    }

    static InstanceUpsert a(String file) {
        return new InstanceUpsert(null, sshdA.getHost(), sshdA.getMappedPort(2222), "/logs/" + file, "alice",
                AuthType.PASSWORD, "alice-pw", null);
    }

    static InstanceUpsert b(String file) {
        return new InstanceUpsert(null, sshdB.getHost(), sshdB.getMappedPort(2222), "/logs/" + file, "bob",
                AuthType.KEY, null, bobKey);
    }

    /** An instance nobody listens on ("node down"). */
    static InstanceUpsert down() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        return new InstanceUpsert(null, "127.0.0.1", port, "/logs/app.log", "x", AuthType.PASSWORD, "pw", null);
    }

    ServerDto server(InstanceUpsert... instances) {
        return servers.create(new ServerUpsert("multi-" + System.nanoTime(), List.of(instances), null, "SPRING_BOOT_3",
                null, "Asia/Seoul", "UTF-8"));
    }

    static long inst(ServerDto s, int n) {
        return s.instances().get(n).id();
    }

    ResultActions search(ServerDto s) throws Exception {
        return mvc.perform(post("/api/error-logs/search").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"serverId\": %d, \"errorTime\": \"%s\" }".formatted(s.id(), T)));
    }

    static String id(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id");
    }

    ResultActions patchCrop(String id, String json) throws Exception {
        return mvc.perform(patch("/api/error-logs/" + id + "/crop").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void exceptionOnlyOnInstanceBIsFoundThere() throws Exception {
        ServerDto s = server(a("no-exception-in-window.log"), b("single-npe.log"));
        String id = id(search(s)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instance.id").value(inst(s, 1)))
                .andExpect(jsonPath("$.instance.host").value(sshdB.getHost()))
                .andExpect(jsonPath("$.instance.sshPort").value(sshdB.getMappedPort(2222)))
                .andExpect(jsonPath("$.instance.logPath").value("/logs/single-npe.log"))
                .andExpect(jsonPath("$.exceptionClass").value("java.lang.NullPointerException"))
                .andExpect(jsonPath("$.crop.anchorLine").value(26))
                .andExpect(jsonPath("$.crop.totalLines").value(45))
                .andExpect(jsonPath("$.candidates", hasSize(1)))
                .andExpect(jsonPath("$.candidates[0].instanceId").value(inst(s, 1)))
                .andExpect(jsonPath("$.fetchFailures", hasSize(0))));

        // one raw copy per fetched instance, each under its own directory
        for (int n = 0; n < 2; n++) {
            try (Stream<Path> files = Files.list(ROOT.resolve("raw/" + s.id() + "/" + inst(s, n)))) {
                assertThat(files.filter(f -> !f.toString().endsWith(".meta.json"))).hasSize(1);
            }
        }
        mvc.perform(get("/api/error-logs?serverId=" + s.id()))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].instance.id").value(inst(s, 1)));
    }

    @Test
    void candidatesOfBothInstancesArePooledAndSwitchMovesTheCrop() throws Exception {
        ServerDto s = server(a("multiple-exceptions.log"), b("single-npe.log"));
        long ia = inst(s, 0);
        long ib = inst(s, 1);
        // A: 14:30:10 (6), 14:31:58.000 (11), 14:34:00 (16); B: 14:31:58.123 (26) is closest to 14:32:00
        String id = id(search(s)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidates[*].instanceId").value(contains((int) ia, (int) ia, (int) ia, (int) ib)))
                .andExpect(jsonPath("$.candidates[*].anchorLine").value(contains(6, 11, 16, 26)))
                .andExpect(jsonPath("$.candidates[0].host").value(sshdA.getHost()))
                .andExpect(jsonPath("$.instance.id").value(ib))
                .andExpect(jsonPath("$.crop.anchorLine").value(26)));

        // B → A: crop moves to A's raw copy
        patchCrop(id, "{ \"anchorLine\": 16, \"instanceId\": " + ia + " }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(ia))
                .andExpect(jsonPath("$.instance.logPath").value("/logs/multiple-exceptions.log"))
                .andExpect(jsonPath("$.crop.anchorLine").value(16))
                .andExpect(jsonPath("$.crop.totalLines").value(18))
                .andExpect(jsonPath("$.crop.lines[15].text").value(containsString("Payment gateway")))
                .andExpect(jsonPath("$.exceptionClass").value(org.hamcrest.Matchers.nullValue()));
        // anchorLine without instanceId = current instance (A); 26 is only a candidate of B
        patchCrop(id, "{ \"anchorLine\": 26 }").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.anchorLine").exists());
        patchCrop(id, "{ \"anchorLine\": 11 }").andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(ia)).andExpect(jsonPath("$.crop.anchorLine").value(11));
        // expand stays inside A (18 lines)
        patchCrop(id, "{ \"expandAfter\": 500 }").andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(ia))
                .andExpect(jsonPath("$.crop.endLine").value(18));

        // A → B
        patchCrop(id, "{ \"anchorLine\": 26, \"instanceId\": " + ib + " }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(ib))
                .andExpect(jsonPath("$.crop.totalLines").value(45))
                .andExpect(jsonPath("$.crop.startLine").value(6))
                .andExpect(jsonPath("$.crop.endLine").value(40))
                .andExpect(jsonPath("$.exceptionClass").value("java.lang.NullPointerException"));
        patchCrop(id, "{ \"expandBefore\": 50 }").andExpect(jsonPath("$.crop.startLine").value(1));
        mvc.perform(get("/api/error-logs/" + id))
                .andExpect(jsonPath("$.instance.id").value(ib))
                .andExpect(jsonPath("$.crop.lines", hasSize(40)))
                .andExpect(jsonPath("$.crop.lines[25].kind").value("ANCHOR"));
        assertThat(Files.readAllLines(ROOT.resolve("errors").resolve(id + ".log"))).hasSize(40);
    }

    @Test
    void tieGoesToTheLowerPosition() throws Exception {
        ServerDto s = server(a("single-npe.log"), b("single-npe.log"));
        search(s).andExpect(jsonPath("$.instance.id").value(inst(s, 0)));

        ServerUpsert reordered = new ServerUpsert(s.name(), List.of(withId(b("single-npe.log"), inst(s, 1)),
                withId(a("single-npe.log"), inst(s, 0))), null, "SPRING_BOOT_3", null, "Asia/Seoul", "UTF-8");
        servers.update(s.id(), reordered);
        search(s).andExpect(jsonPath("$.instance.id").value(inst(s, 1)))
                .andExpect(jsonPath("$.candidates[*].instanceId").value(contains((int) inst(s, 1), (int) inst(s, 0))));
    }

    static InstanceUpsert withId(InstanceUpsert i, long id) {
        return new InstanceUpsert(id, i.host(), i.sshPort(), i.logPath(), i.username(), i.authType(), i.password(),
                i.privateKey());
    }

    @Test
    void oneInstanceDownStillCreatesTheErrorLogWithAFetchFailure() throws Exception {
        ServerDto s = server(a("single-npe.log"), down());
        String id = id(search(s)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instance.id").value(inst(s, 0)))
                .andExpect(jsonPath("$.fetchFailures", hasSize(1)))
                .andExpect(jsonPath("$.fetchFailures[0].instanceId").value(inst(s, 1)))
                .andExpect(jsonPath("$.fetchFailures[0].host").value("127.0.0.1"))
                .andExpect(jsonPath("$.fetchFailures[0].sshPort").value(s.instances().get(1).sshPort()))
                .andExpect(jsonPath("$.fetchFailures[0].logPath").value("/logs/app.log"))
                .andExpect(jsonPath("$.fetchFailures[0].message").value(containsString("Connection refused by 127.0.0.1"))));
        mvc.perform(get("/api/error-logs/" + id)).andExpect(jsonPath("$.fetchFailures", hasSize(1)));
    }

    @Test
    void allInstancesFailedIs502WithEveryFailure() throws Exception {
        ServerDto s = server(down(), a("nope.log"));
        search(s)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("FETCH_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("Connection refused")))
                .andExpect(jsonPath("$.message").value(containsString("; " + sshdA.getHost() + ":"
                        + sshdA.getMappedPort(2222) + ": Connected, but log file not found: /logs/nope.log")))
                .andExpect(jsonPath("$.details.failures", hasSize(2)))
                .andExpect(jsonPath("$.details.failures[*].instanceId").value(contains((int) inst(s, 0), (int) inst(s, 1))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM error_logs", Integer.class)).isZero();
    }

    @Test
    void noExceptionAnywhereIs404WithSearchedAndFailedInstances() throws Exception {
        ServerDto s = server(a("no-exception-in-window.log"), b("no-exception-in-window.log"), down());
        search(s)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_EXCEPTION_FOUND"))
                .andExpect(jsonPath("$.details.searchedInstances[*].id").value(contains((int) inst(s, 0), (int) inst(s, 1))))
                .andExpect(jsonPath("$.details.searchedInstances[1].host").value(sshdB.getHost()))
                .andExpect(jsonPath("$.details.searchedInstances[1].logPath").value("/logs/no-exception-in-window.log"))
                .andExpect(jsonPath("$.details.failures", hasSize(1)))
                .andExpect(jsonPath("$.details.failures[0].instanceId").value(inst(s, 2)));
        for (int n = 0; n < 2; n++) {
            try (Stream<Path> files = Files.list(ROOT.resolve("raw/" + s.id() + "/" + inst(s, n)))) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void removedInstanceIsSoftDeletedAndItsErrorLogKeepsWorking() throws Exception {
        ServerDto s = server(a("multiple-exceptions.log"), b("single-npe.log"));
        long ia = inst(s, 0);
        long ib = inst(s, 1);
        String id = id(search(s).andExpect(jsonPath("$.instance.id").value(ib)));

        servers.update(s.id(), new ServerUpsert(s.name(), List.of(withId(a("multiple-exceptions.log"), ia)), null,
                "SPRING_BOOT_3", null, "Asia/Seoul", "UTF-8"));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM server_instances WHERE id = ?", Boolean.class,
                ib)).isTrue();

        mvc.perform(get("/api/error-logs/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(ib))
                .andExpect(jsonPath("$.instance.host").value(sshdB.getHost()))
                .andExpect(jsonPath("$.candidates[3].host").value(sshdB.getHost()));
        patchCrop(id, "{ \"anchorLine\": 6, \"instanceId\": " + ia + " }").andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(ia));
        patchCrop(id, "{ \"anchorLine\": 26, \"instanceId\": " + ib + " }").andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(ib));
        // a new search reads only the active instance
        search(s).andExpect(status().isCreated()).andExpect(jsonPath("$.instance.id").value(ia))
                .andExpect(jsonPath("$.candidates", hasSize(3)));
    }
}
