package com.logboard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real HTTP + the MCP Java SDK client: list → get → terminate, tool errors, and the bearer token filter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class McpIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final Path ROOT = createTempDir();
    static final String TOKEN = "test-mcp-token-0123456789abcdef0123456789"; // application-test.yml
    static final String CROP = "2026-11-08T14:31:58.123+09:00 ERROR 1 --- [main] c.f.Order : failed\n"
            + "java.lang.NullPointerException: name is null\n\tat com.foo.Order.place(Order.java:42)\n";

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry r) {
        r.add("logboard.storage.root", ROOT::toString);
    }

    static Path createTempDir() {
        try {
            return Files.createTempDirectory("logboard-mcp");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    McpSyncClient client;
    long orderApi;
    long payApi;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM error_logs");
        jdbc.update("DELETE FROM servers");
        jdbc.update("DELETE FROM llm_endpoints");
        jdbc.update("DELETE FROM prompt_templates");
        orderApi = server("order-api", "10.0.0.5");
        payApi = server("pay-api", "10.0.0.6");
        client = client(TOKEN);
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    McpSyncClient client(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    long server(String name, String host) {
        long serverId = jdbc.queryForObject("""
                INSERT INTO servers (name, log_pattern_key, created_at, updated_at)
                VALUES (?, 'SPRING_BOOT_3', now(), now()) RETURNING id""", Long.class, name);
        jdbc.update("""
                INSERT INTO server_instances (server_id, position, host, ssh_port, log_path, username, auth_type,
                    created_at, updated_at)
                VALUES (?, 0, ?, 22, '/var/log/' || ? || '.log', 'u', 'PASSWORD', now(), now())""", serverId, host, name);
        return serverId;
    }

    void errorLog(String id, long serverId, String status, String note, String createdAt) throws Exception {
        jdbc.update("""
                INSERT INTO error_logs (id, server_id, instance_id, requested_time, window_min, raw_file_path,
                    crop_start_line, crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, raw_files,
                    fetch_failures, exception_class, exception_message, status, note, created_at, updated_at)
                VALUES (?, ?, (SELECT min(id) FROM server_instances WHERE server_id = ?), '2026-11-08T05:32:00Z', 5,
                    'raw/x.log', 1, 3, 1, 3, 3, '[]', '{}', '[]',
                    'java.lang.NullPointerException', 'name is null', ?, ?, ?::timestamptz, now())""",
                id, serverId, serverId, status, note, createdAt);
        Files.createDirectories(ROOT.resolve("errors"));
        Files.writeString(ROOT.resolve("errors").resolve(id + ".log"), CROP);
    }

    void analysis(String errorLogId, String status, String prompt, String reply, String createdAt) {
        Long ep = jdbc.queryForObject("""
                INSERT INTO llm_endpoints (name, base_url, model, timeout_sec, is_default)
                VALUES (?, 'http://llm', 'm', 120, false) RETURNING id""", Long.class, "ep-" + System.nanoTime());
        Long pr = jdbc.queryForObject("""
                INSERT INTO prompt_templates (name, content, is_default)
                VALUES (?, '{{errorLog}}', false) RETURNING id""", Long.class, "pr-" + System.nanoTime());
        jdbc.update("""
                INSERT INTO analyses (error_log_id, llm_endpoint_id, prompt_template_id, rendered_prompt, reply, status,
                    error_message, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?::timestamptz, now())""", errorLogId, ep, pr, prompt, reply, status, createdAt);
    }

    CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new CallToolRequest(tool, args));
    }

    static String text(CallToolResult r) {
        return ((TextContent) r.content().get(0)).text();
    }

    // ---- happy path ----

    @Test
    void listGetTerminateThenRestShowsTerminatedByAgent() throws Exception {
        errorLog("20261108-002", orderApi, "DEV_REQUIRED", "check Order.place", "2026-11-08T06:00:00Z");
        errorLog("20261108-001", orderApi, "DEV_REQUIRED", "older one", "2026-11-08T05:40:00Z");
        errorLog("20261108-003", payApi, "DEV_REQUIRED", "pay", "2026-11-08T07:00:00Z");
        errorLog("20261108-004", orderApi, "ANALYZED", null, "2026-11-08T08:00:00Z");
        analysis("20261108-002", "DONE", "PROMPT-1", "## Cause: null name", "2026-11-08T06:01:00Z");
        analysis("20261108-002", "FAILED", "PROMPT-2", null, "2026-11-08T06:05:00Z"); // newer but failed

        // list: only DEV_REQUIRED, oldest first, filters
        CallToolResult list = call("list_dev_required_errors", Map.of());
        assertThat(list.isError()).isFalse();
        String json = text(list);
        assertThat(JsonPath.<List<String>>read(json, "$[*].id")).containsExactly("20261108-001", "20261108-002", "20261108-003");
        assertThat(JsonPath.<String>read(json, "$[1].serverName")).isEqualTo("order-api");
        assertThat(JsonPath.<String>read(json, "$[1].note")).isEqualTo("check Order.place");
        assertThat(JsonPath.<String>read(json, "$[1].exceptionClass")).isEqualTo("java.lang.NullPointerException");
        assertThat(JsonPath.<String>read(json, "$[1].exceptionMessage")).isEqualTo("name is null");
        assertThat(JsonPath.<String>read(json, "$[1].createdAt")).isEqualTo("2026-11-08T06:00:00Z");
        assertThat(JsonPath.<List<String>>read(text(call("list_dev_required_errors",
                Map.of("serverName", "PAY-api"))), "$[*].id")).containsExactly("20261108-003");
        assertThat(JsonPath.<List<String>>read(text(call("list_dev_required_errors", Map.of("limit", 1))), "$[*].id"))
                .containsExactly("20261108-001");

        // get
        CallToolResult got = call("get_error_log", Map.of("errorId", "20261108-002"));
        assertThat(got.isError()).isFalse();
        String log = text(got);
        assertThat(JsonPath.<String>read(log, "$.id")).isEqualTo("20261108-002");
        assertThat(JsonPath.<String>read(log, "$.server.name")).isEqualTo("order-api");
        assertThat(JsonPath.<String>read(log, "$.server.host")).isEqualTo("10.0.0.5");
        assertThat(JsonPath.<String>read(log, "$.server.logPath")).isEqualTo("/var/log/order-api.log");
        assertThat(JsonPath.<String>read(log, "$.errorTime")).isEqualTo("2026-11-08T14:32:00+09:00"); // server tz
        assertThat(JsonPath.<String>read(log, "$.exceptionClass")).isEqualTo("java.lang.NullPointerException");
        assertThat(JsonPath.<String>read(log, "$.message")).isEqualTo("name is null");
        assertThat(JsonPath.<String>read(log, "$.status")).isEqualTo("DEV_REQUIRED");
        assertThat(JsonPath.<String>read(log, "$.userNote")).isEqualTo("check Order.place");
        assertThat(JsonPath.<String>read(log, "$.croppedLog")).isEqualTo(CROP);
        assertThat(JsonPath.<String>read(log, "$.latestAnalysis.prompt")).isEqualTo("PROMPT-1"); // latest DONE
        assertThat(JsonPath.<String>read(log, "$.latestAnalysis.reply")).isEqualTo("## Cause: null name");
        assertThat(JsonPath.<Object>read(text(call("get_error_log", Map.of("errorId", "20261108-004"))),
                "$.latestAnalysis")).isNull();

        // terminate
        CallToolResult done = call("terminate_error",
                Map.of("errorId", "20261108-002", "resolution", "Null check in Order.place, commit 1a2b3c"));
        assertThat(done.isError()).isFalse();
        assertThat(JsonPath.<String>read(text(done), "$.id")).isEqualTo("20261108-002");
        assertThat(JsonPath.<String>read(text(done), "$.status")).isEqualTo("TERMINATED");

        // verify over REST
        HttpResponse<String> rest = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/error-logs/20261108-002")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(rest.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(rest.body(), "$.status")).isEqualTo("TERMINATED");
        assertThat(JsonPath.<String>read(rest.body(), "$.resolvedBy")).isEqualTo("AGENT");
        assertThat(JsonPath.<String>read(rest.body(), "$.resolvedAt")).endsWith("Z");
        assertThat(JsonPath.<String>read(rest.body(), "$.resolution")).isEqualTo("Null check in Order.place, commit 1a2b3c");
        assertThat(JsonPath.<List<String>>read(text(call("list_dev_required_errors", Map.of())), "$[*].id"))
                .containsExactly("20261108-001", "20261108-003");
    }

    // ---- tool errors ----

    @Test
    void toolErrorsAreClear() throws Exception {
        errorLog("20261108-010", orderApi, "ANALYZED", null, "2026-11-08T06:00:00Z");
        errorLog("20261108-011", orderApi, "DEV_REQUIRED", null, "2026-11-08T06:00:00Z");

        assertToolError(call("get_error_log", Map.of("errorId", "20991231-999")),
                "No error log with id 20991231-999. Use list_dev_required_errors to find IDs.");
        assertToolError(call("get_error_log", Map.of("errorId", "abc")),
                "Invalid errorId 'abc': expected YYYYMMDD-NNN, e.g. 20261108-001.");
        assertToolError(call("terminate_error", Map.of("errorId", "20991231-999", "resolution", "x")),
                "No error log with id 20991231-999. Use list_dev_required_errors to find IDs.");
        assertToolError(call("terminate_error", Map.of("errorId", "20261108-010", "resolution", "x")),
                "Error log 20261108-010 is ANALYZED, not DEV_REQUIRED. terminate_error only closes errors waiting "
                        + "for a developer; the others are handled by the user in Logboard.");
        Map<String, Object> noResolution = new HashMap<>();
        noResolution.put("errorId", "20261108-011");
        CallToolResult missing = call("terminate_error", noResolution); // rejected by the SDK's schema check
        assertThat(missing.isError()).isTrue();
        assertThat(text(missing)).contains("resolution").contains("required");
        assertToolError(call("terminate_error", Map.of("errorId", "20261108-011", "resolution", "  ")),
                "resolution is required: describe the root cause and the fix, with the commit hash or PR link.");
        assertToolError(call("list_dev_required_errors", Map.of("limit", 0)), "limit must be between 1 and 100 (got 0).");

        assertThat(jdbc.queryForObject("SELECT status FROM error_logs WHERE id = '20261108-010'", String.class))
                .isEqualTo("ANALYZED");
        assertThat(jdbc.queryForObject("SELECT status FROM error_logs WHERE id = '20261108-011'", String.class))
                .isEqualTo("DEV_REQUIRED");
    }

    static void assertToolError(CallToolResult r, String message) {
        assertThat(r.isError()).isTrue();
        assertThat(text(r)).isEqualTo(message);
    }

    // ---- server info & schemas ----

    @Test
    void serverNameAndToolSchemas() {
        assertThat(client.getServerInfo().name()).isEqualTo("logboard");
        Map<String, Tool> tools = new HashMap<>();
        client.listTools().tools().forEach(t -> tools.put(t.name(), t));
        assertThat(tools).containsOnlyKeys("list_dev_required_errors", "get_error_log", "terminate_error");
        assertThat(tools.get("terminate_error").description()).contains("DEV_REQUIRED").contains("resolution");
        assertThat(tools.get("get_error_log").description()).contains("YYYYMMDD-NNN");

        assertThat(props(tools.get("list_dev_required_errors"))).containsOnlyKeys("serverName", "limit");
        assertThat(required(tools.get("list_dev_required_errors"))).isEmpty();
        assertThat(props(tools.get("get_error_log"))).containsOnlyKeys("errorId");
        assertThat(required(tools.get("get_error_log"))).containsExactly("errorId");
        assertThat(props(tools.get("terminate_error"))).containsOnlyKeys("errorId", "resolution");
        assertThat(required(tools.get("terminate_error"))).containsExactlyInAnyOrder("errorId", "resolution");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> props(Tool t) {
        return (Map<String, Object>) t.inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    static List<String> required(Tool t) {
        Object r = t.inputSchema().get("required");
        return r == null ? List.of() : (List<String>) r;
    }

    // ---- auth ----

    HttpResponse<String> rawPost(String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                         "capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        for (String auth : new String[] {null, "Bearer wrong", "Bearer " + TOKEN + "x", "Basic " + TOKEN, "Bearer "}) {
            HttpResponse<String> res = rawPost(auth);
            assertThat(res.statusCode()).as(String.valueOf(auth)).isEqualTo(401);
            assertThat(res.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"logboard-mcp\"");
            assertThat(res.body()).contains("\"code\":\"UNAUTHORIZED\"");
        }
        assertThat(rawPost("Bearer " + TOKEN).statusCode()).isEqualTo(200);
        assertThat(rawPost("bearer " + TOKEN).statusCode()).isEqualTo(200); // scheme is case-insensitive

        McpSyncClient bad = client("wrong");
        assertThatThrownBy(bad::initialize).isInstanceOf(RuntimeException.class);
    }

    @Test
    void restApiNeedsNoMcpToken() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/servers")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
    }
}
