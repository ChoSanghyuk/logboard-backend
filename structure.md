# Logboard Backend — Structure

Spring Boot 4.1 · Java 21 · Gradle Kotlin DSL · PostgreSQL 16 + Flyway. Specs: `spec/logboard-mvp/`.

## Run
Details (env vars, master-key warning, tuning, MCP setup): [`README.md`](README.md).
```bash
docker-compose -f docker-compose.dev.yml up -d   # Postgres on localhost:5434 (logboard/logboard)
./gradlew bootRun     # port 8080, profile "dev" (sample master key + MCP token in application-dev.yml, CORS :5173)
./gradlew test        # needs Docker (Testcontainers postgres:16-alpine, linuxserver/openssh-server)
# full stack: ../docker-compose.yml (postgres, backend :8080, frontend :8088; --profile demo: demo-sshd, demo-llm)
python3 demo/e2e_flow.py   # end-to-end flow against the compose demo stack
```
Env: `LOGBOARD_DB_URL|USERNAME|PASSWORD` (default: dev compose DB), `LOGBOARD_STORAGE_ROOT` (default `./data`).

## Layout
```
build.gradle.kts, settings.gradle.kts, gradlew      build (springdoc 3.2.0 pinned, Spring AI BOM 2.0.2, rest via Boot BOM)
Dockerfile, .dockerignore                           multi-stage: gradle bootJar → layered jar on eclipse-temurin:21-jre, uid 10001
docker-compose.dev.yml                              dev PostgreSQL 16
../docker-compose.yml                               full stack (owned by backend, B8); frontend build context ../Frontend Developer
demo/                                               sshd/logs (application.log + application-%d{yyyy-MM-dd}-%i.log.gz), llm-stub/mappings (WireMock),
                                                    e2e_flow.py (full flow incl. MCP, stdlib only)
src/main/java/com/logboard/
 ├─ LogboardApplication.java
 ├─ common/
 │   ├─ LogboardProperties    logboard.* config (storage.root, crypto.masterKey, mcp.token ≥32 chars or no start,
 │   │                        crop.{before,after,expandStep,maxLines})
 │   ├─ logging/              SecretMasker (known secrets + Bearer/password=/apiKey/PEM patterns), MaskingEncoder
 │   │                        (wraps Boot's pattern or structured encoder; used by logback-spring.xml)
 │   ├─ ApiError              { code, message, details } error body
 │   ├─ ApiException          throw → ApiError with given status/code/details
 │   ├─ GlobalExceptionHandler  maps all exceptions → ApiError (see codes below)
 │   ├─ OpenApiNullableCustomizer  OpenAPI 3.1: every non-required property → nullable (type [T,"null"] /
 │   │                        oneOf [$ref, null]); except ApiError.details. DTOs must mark never-null fields REQUIRED.
 │   │                        Response schemas (not a request body): all properties required (nullable ones too)
 │   ├─ AsyncConfig           @EnableAsync (analyses run on Boot's applicationTaskExecutor)
 │   ├─ SecretCipher          AES-256-GCM (key = SHA-256(master key)), Base64(IV||ct). For all stored secrets
 │   └─ DevCorsConfig         @Profile("dev"): CORS /api/** for localhost:5173
 ├─ server/                   B1 server registry
 │   ├─ Server, AuthType, ServerRepository   entity (soft delete via deletedAt)
 │   ├─ ServerController      /api/servers CRUD, /{id}/test, /{id}/reset-host-key, /api/log-patterns
 │   ├─ ServerService         validation (pattern key | custom regex w/ ts+level, tz, charset), secrets, delete
 │   ├─ ServerUpsert / ServerDto / ConnectionTestResult / LogPatternDto   API records (DTO never has secrets)
 │   ├─ LogPatternPreset      SPRING_BOOT_3, SPRING_BOOT_2, LOGBACK_DEFAULT (time only) header regexes
 │   ├─ RotatedPattern        parse/validate rotatedPattern (%d{fmt} once, %i, optional .gz) + select files in window
 │   └─ SshConnector          withSftp(server, work) → SshFailure(clear msg) · test() = connect+auth+stat.
 │                            Host key TOFU: checked during KEX (before credentials), pinned after first login
 ├─ fetch/                    B2 log fetching
 │   ├─ RotatedFileResolver   SFTP ls of pattern dir → rotated files overlapping window, oldest first
 │   ├─ SftpLogFetcher        rotated + active → one UTF-8 raw file {root}/raw/{serverId}/{yyyyMMddHHmmss}-{logName}
 │   │                        (.gz decompressed, server charset→UTF-8, LF). Missing active file OK if a rotated
 │   │                        file matches. Failures → 502 FETCH_FAILED
 │   └─ FetchedLog            rawFile, totalLines, activeFileMissing, sources[{remotePath, firstLine, lineCount, date}];
 │                            saved as {raw}.meta.json, FetchedLog.read(raw) loads it for re-crop
 ├─ crop/                     B3 crop engine — pure Java, no Spring
 │   ├─ LogEntryParser        streams raw file → LogEntry (header + continuation; preamble has no header).
 │   │                        TimestampParser: [date[T| ]]HH:mm:ss[.,frac][offset]; no date → FileSegment date
 │   │                        (= date of segment's last line, walking back over midnight); no offset → server tz
 │   ├─ ExceptionDetector     design §6.5 rules → Candidate{anchorLine, endLine, time, exceptionClass, message}
 │   ├─ CropEngine            scan (candidates in window) · crop (entry ± before/after) · expand · clamp to file
 │   │                        and maxLines (anchor entry always whole; room goes above first; expand never shrinks)
 │   └─ Crop, LineKind        lines labeled ANCHOR (header) / STACK (anchor continuation) / CONTEXT; LineKind.of(...)
 ├─ errorlog/                 B4 error log API
 │   ├─ ErrorLog, ErrorStatus, ResolvedBy, ErrorLogRepository   entity (stores scan result: candidates JSON,
 │   │                        anchorEndLine, totalLines → GET/PATCH never re-scan); findForUpdate = row lock
 │   ├─ ErrorLogStatusMachine ONLY place that changes status. Action enum = all contract transitions incl. B5–B7
 │   │                        (START_ANALYSIS, ANALYSIS_DONE, DEV_REQUIRED, TERMINATE_BY_USER/AGENT) → 409 INVALID_STATUS
 │   ├─ ErrorLogIdGenerator   YYYYMMDD-NNN (Asia/Seoul), error_log_seq upsert … RETURNING (row lock till commit)
 │   ├─ ErrorLogService       search = fetch → scan → closest anchor → crop → persist + {root}/errors/{ID}.log;
 │   │                        recrop (from raw + stored candidates), confirm, discard, get, list (Specification),
 │   │                        terminate(id, resolution, USER|AGENT) sets resolvedAt (B6, reused by MCP), devRequired(id, note)
 │   ├─ ErrorLogController    /api/error-logs: search, list, get, PATCH crop, confirm, discard, terminate, dev-required
 │   └─ SearchRequest, CropRequest, TerminateRequest, DevRequiredRequest, ErrorLogSummary, ErrorLogDetail(CropDto, CropLineDto, CandidateDto), ErrorLogPage
 ├─ llm/                      B5 LLM endpoints, prompts, analyses
 │   ├─ LlmEndpoint / PromptTemplate (+Repository, Service, Controller, Upsert, Dto)   CRUD /api/llm-endpoints,
 │   │                        /api/prompts. Unique name, single isDefault (clearDefaultExcept before save), apiKey
 │   │                        encrypted + clearApiKey, baseUrl normalized (no trailing '/', no /v1), DELETE → 409 IN_USE
 │   ├─ LlmClient             RestClient (JDK HttpClient, HTTP/1.1: no h2c) → {baseUrl}/v1/chat/completions; per-call timeout; Bearer
 │   │                        only with apiKey; failures → LlmException(clear message)
 │   ├─ Analysis, AnalysisRepository, AnalysisDto   PENDING|DONE|FAILED
 │   ├─ AnalysisService       start: status machine START_ANALYSIS → render (PromptRenderer, one pass) → save
 │   │                        PENDING → AnalysisRunner.run (async, after commit) · get · history (newest first)
 │   ├─ AnalysisRunner        @Async LLM call → DONE (+ ANALYSIS_DONE if allowed) / FAILED; on startup fails
 │   │                        leftover PENDING analyses
 │   └─ AnalysisController, LlmEndpointController (/test → LlmTestResult), PromptTemplateController
 └─ mcp/                      B7 MCP server (Spring AI 2.0.2 WebMVC, Streamable HTTP /mcp, name "logboard")
     ├─ LogboardMcpTools      @McpTool list_dev_required_errors · get_error_log · terminate_error. Returns
     │                        CallToolResult: JSON text, or isError=true + clear message (never throws).
     │                        terminate → ErrorLogService.terminate(…, AGENT) (status machine)
     ├─ McpTokenFilter        /mcp, /mcp/*: Authorization: Bearer <logboard.mcp.token>, constant-time → else 401
     └─ McpConfig             registers the filter (highest precedence). MCP settings: spring.ai.mcp.server.*
src/main/resources/
 ├─ application.yml, application-dev.yml (dev-only sample secrets), logback-spring.xml (masked console)
 └─ db/migration/ V1__init.sql (all tables, design §5) · V2__server_soft_delete.sql (deleted_at, name unique
                 among active) · V3__error_log_crop_state.sql (anchor_end_line, total_lines, candidates JSON) ·
                 V4__llm_timestamps.sql (created_at/updated_at on llm_endpoints, prompt_templates) ·
                 V5__error_log_resolved_at.sql (resolved_at, set on terminate) · V6__server_host_key.sql
src/test/java/com/logboard/
 ├─ ApplicationIntegrationTest  full boot on Testcontainers PG: Flyway, props, api-docs (required/nullable), health, CORS
 ├─ ApiDocsDisabledTest  springdoc.api-docs.enabled=false (env SPRINGDOC_API_DOCS_ENABLED) → /v3/api-docs 404
 ├─ common/  GlobalExceptionHandlerTest, SecretCipherTest, LogboardPropertiesTest (token ≥32 / refuse start),
 │           logging/ SecretMaskerTest, MaskingEncoderTest (plain + ECS), LogMaskingIntegrationTest (captured console)
 ├─ mcp/     McpIntegrationTest (real port + MCP Java SDK client: list → get → terminate → REST check, tool
 │           errors, tool schemas, 401 for missing/wrong token)
 ├─ llm/     LlmClientTest (WireMock: success, no key, timeout, 500, 401, malformed, refused), PromptRendererTest,
 │           LlmApiIntegrationTest (CRUD, defaults, /test, analyses + status machine, restart cleanup, OpenAPI)
 ├─ errorlog/ ErrorLogApiIntegrationTest (all endpoints, search via SSH container), ErrorLogStatusMachineTest
 │           (full status × action matrix), ErrorLogIdGeneratorTest (20 parallel creates, >999, Seoul date),
 │           ResolutionApiIntegrationTest (terminate/dev-required from every status, validation, AGENT)
 ├─ crop/    CropEngineTest (one test per fixture in test/resources/logs/), LogEntryParserTest, ExceptionDetectorTest
 ├─ fetch/   SftpLogFetcherIntegrationTest (active + rotated + .gz, EUC-KR, real server naming, failures)
 └─ server/  RotatedPatternTest, ServerApiIntegrationTest (CRUD/secrets/validation/OpenAPI), LogPatternPresetTest,
             SshConnectorTest (timeout/refused/unknown host), SshConnectionIntegrationTest (linuxserver/openssh-server)
```

## MCP (design §8) — manual check
Start the app (`./gradlew bootRun` with `LOGBOARD_MCP_TOKEN` set). The app refuses to start without the token.
- **curl**: `curl -i -X POST localhost:8080/mcp` → `401`. With `-H "Authorization: Bearer $LOGBOARD_MCP_TOKEN"
  -H "Accept: application/json, text/event-stream" -H "Content-Type: application/json"` and an `initialize` body →
  `200`, `serverInfo.name = logboard`; reuse the returned `Mcp-Session-Id` header for `tools/list` / `tools/call`.
- **MCP Inspector**: `npx @modelcontextprotocol/inspector` → Transport *Streamable HTTP*, URL `http://localhost:8080/mcp`,
  header `Authorization: Bearer <token>` → Connect → Tools → List: 3 tools. Run `list_dev_required_errors`, then
  `get_error_log {errorId}`, then `terminate_error {errorId, resolution}`; the log shows TERMINATED / AGENT in the UI.
  A wrong token must fail to connect (401).
- **Claude Code**: `claude mcp add --transport http logboard http://localhost:8080/mcp --header "Authorization: Bearer $LOGBOARD_MCP_TOKEN"`
  (or `.mcp.json`: `{"mcpServers":{"logboard":{"type":"http","url":"http://localhost:8080/mcp",
  "headers":{"Authorization":"Bearer ${LOGBOARD_MCP_TOKEN}"}}}}`), then `/mcp` in Claude Code shows `logboard` connected.

## Conventions
- REST under `/api`. OpenAPI at `/v3/api-docs`. Health at `/actuator/health`.
- Error codes: `ApiException` code as given (e.g. `NO_EXCEPTION_FOUND`, `FETCH_FAILED`, `INVALID_STATUS`);
  bean validation → 400 `VALIDATION_FAILED` + `details.fields{field: msg}`; other Spring MVC errors →
  HTTP status name (`BAD_REQUEST`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`…); unhandled → 500 `INTERNAL_ERROR`.
  `details` is always an object (`{}` when empty). Service-side field checks reuse 400 `VALIDATION_FAILED`
  + `details.fields`. Missing entity → 404 `NOT_FOUND`. Duplicate name → 409 `DUPLICATE_NAME`.
- All response timestamps are UTC (`spring.jackson.time-zone: UTC`). Storage paths in DB are relative to storage root.
- Secrets are write-only; DTOs expose `hasSecret`. Blank/absent secret on update keeps the stored one.
- Schema is owned by Flyway (`ddl-auto: validate`). Enum columns = VARCHAR + CHECK (map with `@Enumerated(STRING)`).

## DB tables (V1–V6)
`servers` · `llm_endpoints` · `prompt_templates` · `error_logs` (PK `YYYYMMDD-NNN`) · `error_log_seq(seq_date, last_seq)` · `analyses`.
Single default per `llm_endpoints`/`prompt_templates` enforced by partial unique index.
`servers.name` is unique among non-deleted servers (V2). `servers.host_key_fingerprint` = TOFU pin (V6). `servers` requires exactly one of `log_pattern_key` / `custom_log_regex`.
