package com.logboard.errorlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logboard.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
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

/** B6: terminate and dev-required from every status, validation, AGENT terminate, OpenAPI. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ResolutionApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final Path ROOT = createTempDir();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry r) {
        r.add("logboard.storage.root", ROOT::toString);
    }

    static Path createTempDir() {
        try {
            return Files.createTempDirectory("logboard-resolution");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ErrorLogService service;

    long serverId;
    int seq;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM error_logs");
        jdbc.update("DELETE FROM servers");
        serverId = jdbc.queryForObject("""
                INSERT INTO servers (name, host, username, auth_type, log_path, log_pattern_key, created_at, updated_at)
                VALUES ('order-api', 'h', 'u', 'PASSWORD', '/a.log', 'SPRING_BOOT_3', now(), now()) RETURNING id""",
                Long.class);
    }

    String errorLog(ErrorStatus status) throws Exception {
        String id = String.format("20261108-%03d", ++seq);
        jdbc.update("""
                INSERT INTO error_logs (id, server_id, requested_time, window_min, raw_file_path, crop_start_line,
                    crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, status, created_at, updated_at)
                VALUES (?, ?, now(), 5, 'raw/x.log', 1, 1, 1, 1, 1, '[]', ?, now(), now())""", id, serverId, status.name());
        Files.createDirectories(ROOT.resolve("errors"));
        Files.writeString(ROOT.resolve("errors").resolve(id + ".log"), "line\n");
        return id;
    }

    ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    java.time.OffsetDateTime resolvedAt(String id) {
        return jdbc.queryForObject("SELECT resolved_at FROM error_logs WHERE id = ?", java.time.OffsetDateTime.class, id);
    }

    Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT status, note, resolution, resolved_by FROM error_logs WHERE id = ?", id);
    }

    @Test
    void terminateFromEveryStatus() throws Exception {
        Set<ErrorStatus> allowed = Set.of(ErrorStatus.CONFIRMED, ErrorStatus.ANALYZED, ErrorStatus.DEV_REQUIRED);
        java.time.OffsetDateTime before = java.time.OffsetDateTime.now().minusSeconds(1);
        for (ErrorStatus from : ErrorStatus.values()) {
            String id = errorLog(from);
            ResultActions r = postJson("/api/error-logs/" + id + "/terminate", "{ \"resolution\": \"  Fixed in abc123  \" }");
            if (allowed.contains(from)) {
                r.andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("TERMINATED"))
                        .andExpect(jsonPath("$.resolution").value("Fixed in abc123"))
                        .andExpect(jsonPath("$.resolvedBy").value("USER"))
                        .andExpect(jsonPath("$.resolvedAt").value(org.hamcrest.Matchers.endsWith("Z"))); // UTC
                assertThat(row(id)).containsEntry("status", "TERMINATED").containsEntry("resolved_by", "USER");
                assertThat(resolvedAt(id)).isBetween(before, java.time.OffsetDateTime.now().plusSeconds(1));
            } else {
                r.andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                        .andExpect(jsonPath("$.details.status").value(from.name()))
                        .andExpect(jsonPath("$.details.action").value("TERMINATE_BY_USER"));
                assertThat(row(id)).containsEntry("status", from.name()).containsEntry("resolution", null)
                        .containsEntry("resolved_by", null);
                assertThat(resolvedAt(id)).isNull();
            }
        }
    }

    @Test
    void devRequiredFromEveryStatus() throws Exception {
        for (ErrorStatus from : ErrorStatus.values()) {
            String id = errorLog(from);
            ResultActions r = postJson("/api/error-logs/" + id + "/dev-required", "{ \"note\": \"NPE in Order.place\" }");
            if (from == ErrorStatus.ANALYZED) {
                r.andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("DEV_REQUIRED"))
                        .andExpect(jsonPath("$.note").value("NPE in Order.place"))
                        .andExpect(jsonPath("$.resolvedBy").value(org.hamcrest.Matchers.nullValue()))
                        .andExpect(jsonPath("$.resolvedAt").value(org.hamcrest.Matchers.nullValue()));
            } else {
                r.andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                        .andExpect(jsonPath("$.details.action").value("DEV_REQUIRED"));
                assertThat(row(id)).containsEntry("status", from.name()).containsEntry("note", null);
            }
        }
    }

    @Test
    void devRequiredThenTerminateKeepsTheNote() throws Exception {
        String id = errorLog(ErrorStatus.ANALYZED);
        postJson("/api/error-logs/" + id + "/dev-required", "{ \"note\": \"check Order.place\" }").andExpect(status().isOk());
        postJson("/api/error-logs/" + id + "/terminate", "{ \"resolution\": \"fixed\" }")
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.note").value("check Order.place"))
                .andExpect(jsonPath("$.resolution").value("fixed"));
        mvc.perform(get("/api/error-logs/" + id)).andExpect(jsonPath("$.resolvedBy").value("USER"))
                .andExpect(jsonPath("$.resolvedAt").isString());
    }

    /** Contract: nullable response fields are always present and sent as null, never omitted. */
    @Test
    void nullFieldsAreSentAsExplicitNull() throws Exception {
        String id = errorLog(ErrorStatus.CROPPED);
        String body = mvc.perform(get("/api/error-logs/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"latestAnalysis\":null", "\"note\":null", "\"resolution\":null",
                "\"resolvedBy\":null", "\"resolvedAt\":null", "\"exceptionClass\":null", "\"exceptionMessage\":null");
        String list = mvc.perform(get("/api/error-logs")).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"exceptionClass\":null");
        String servers = mvc.perform(get("/api/servers")).andReturn().getResponse().getContentAsString();
        assertThat(servers).contains("\"rotatedPattern\":null", "\"customLogRegex\":null", "\"hostKeyFingerprint\":null");
    }

    @Test
    void validation() throws Exception {
        String id = errorLog(ErrorStatus.ANALYZED);
        for (String body : new String[] {"{}", "{ \"resolution\": \"   \" }", "{ \"resolution\": null }"}) {
            postJson("/api/error-logs/" + id + "/terminate", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.resolution").exists());
        }
        for (String body : new String[] {"{}", "{ \"note\": \"\" }"}) {
            postJson("/api/error-logs/" + id + "/dev-required", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.fields.note").exists());
        }
        assertThat(row(id)).containsEntry("status", "ANALYZED");
        postJson("/api/error-logs/20000101-001/terminate", "{ \"resolution\": \"x\" }").andExpect(status().isNotFound());
        postJson("/api/error-logs/20000101-001/dev-required", "{ \"note\": \"x\" }").andExpect(status().isNotFound());
    }

    @Test
    void agentTerminateOnlyFromDevRequired() throws Exception {
        for (ErrorStatus from : ErrorStatus.values()) {
            String id = errorLog(from);
            if (from == ErrorStatus.DEV_REQUIRED) {
                ErrorLogDetail d = service.terminate(id, "fixed by agent", ResolvedBy.AGENT);
                assertThat(d.status()).isEqualTo(ErrorStatus.TERMINATED);
                assertThat(d.resolvedBy()).isEqualTo(ResolvedBy.AGENT);
                assertThat(d.resolvedAt()).isNotNull();
                assertThat(resolvedAt(id)).isNotNull();
            } else {
                assertThatThrownBy(() -> service.terminate(id, "fixed by agent", ResolvedBy.AGENT))
                        .isInstanceOfSatisfying(ApiException.class, e -> {
                            assertThat(e.getCode()).isEqualTo("INVALID_STATUS");
                            assertThat(e.getDetails()).containsEntry("action", "TERMINATE_BY_AGENT");
                        });
            }
        }
        String id = errorLog(ErrorStatus.DEV_REQUIRED);
        assertThatThrownBy(() -> service.terminate(id, " ", ResolvedBy.AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void openApiDescribesResolutionEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/terminate'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/terminate'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/error-logs/{id}/dev-required'].post.responses['400']").exists())
                .andExpect(jsonPath("$.components.schemas.TerminateRequest.required[0]").value("resolution"))
                .andExpect(jsonPath("$.components.schemas.ErrorLogDetail.properties.resolvedAt.type")
                        .value(org.hamcrest.Matchers.contains("string", "null")))
                .andExpect(jsonPath("$.components.schemas.ErrorLogDetail.properties.resolvedAt.format").value("date-time"))
                .andExpect(jsonPath("$.components.schemas.DevRequiredRequest.required[0]").value("note"))
                .andExpect(jsonPath("$.components.schemas.ErrorLogDetail.properties.resolvedBy.enum")
                        .value(org.hamcrest.Matchers.contains("USER", "AGENT", null))); // nullable
    }
}
