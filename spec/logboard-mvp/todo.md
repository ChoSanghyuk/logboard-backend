# Backend Roadmap & TODO — Logboard MVP

Read first: `design.md`, `api-contract.md` (same folder).
Stack: Java 21 · Spring Boot (latest stable ≥3.5) · Gradle Kotlin DSL · PostgreSQL 16 + Flyway ·
Apache MINA SSHD · Spring AI MCP Server (WebMVC, Streamable HTTP) · springdoc-openapi ·
JUnit 5 + Testcontainers.

## Package layout (proposal)
```
com.logboard
 ├─ server/        Server entity, CRUD API, SSH test, credential crypto
 ├─ fetch/         SftpLogFetcher, RotatedFileResolver, gzip handling
 ├─ crop/          LogEntryParser, ExceptionDetector, CropEngine (pure, no Spring deps)
 ├─ errorlog/      ErrorLog entity, IdGenerator, status machine, API
 ├─ llm/           LlmEndpoint, PromptTemplate, LlmClient, AnalysisService (@Async)
 ├─ mcp/           MCP tool beans + token filter
 └─ common/        error handling (ApiError), storage paths, config props
```

## Milestones
| M | Deliverable | Unblocks FE |
|---|-------------|-------------|
| M1 | Setup + Server registry + OpenAPI published | Servers page on real API |
| M2 | Fetch + Crop + Error log API | Search & detail pages |
| M3 | LLM endpoints, prompts, analyses, resolution | Analysis panel |
| M4 | MCP server + hardening + Docker | E2E test |

## TODO

### B0 — Project setup (M1)
- [x] Gradle project, Java 21 toolchain, Spring Boot web/validation/data-jpa/actuator
- [x] `docker-compose.dev.yml` with PostgreSQL. Flyway `V1__init.sql` for all tables in design §5
- [x] `application.yml` config props: `logboard.storage.root`, `logboard.crypto.master-key` (env),
      `logboard.mcp.token` (env), `logboard.crop.before=20`, `after=10`, `expand-step=50`
- [x] Global `ApiError` handler (`code`, `message`, `details`)
- [x] springdoc at `/v3/api-docs`. CORS for `http://localhost:5173` in dev
- [x] `structure.md` created
- Verify: `./gradlew test` is green. The app boots against compose DB.

### B1 — Server registry (M1)
- [x] `Server` entity + repository + CRUD controller per contract
- [x] AES-GCM `SecretCipher` for password/private key/apiKey. Secrets are never returned
- [x] Log pattern presets (`/log-patterns`): at least `SPRING_BOOT_3`
      (`2026-11-08T14:31:58.123+09:00  ERROR 123 --- [app] [thread] c.f.Cls : msg`),
      `SPRING_BOOT_2` (`2026-11-08 14:31:58.123 ERROR 123 --- [thread] c.f.Cls : msg`),
      `LOGBACK_DEFAULT` (`14:31:58.123 [thread] ERROR c.f.Cls - msg` — date taken from file), plus custom regex
      with named groups `ts`, `level`
- [x] `POST /servers/{id}/test`: SSH connect, `stat` logPath
- Verify: CRUD integration tests. Cipher round-trip test. SSH test against a Testcontainers `openssh-server`.

### B2 — Log fetching (M2)
- [x] `RotatedFileResolver`: given `rotatedPattern` + time window, list matching remote files (via SFTP `ls`)
- [x] `SftpLogFetcher`: copy to `{root}/raw/{serverId}/{yyyyMMddHHmmss}-{file}`, decompress `.gz`,
      merge multiple files chronologically, handle charset
- [x] Map failures to `502 FETCH_FAILED` with a clear message (auth, not found, timeout)
- Verify: integration test with an SSH container holding active + rotated + gz files.

### B3 — Crop engine (M2) — pure Java, heavily unit-tested
- [x] `LogEntryParser`: lines → entries (header + continuation lines), using the server regex + timezone
- [x] `ExceptionDetector`: candidate rules in design §6 step 5
- [x] `CropEngine`: anchor = candidate closest to time. Crop = entry ± context. Expand/anchor switch.
      Clamp to file bounds. Label lines with `kind` ANCHOR/STACK/CONTEXT
- [x] Test fixtures under `src/test/resources/logs/`: single NPE, nested `Caused by` chain,
      `... N more`, multiple exceptions in window, none in window, multi-line non-exception message,
      file boundary cases
- Verify: every fixture has an expected (start,end,anchor) assertion.

### B4 — Error log API (M2)
- [x] `ErrorLogIdGenerator`: `YYYYMMDD-NNN` via `ErrorLogSeq` row lock (`SELECT … FOR UPDATE`)
      — concurrency test with 20 parallel creates → no duplicates
- [x] `search`, `get`, `list` (filters + paging), `crop` PATCH, `confirm`, `discard`
- [x] Write the crop file `{root}/errors/{ID}.log` on create and on every re-crop
- [x] Status machine guarded centrally. Illegal transition → `409 INVALID_STATUS`
- Verify: API tests for every endpoint + transition matrix.

### B5 — LLM integration (M3)
- [x] `LlmEndpoint` + `PromptTemplate` CRUD. Validate `{{errorLog}}` placeholder. Single `isDefault` each
      Unique names (`409 DUPLICATE_NAME`). `clearApiKey` on PUT. See the "Rules for LLM endpoints & prompts" section in api-contract.md
- [x] `LlmClient` (RestClient, OpenAI-compatible chat completions, per-endpoint timeout, bearer apiKey)
- [x] `/llm-endpoints/{id}/test`
- [x] `AnalysisService`: render template → persist PENDING → `@Async` call → DONE/FAILED.
      On DONE set ErrorLog → ANALYZED
- [x] Analysis history endpoints
- Verify: tests with WireMock for success, timeout, 500, malformed response.

### B6 — Resolution (M3)
- [x] `terminate` (requires `resolution`), `dev-required` (requires `note`), with `resolvedBy=USER`
- Verify: transition tests.

### B7 — MCP server (M4)
- [x] Add Spring AI MCP server WebMVC starter. Streamable HTTP at `/mcp`. Server name `logboard`
- [x] Tools `list_dev_required_errors`, `get_error_log`, `terminate_error` (design §8). Reuse services and the status machine.
      `resolvedBy=AGENT`. Write clear tool descriptions (agents read them)
- [x] Bearer token filter on `/mcp/**` (`401` without valid token)
- Verify: integration test using MCP Java SDK client (list → get → terminate). Manual check with
  MCP Inspector and Claude Code config from design §8. Document the steps in `structure.md`/README.

### B8 — Hardening & delivery (M4)
- [x] Dockerfile (layered jar). Root `docker-compose.yml` with postgres + backend + frontend(nginx)
- [x] Structured app logging. Mask secrets in logs
- [x] Refuse to start when `LOGBOARD_MCP_TOKEN` is shorter than 32 chars (dev compose/config ships a long sample token)
- [ ] (post-MVP backlog, not required for DoD) Retention: delete raw copies of DISCARDED / old error logs
- [x] SSH host-key verification: trust on first use. Store the fingerprint on the server. A changed key fails `/test` and fetch
      with a clear message, and the user can reset the fingerprint
- [x] README: document tuning analysis concurrency via `spring.task.execution.pool.*` (default 8 threads, unbounded queue)
- [x] README: warn that changing the master key makes stored secrets unreadable (no re-encryption in MVP). Env vars (`LOGBOARD_CRYPTO_MASTER_KEY`, `LOGBOARD_MCP_TOKEN`, DB), run & test
- Verify: `docker compose up` → full flow works against the SSH test container.

### After B8 (pm-notes-after-B8.md)
- [x] `/v3/api-docs` on by default, `SPRINGDOC_API_DOCS_ENABLED=false` disables it (ApiDocsDisabledTest, README)
- [x] Real server naming `application.log` + `application-%d{yyyy-MM-dd}-%i.log.gz` (same dir): RotatedPatternTest +
      SftpLogFetcherIntegrationTest (-1/-2 same day in index order, previous-day .gz, active file). Demo sshd,
      e2e_flow.py (extra search in the rotated .gz) and the README example use it. `%d{…, Zone/Name}` is now reported
      as an invalid date format, not as a token in the directory
- Verify: `./gradlew build` green, `e2e_flow.py` ALL OK on the compose demo stack.

### B9 — OpenAPI nullable response fields (pm-notes-B9-openapi-nullables.md)
- [x] `ErrorLogDetail.latestAnalysis` description: "`null` if none (always present)"
- [x] Nullable response DTO fields marked required + nullable in `/v3/api-docs` (request DTOs unchanged)
- Verify: test asserting `/v3/api-docs` for representative fields. `./gradlew build` green.

### B10 — Docs: compose 1.29 recreate bug (small)
- [x] README + root `docker-compose.yml` header: with compose 1.29 on newer Docker, `up -d --build` over a running stack
      fails with `KeyError: 'ContainerConfig'`. Workaround: `docker-compose down` (or `docker-compose rm -sf <service>`) first.
      Compose v2 (`docker compose`) is supported but optional

## Definition of Done (per task)
Tests are green. The contract is respected (or the PM is notified of a change). `structure.md` is updated if the structure changed.
