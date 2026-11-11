package com.logboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logboard.common.LogboardProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots the full app against PostgreSQL 16 with the dev profile (CORS on). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"}) // test last: its values win over the dev samples
@Testcontainers
class ApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LogboardProperties props;

    @Test
    void flywayCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains(
                "servers", "llm_endpoints", "prompt_templates", "error_logs", "error_log_seq", "analyses");
    }

    @Test
    void configPropertiesBound() {
        assertThat(props.crop()).isEqualTo(new LogboardProperties.Crop(20, 10, 50, 2000));
        assertThat(props.storage().root()).isEqualTo("./data");
        assertThat(props.crypto().masterKey()).isEqualTo("test-master-key");
        assertThat(props.mcp().token()).isEqualTo("test-mcp-token-0123456789abcdef0123456789");
    }

    @Test
    void openApiDocsPublished() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }

    /** Optional response fields are sent as explicit null, so they must be nullable; always-present ones required. */
    @Test
    void openApiMarksNullableAndRequiredFields() throws Exception {
        String s = "$.components.schemas.";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath(s + "ApiError.required").value(org.hamcrest.Matchers.containsInAnyOrder("code", "message")))
                .andExpect(jsonPath(s + "ApiError.properties.details.type").value("object")) // optional, never null
                .andExpect(jsonPath(s + "ServerDto.properties.rotatedPattern.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath(s + "ServerDto.properties.id.type").value("integer"))
                .andExpect(jsonPath(s + "InstanceTestResult.properties.logFileSize.type")
                        .value(org.hamcrest.Matchers.contains("integer", "null")))
                .andExpect(jsonPath(s + "InstanceRef.required")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("id", "host", "sshPort", "logPath")))
                .andExpect(jsonPath(s + "FetchFailure.required").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "instanceId", "host", "sshPort", "logPath", "message")))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.fetchFailures.type").value("array"))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.instance['$ref']")
                        .value("#/components/schemas/InstanceRef"))
                .andExpect(jsonPath(s + "CandidateDto.properties.instanceId.type").value("integer"))
                .andExpect(jsonPath(s + "CandidateDto.properties.host.type").value("string"))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.exceptionClass.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.note.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.resolution.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.resolvedBy.enum")
                        .value(org.hamcrest.Matchers.contains("USER", "AGENT", null)))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.latestAnalysis.oneOf[0].$ref")
                        .value("#/components/schemas/AnalysisDto"))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.latestAnalysis.oneOf[1].type").value("null"))
                .andExpect(jsonPath(s + "AnalysisDto.properties.finishedAt.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath(s + "AnalysisDto.required")
                        .value(org.hamcrest.Matchers.hasItems("id", "status", "createdAt")))
                .andExpect(jsonPath(s + "LlmTestResult.required")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("ok", "message", "latencyMs")))
                .andExpect(jsonPath(s + "ErrorLogSummary.required")
                        .value(org.hamcrest.Matchers.hasItems("id", "status", "createdAt", "serverName", "serverTimezone",
                                "instance")))
                .andExpect(jsonPath(s + "ErrorLogDetail.required").value(org.hamcrest.Matchers.hasItems("serverTimezone",
                        "instance", "fetchFailures")))
                .andExpect(jsonPath(s + "ErrorLogSummary.properties.serverTimezone.type").value("string"))
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/terminate'].post").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/dev-required'].post").exists())
                .andExpect(jsonPath("$.paths['/api/llm-endpoints/{id}/test'].post").exists())
                .andExpect(jsonPath("$.paths['/api/prompts/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/analyses'].post").exists());
    }

    /** B9: nullable response fields are always present (sent as null) → required + nullable. Requests unchanged. */
    @Test
    void openApiMarksNullableResponseFieldsRequired() throws Exception {
        String s = "$.components.schemas.";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(s + "ErrorLogDetail.required").value(org.hamcrest.Matchers.hasItems(
                        "note", "resolution", "resolvedBy", "resolvedAt", "latestAnalysis", "exceptionClass",
                        "exceptionMessage")))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.note.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.latestAnalysis.oneOf[1].type").value("null"))
                .andExpect(jsonPath(s + "ErrorLogDetail.properties.latestAnalysis.description")
                        .value("Newest analysis (any status); `null` if none (always present)"))
                .andExpect(jsonPath(s + "ServerDto.required")
                        .value(org.hamcrest.Matchers.hasItems("instances", "rotatedPattern")))
                .andExpect(jsonPath(s + "InstanceDto.required").value(org.hamcrest.Matchers.hasItem("hostKeyFingerprint")))
                .andExpect(jsonPath(s + "InstanceDto.properties.hostKeyFingerprint.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath(s + "AnalysisDto.required")
                        .value(org.hamcrest.Matchers.hasItems("reply", "errorMessage", "finishedAt")))
                .andExpect(jsonPath(s + "CandidateDto.required").value(org.hamcrest.Matchers.hasItem("exceptionClass")))
                .andExpect(jsonPath(s + "InstanceTestResult.required").value(org.hamcrest.Matchers.hasItem("logFileSize")))
                .andExpect(jsonPath(s + "CropRequest.required").doesNotExist())
                // request DTOs unchanged: optional fields stay optional
                .andExpect(jsonPath(s + "ServerUpsert.required")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("rotatedPattern"))))
                // nested request DTO (ServerUpsert.instances) is a request schema too
                .andExpect(jsonPath(s + "InstanceUpsert.required").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "host", "logPath", "username", "authType")))
                .andExpect(jsonPath(s + "LlmEndpointUpsert.required")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("apiKey"))));
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiRouteReturnsApiError() throws Exception {
        mvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void corsAllowsViteDevServer() throws Exception {
        mvc.perform(options("/api/servers")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/servers")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
