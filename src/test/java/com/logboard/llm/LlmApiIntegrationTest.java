package com.logboard.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.logboard.common.SecretCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** LLM endpoints, prompts, the connection test and analyses, against WireMock as the LLM. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class LlmApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final WireMockServer llm = new WireMockServer(options().dynamicPort());
    static final Path ROOT = createTempDir();
    static final String PATH = "/v1/chat/completions";
    static final String CROP = "2026-11-08T14:31:58.123+09:00 ERROR 1 --- [main] c.f.A : boom\njava.lang.NullPointerException: x\n";

    static {
        llm.start();
    }

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry r) {
        r.add("logboard.storage.root", ROOT::toString);
    }

    @AfterAll
    static void stopLlm() {
        llm.stop();
    }

    static Path createTempDir() {
        try {
            return Files.createTempDirectory("logboard-llm");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretCipher cipher;
    @Autowired AnalysisRunner runner;

    long serverId;

    @BeforeEach
    void clean() {
        llm.resetAll();
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM error_logs");
        jdbc.update("DELETE FROM servers");
        jdbc.update("DELETE FROM llm_endpoints");
        jdbc.update("DELETE FROM prompt_templates");
        serverId = jdbc.queryForObject("""
                INSERT INTO servers (name, host, username, auth_type, log_path, log_pattern_key, created_at, updated_at)
                VALUES ('order-api', 'h', 'u', 'PASSWORD', '/a.log', 'SPRING_BOOT_3', now(), now()) RETURNING id""",
                Long.class);
    }

    String base() {
        return "http://localhost:" + llm.port();
    }

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String json)
            throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    long endpoint(String name, String extra) throws Exception {
        return id(send(post("/api/llm-endpoints"), """
                { "name": "%s", "baseUrl": "%s", "model": "qwen" %s }""".formatted(name, base(), extra))
                .andExpect(status().isCreated()));
    }

    long prompt(String name, String content) throws Exception {
        return id(send(post("/api/prompts"), "{ \"name\": \"%s\", \"content\": \"%s\" }".formatted(name, content))
                .andExpect(status().isCreated()));
    }

    String errorLog(String id, String status) throws Exception {
        jdbc.update("""
                INSERT INTO error_logs (id, server_id, requested_time, window_min, raw_file_path, crop_start_line,
                    crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, exception_class, status,
                    created_at, updated_at)
                VALUES (?, ?, '2026-11-08T14:32:00+09:00', 5, 'raw/x.log', 1, 2, 1, 2, 2, '[]',
                    'java.lang.NullPointerException', ?, now(), now())""", id, serverId, status);
        Files.createDirectories(ROOT.resolve("errors"));
        Files.writeString(ROOT.resolve("errors").resolve(id + ".log"), CROP);
        return id;
    }

    String errorStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM error_logs WHERE id = ?", String.class, id);
    }

    ResultActions analyze(String errorLogId, long endpointId, long promptId) throws Exception {
        return send(post("/api/error-logs/" + errorLogId + "/analyses"),
                "{ \"llmEndpointId\": %d, \"promptTemplateId\": %d }".formatted(endpointId, promptId));
    }

    String awaitFinished(long analysisId) {
        await().atMost(Duration.ofSeconds(15)).until(() ->
                !"PENDING".equals(jdbc.queryForObject("SELECT status FROM analyses WHERE id = ?", String.class, analysisId)));
        return jdbc.queryForObject("SELECT status FROM analyses WHERE id = ?", String.class, analysisId);
    }

    static String reply(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    // ---- LLM endpoints ----

    @Test
    void endpointCrudWithWriteOnlyApiKey() throws Exception {
        String created = send(post("/api/llm-endpoints"), """
                { "name": "vllm", "baseUrl": "%s/", "model": "qwen", "apiKey": "sk-SECRET" }""".formatted(base()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseUrl").value(base())) // trailing '/' removed
                .andExpect(jsonPath("$.timeoutSec").value(120))
                .andExpect(jsonPath("$.isDefault").value(false))
                .andExpect(jsonPath("$.hasSecret").value(true))
                .andExpect(jsonPath("$.createdAt").value(containsString("Z")))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("sk-SECRET").doesNotContain("\"default\"");
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        String enc = jdbc.queryForObject("SELECT api_key_enc FROM llm_endpoints WHERE id = ?", String.class, id);
        assertThat(cipher.decrypt(enc)).isEqualTo("sk-SECRET");

        String upd = "{ \"name\": \"vllm2\", \"baseUrl\": \"%s\", \"model\": \"llama\", \"timeoutSec\": 30 %s }";
        send(put("/api/llm-endpoints/" + id), upd.formatted(base(), ", \"apiKey\": \"  \""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("vllm2"))
                .andExpect(jsonPath("$.model").value("llama"))
                .andExpect(jsonPath("$.timeoutSec").value(30))
                .andExpect(jsonPath("$.hasSecret").value(true)); // blank keeps
        send(put("/api/llm-endpoints/" + id), upd.formatted(base(), ", \"apiKey\": \"k\", \"clearApiKey\": true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.clearApiKey").exists());
        send(put("/api/llm-endpoints/" + id), upd.formatted(base(), ", \"clearApiKey\": true"))
                .andExpect(jsonPath("$.hasSecret").value(false));
        assertThat(jdbc.queryForObject("SELECT api_key_enc FROM llm_endpoints WHERE id = ?", String.class, id)).isNull();

        mvc.perform(get("/api/llm-endpoints")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("vllm2"));
        mvc.perform(delete("/api/llm-endpoints/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/llm-endpoints/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void endpointValidationAndDuplicates() throws Exception {
        endpoint("vllm", "");
        long other = endpoint("ollama", "");
        send(post("/api/llm-endpoints"), "{ \"name\": \"vllm\", \"baseUrl\": \"%s\", \"model\": \"m\" }".formatted(base()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NAME"))
                .andExpect(jsonPath("$.details.field").value("name"));
        send(put("/api/llm-endpoints/" + other), "{ \"name\": \"vllm\", \"baseUrl\": \"%s\", \"model\": \"m\" }".formatted(base()))
                .andExpect(status().isConflict());
        send(post("/api/llm-endpoints"), "{ \"name\": \"a\", \"baseUrl\": \"https://api.openai.com/v1\", \"model\": \"m\" }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.baseUrl").value(containsString("Leave out /v1")));
        send(post("/api/llm-endpoints"), "{ \"name\": \"a\", \"baseUrl\": \"ftp://x\", \"model\": \"m\" }")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.fields.baseUrl").exists());
        send(post("/api/llm-endpoints"), "{ \"timeoutSec\": 0 }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.name").exists())
                .andExpect(jsonPath("$.details.fields.baseUrl").exists())
                .andExpect(jsonPath("$.details.fields.model").exists())
                .andExpect(jsonPath("$.details.fields.timeoutSec").exists());
    }

    @Test
    void onlyOneDefaultEndpointAndPrompt() throws Exception {
        long a = endpoint("a", ", \"isDefault\": true");
        long b = endpoint("b", ", \"isDefault\": true");
        mvc.perform(get("/api/llm-endpoints")).andExpect(jsonPath("$[*].isDefault").value(contains(false, true)));
        send(put("/api/llm-endpoints/" + a), "{ \"name\": \"a\", \"baseUrl\": \"%s\", \"model\": \"m\", \"isDefault\": true }"
                .formatted(base())).andExpect(status().isOk());
        mvc.perform(get("/api/llm-endpoints")).andExpect(jsonPath("$[*].isDefault").value(contains(true, false)));
        assertThat(b).isNotEqualTo(a);

        long p = id(send(post("/api/prompts"), "{ \"name\": \"p\", \"content\": \"{{errorLog}}\", \"isDefault\": true }"));
        send(post("/api/prompts"), "{ \"name\": \"q\", \"content\": \"{{errorLog}}\", \"isDefault\": true }");
        mvc.perform(get("/api/prompts")).andExpect(jsonPath("$[*].isDefault").value(contains(false, true)));
        send(put("/api/prompts/" + p), "{ \"name\": \"p\", \"content\": \"{{errorLog}}\", \"isDefault\": true }");
        mvc.perform(get("/api/prompts")).andExpect(jsonPath("$[*].isDefault").value(contains(true, false)));
    }

    @Test
    void endpointConnectionTest() throws Exception {
        long id = endpoint("vllm", ", \"apiKey\": \"sk-1\"");
        llm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PATH).willReturn(okJson(reply("OK"))));
        mvc.perform(post("/api/llm-endpoints/" + id + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("Model qwen replied: OK"))
                .andExpect(jsonPath("$.latencyMs").isNumber());
        llm.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("Authorization",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer sk-1")));

        llm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PATH).willReturn(aResponse().withStatus(503)));
        mvc.perform(post("/api/llm-endpoints/" + id + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("HTTP 503")));
        mvc.perform(post("/api/llm-endpoints/999999/test")).andExpect(status().isNotFound());
    }

    // ---- prompts ----

    @Test
    void promptCrudAndPlaceholderRule() throws Exception {
        send(post("/api/prompts"), "{ \"name\": \"p\", \"content\": \"Explain this\" }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.content").value(containsString("{{errorLog}}")));
        long id = id(send(post("/api/prompts"), "{ \"name\": \"triage\", \"content\": \"Explain:\\n{{errorLog}}\" }")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false))
                .andExpect(jsonPath("$.content").value("Explain:\n{{errorLog}}")));
        send(put("/api/prompts/" + id), "{ \"name\": \"triage\", \"content\": \"no placeholder\" }")
                .andExpect(status().isBadRequest());
        send(put("/api/prompts/" + id), "{ \"name\": \"triage2\", \"content\": \"{{errorLog}} on {{serverName}}\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("triage2"));
        prompt("other", "{{errorLog}}");
        send(post("/api/prompts"), "{ \"name\": \"other\", \"content\": \"{{errorLog}}\" }")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));
        mvc.perform(get("/api/prompts")).andExpect(jsonPath("$[*].name").value(contains("other", "triage2")));
        mvc.perform(delete("/api/prompts/" + id)).andExpect(status().isNoContent());
    }

    // ---- analyses ----

    @Test
    void analysisDoneMovesErrorLogToAnalyzed() throws Exception {
        String log = errorLog("20261108-001", "CONFIRMED");
        assertThat(mvc.perform(get("/api/error-logs/" + log)).andReturn().getResponse().getContentAsString())
                .contains("\"latestAnalysis\":null");
        long ep = endpoint("vllm", "");
        long pr = prompt("triage", "Server {{serverName}} at {{errorTime}}: {{exceptionClass}}\\n{{errorLog}}");
        llm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PATH).willReturn(okJson(reply("## Cause"))));

        long analysis = id(analyze(log, ep, pr)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.errorLogId").value(log))
                .andExpect(jsonPath("$.llmEndpointId").value(ep))
                .andExpect(jsonPath("$.llmEndpointName").value("vllm"))
                .andExpect(jsonPath("$.promptTemplateId").value(pr))
                .andExpect(jsonPath("$.promptTemplateName").value("triage"))
                .andExpect(jsonPath("$.finishedAt").value(nullValue())));
        assertThat(awaitFinished(analysis)).isEqualTo("DONE");

        mvc.perform(get("/api/analyses/" + analysis))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.reply").value("## Cause"))
                .andExpect(jsonPath("$.errorMessage").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").isString());
        assertThat(errorStatus(log)).isEqualTo("ANALYZED");
        llm.verify(postRequestedFor(urlEqualTo(PATH))
                .withoutHeader("Authorization")
                .withRequestBody(matchingJsonPath("$.messages[0].content", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("Server order-api at 2026-11-08T14:32:00+09:00: java.lang.NullPointerException\n" + CROP))));
        mvc.perform(get("/api/error-logs/" + log))
                .andExpect(jsonPath("$.status").value("ANALYZED"))
                .andExpect(jsonPath("$.latestAnalysis.id").value(analysis))
                .andExpect(jsonPath("$.latestAnalysis.reply").value("## Cause"))
                .andExpect(jsonPath("$.latestAnalysis.llmEndpointId").value(ep))
                .andExpect(jsonPath("$.latestAnalysis.promptTemplateId").value(pr));
        mvc.perform(get("/api/error-logs/" + log + "/analyses"))
                .andExpect(jsonPath("$[0].llmEndpointId").value(ep))
                .andExpect(jsonPath("$[0].promptTemplateId").value(pr));

        // re-analyze from ANALYZED is allowed; history is newest first
        long second = id(analyze(log, ep, pr).andExpect(status().isAccepted()));
        awaitFinished(second);
        mvc.perform(get("/api/error-logs/" + log + "/analyses"))
                .andExpect(jsonPath("$[*].id").value(contains((int) second, (int) analysis)));
        mvc.perform(get("/api/error-logs/" + log)).andExpect(jsonPath("$.latestAnalysis.id").value(second));

        // referenced endpoint/prompt cannot be deleted
        mvc.perform(delete("/api/llm-endpoints/" + ep)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IN_USE"));
        mvc.perform(delete("/api/prompts/" + pr)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IN_USE"));
    }

    @Test
    void failedAnalysisKeepsConfirmed() throws Exception {
        String log = errorLog("20261108-002", "CONFIRMED");
        long ep = endpoint("vllm", ", \"timeoutSec\": 1");
        long pr = prompt("triage", "{{errorLog}}");

        llm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PATH).willReturn(aResponse().withStatus(500).withBody("boom")));
        long a1 = id(analyze(log, ep, pr));
        assertThat(awaitFinished(a1)).isEqualTo("FAILED");
        mvc.perform(get("/api/analyses/" + a1))
                .andExpect(jsonPath("$.errorMessage").value("LLM endpoint returned HTTP 500: boom"))
                .andExpect(jsonPath("$.reply").value(nullValue()));

        llm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PATH).willReturn(okJson(reply("late")).withFixedDelay(3000)));
        long a2 = id(analyze(log, ep, pr));
        assertThat(awaitFinished(a2)).isEqualTo("FAILED");
        mvc.perform(get("/api/analyses/" + a2)).andExpect(jsonPath("$.errorMessage").value(containsString("No response within 1s")));

        llm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PATH).willReturn(okJson("{\"nope\":1}")));
        long a3 = id(analyze(log, ep, pr));
        assertThat(awaitFinished(a3)).isEqualTo("FAILED");
        mvc.perform(get("/api/analyses/" + a3)).andExpect(jsonPath("$.errorMessage").value(containsString("not a chat completion")));

        assertThat(errorStatus(log)).isEqualTo("CONFIRMED");
    }

    @Test
    void analysisIsGuardedByTheStatusMachine() throws Exception {
        long ep = endpoint("vllm", "");
        long pr = prompt("triage", "{{errorLog}}");
        String[] statuses = {"CROPPED", "DEV_REQUIRED", "TERMINATED", "DISCARDED"};
        for (int i = 0; i < statuses.length; i++) {
            String s = statuses[i];
            String log = errorLog("20261108-10" + i, s);
            analyze(log, ep, pr)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                    .andExpect(jsonPath("$.details.status").value(s))
                    .andExpect(jsonPath("$.details.action").value("START_ANALYSIS"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analyses", Integer.class)).isZero();
        analyze("20000101-001", ep, pr).andExpect(status().isNotFound());
        analyze(errorLog("20261108-003", "CONFIRMED"), 999999, pr).andExpect(status().isNotFound());
        send(post("/api/error-logs/20261108-003/analyses"), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.llmEndpointId").exists());
        mvc.perform(get("/api/analyses/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/error-logs/20000101-001/analyses")).andExpect(status().isNotFound());
    }

    @Test
    void doneAfterTerminateDoesNotChangeStatus() throws Exception {
        String log = errorLog("20261108-004", "CONFIRMED");
        long ep = endpoint("vllm", "");
        long pr = prompt("triage", "{{errorLog}}");
        llm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(PATH).willReturn(okJson(reply("late")).withFixedDelay(1500)));
        long a = id(analyze(log, ep, pr));
        jdbc.update("UPDATE error_logs SET status = 'TERMINATED' WHERE id = ?", log); // user terminated meanwhile
        assertThat(awaitFinished(a)).isEqualTo("DONE");
        assertThat(errorStatus(log)).isEqualTo("TERMINATED");
    }

    @Test
    void pendingAnalysesFromAPreviousRunAreFailedOnStartup() throws Exception {
        String log = errorLog("20261108-005", "CONFIRMED");
        long ep = endpoint("vllm", "");
        long pr = prompt("triage", "{{errorLog}}");
        long stuck = jdbc.queryForObject("""
                INSERT INTO analyses (error_log_id, llm_endpoint_id, prompt_template_id, rendered_prompt, status, created_at)
                VALUES (?, ?, ?, 'p', 'PENDING', now()) RETURNING id""", Long.class, log, ep, pr);
        assertThat(runner.failInterrupted()).isEqualTo(1);
        mvc.perform(get("/api/analyses/" + stuck))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value(AnalysisRunner.INTERRUPTED))
                .andExpect(jsonPath("$.finishedAt").isString());
    }

    // ---- OpenAPI ----

    @Test
    void openApiDescribesLlmEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/llm-endpoints'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/llm-endpoints/{id}'].delete.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/llm-endpoints/{id}/test'].post").exists())
                .andExpect(jsonPath("$.paths['/api/prompts'].get").exists())
                .andExpect(jsonPath("$.paths['/api/prompts/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/analyses'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/analyses'].get").exists())
                .andExpect(jsonPath("$.paths['/api/analyses/{id}'].get").exists())
                .andExpect(jsonPath("$.components.schemas.LlmEndpointDto.properties.isDefault").exists())
                .andExpect(jsonPath("$.components.schemas.LlmEndpointDto.properties.default").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.LlmEndpointDto.properties.apiKey").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.LlmEndpointUpsert.properties.clearApiKey").exists())
                .andExpect(jsonPath("$.components.schemas.PromptTemplateDto.properties.isDefault").exists())
                .andExpect(jsonPath("$.components.schemas.LlmTestResult.properties.latencyMs").exists())
                .andExpect(jsonPath("$.components.schemas.AnalysisDto.properties.status.enum")
                        .value(contains("PENDING", "DONE", "FAILED")))
                .andExpect(jsonPath("$.components.schemas.ErrorLogDetail.properties.latestAnalysis").exists())
                .andExpect(jsonPath("$.components.schemas.AnalysisDto.required")
                        .value(org.hamcrest.Matchers.hasItems("llmEndpointId", "promptTemplateId")))
                .andExpect(jsonPath("$.components.schemas.AnalysisDto.properties.llmEndpointId.format").value("int64"))
                .andExpect(jsonPath("$.components.schemas.AnalysisDto.properties.promptTemplateId.format").value("int64"));
    }
}
