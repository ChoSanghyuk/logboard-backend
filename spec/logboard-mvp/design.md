# Logboard MVP — System Design

Status: CONFIRMED · Author: PM · 2026-11-08

## 1. Goal

One dashboard to pull exception logs from 10+ Java/Spring (WAS) servers, crop the
relevant stack trace, have an LLM explain it, and route unsolved errors to external
code agents through an MCP server.

## 2. Assumptions (A1, A3, A4 confirmed by user on 2026-11-08)

| # | Assumption |
|---|------------|
| A1 | "port" in server enrollment = **SSH port** (default 22). Log files are fetched over **SFTP**. |
| A2 | SSH auth by password **or** private key, stored encrypted (AES-GCM, master key from env var). |
| A3 | Error ID `YYYYMMDD-{seq}` uses the **date the error log was created in Logboard** (seq resets daily, 3-digit zero-padded: `20261108-001`). |
| A4 | The "designated LLM server" exposes an **OpenAI-compatible** `/v1/chat/completions` API (vLLM, Ollama, OpenAI, LiteLLM gateway, …). |
| A5 | Internal tool, a single team, no user login in MVP (deployed in a trusted network). The MCP endpoint is protected by an API token. |
| A6 | Logs are Logback/Spring Boot style: each entry starts with a timestamp, and stack trace lines follow without one. The timestamp pattern can be set per server and has presets. |
| A7 | Rotated files follow a per-server pattern (e.g. `application-%d{yyyy-MM-dd}-%i.log.gz`, the real server convention). If no pattern is set, only the active log file is searched. |

## 3. Architecture

```
 ┌──────────────┐   REST/JSON   ┌──────────────────────────────────────────┐
 │  Frontend    │ ────────────▶ │  Backend (Spring Boot, single process)    │
 │  React SPA   │  /api/**      │                                           │
 └──────────────┘               │  ┌─────────────┐  ┌────────────────────┐  │
                                │  │ REST API    │  │ MCP Server         │◀─┼── External code agent
 ┌──────────────┐  MCP          │  │ /api/**     │  │ /mcp (Streamable   │  │   (Claude Code, etc.)
 │ Code agent   │ ────────────▶ │  └──────┬──────┘  │  HTTP, Bearer key) │  │
 └──────────────┘               │         │         └─────────┬──────────┘  │
                                │  ┌──────▼───────────────────▼──────────┐  │
                                │  │ Services                            │  │
                                │  │  ServerRegistry · LogFetcher(SFTP)  │  │
                                │  │  CropEngine · ErrorLogService       │  │
                                │  │  LlmClient · AnalysisService        │  │
                                │  └──────┬──────────────┬───────────────┘  │
                                └─────────┼──────────────┼──────────────────┘
                                          │              │
                              ┌───────────▼──┐   ┌───────▼─────────────────┐
                              │ PostgreSQL   │   │ Local storage            │
                              │ (metadata)   │   │ {root}/raw/…  copied logs│
                              └──────────────┘   │ {root}/errors/ID.log     │
                                                 └──────────────────────────┘
        SFTP ▲ (copy log files)                   HTTP ▼ (chat completions)
   ┌─────────┴─────────┐                         ┌────────────────┐
   │ 10+ WAS servers   │                         │ LLM server(s)  │
   └───────────────────┘                         └────────────────┘
```

The MCP server runs **inside the backend process**. It shares the DB and services, so
there is only one source of truth and one deployable.

## 4. Technology Selection

| Layer | Choice | Why |
|-------|--------|-----|
| Backend language | **Java 21** | Same stack as the monitored servers, so the team already knows it and can reuse its Java exception-format knowledge. |
| Backend framework | **Spring Boot** (latest stable, ≥3.5), Gradle Kotlin DSL | Standard and well supported. |
| MCP | **Spring AI MCP Server starter** (WebMVC, Streamable HTTP transport) on the official MCP Java SDK | Tools are annotated beans in the same app. |
| SSH/SFTP | **Apache MINA SSHD** (sshd-sftp) | Maintained, pure Java, supports key and password auth. |
| DB | **PostgreSQL 16** + **Flyway**; Testcontainers for tests | Simple relational metadata; migrations are versioned. |
| LLM client | Spring `RestClient` → OpenAI-compatible API | Endpoints are configured at runtime and there can be several, so a thin client is simpler than Spring AI autoconfig. |
| API docs | springdoc-openapi (`/v3/api-docs`) | The frontend generates TS types from it. |
| Frontend language | **TypeScript** | Type safety; types are generated from OpenAPI. |
| Frontend framework | **React 19 + Vite** | Mainstream SPA tooling. |
| UI kit | **Mantine** (+ @mantine/dates) | Gives ready-made forms, tables, modals and date-time pickers with little code. |
| Data fetching | **TanStack Query** + `openapi-fetch` (types from `openapi-typescript`) | Caching, polling for analysis status. |
| Routing | React Router | |
| Markdown reply | react-markdown + rehype-highlight | LLM replies are markdown. |
| Mocks / tests | MSW (mock API until backend ready), Vitest + Testing Library, Playwright (e2e) | |
| Deploy | Docker Compose: `postgres`, `backend`, `frontend` (nginx serving SPA, proxying `/api` and `/mcp`) | |

## 5. Domain Model

```
Server(id, name, host, sshPort, username, authType[PASSWORD|KEY], secretEnc,
       logPath, rotatedPattern?, logPatternKey? | customLogRegex? (exactly one), timezone,
       charset, deletedAt?, createdAt, updatedAt)     -- soft-deleted when error logs reference it

LlmEndpoint(id, name, baseUrl, model, apiKeyEnc?, timeoutSec, isDefault)

PromptTemplate(id, name, content, isDefault)       -- content contains {{errorLog}},
                                                     -- optional {{serverName}}, {{errorTime}}
ErrorLog(id "YYYYMMDD-NNN", serverId, requestedTime, windowMin,
         rawFilePath, cropStartLine, cropEndLine, anchorLine,
         exceptionClass, exceptionMessage, status, note,
         resolvedBy[USER|AGENT]?, resolution?, createdAt, updatedAt)

ErrorLogSeq(date, lastSeq)                         -- row-locked counter for ID generation.
                                                     -- `date` = creation date in Asia/Seoul. NNN is
                                                     -- zero-padded to 3 digits and may grow past 999

Analysis(id, errorLogId, llmEndpointId, promptTemplateId, renderedPrompt,
         reply, status[PENDING|DONE|FAILED], errorMessage, createdAt, finishedAt)
```

### Error status lifecycle

```
 CROPPED ──confirm──▶ CONFIRMED ──analyze──▶ ANALYZED ──dev needed──▶ DEV_REQUIRED
   │  ▲ expand/                     │  (re-analyze allowed)                 │
   │  └ re-crop                     └─ analysis FAILED stays CONFIRMED      │
   └──────── discard ──▶ DISCARDED                                          │
 terminate (USER) from CONFIRMED / ANALYZED / DEV_REQUIRED ─────▶ TERMINATED ◀─ MCP terminate (AGENT,
                                                                               only from DEV_REQUIRED)
```
- The crop can only be changed while the status is `CROPPED`.
- `DEV_REQUIRED` is the queue the MCP server works from.

## 6. Core Flow — Search & Crop

1. `POST /api/error-logs/search {serverId, errorTime, windowMin=5}`
2. **Resolve files**: the active `logPath`, plus any rotated files whose date matches
   `errorTime ± window` (from `rotatedPattern`).
   **Real server convention (confirmed by the owner, 2026-11-09)**: today's file is `application.log`. Past files are
   `application-YYYY-MM-DD-N.log.gz` (e.g. `application-2026-11-08-1.log.gz`), in the **same directory**. Configure them as
   `logPath=/…/application.log`, `rotatedPattern=application-%d{yyyy-MM-dd}-%i.log.gz`. The format may change, so it
   stays user-editable per server. `.gz` files are always decompressed. Timezone options in `%d` and date folders are not used.
3. **Copy** them over SFTP to `{root}/raw/{serverId}/{yyyyMMddHHmmss}-{file}` (server **id**, not name, so free-text names are safe in paths). `.gz` files are decompressed locally.
   If several files match, they are merged in chronological order into one raw file.
4. **Parse into entries**: a line that matches the server's timestamp regex starts a new entry.
   Lines that don't match are continuation lines (stack traces).
5. **Detect exception candidates** whose timestamp is inside the window. A candidate is any entry that
   - has level `ERROR`, **or**
   - has continuation lines matching `^\s+at\s+\S+\(` (the `(` avoids matching prose like "  at least once"), `^Caused by:`, `^\s*\.\.\. \d+ more`,
     or a header like `^[\w.$]+(Exception|Error)(: .*)?$`.
6. **Choose the anchor**: the candidate closest to `errorTime`. The other candidates are returned too, so the user can switch.
7. **Crop** = the whole anchor entry (header + full stack trace + `Caused by` chain), plus
   `before=20` and `after=10` context lines (defaults, configurable).
8. **Save** `{root}/errors/{ID}.log`. Store the line range and raw path in `ErrorLog` with status `CROPPED`.
9. No candidate found → return `404 NO_EXCEPTION_FOUND` along with the window searched. No ID is created.

**Expand / re-crop** (status must be `CROPPED`): `PATCH /api/error-logs/{id}/crop`
with `{expandBefore, expandAfter}` (lines), or `{anchorLine}` to switch candidate.
The crop is recomputed from the **stored raw copy** (no re-download) and the same ID file is overwritten.

## 7. Core Flow — Analysis & Resolution

1. The user confirms → status `CONFIRMED`.
2. `POST /api/error-logs/{id}/analyses {llmEndpointId, promptTemplateId}` → the template is
   rendered (`{{errorLog}}` = cropped content). An `Analysis(PENDING)` is created and called
   asynchronously (`@Async`, timeout per endpoint). Request: a single `user` message whose content is
   the rendered template (no system message). See the API contract for details.
3. The frontend polls `GET /api/analyses/{id}` every 2s → `DONE` sets ErrorLog `ANALYZED`.
4. The user chooses **Terminate** (`resolution` note) → `TERMINATED`, `resolvedBy=USER`,
   or **Developer needed** (`note`) → `DEV_REQUIRED`.

## 8. MCP Server

Endpoint: `/mcp` (Streamable HTTP). Auth: `Authorization: Bearer <LOGBOARD_MCP_TOKEN>`.

| Tool | Input | Output | Rule |
|------|-------|--------|------|
| `list_dev_required_errors` | `serverName?`, `limit=20` | `[{id, serverName, exceptionClass, exceptionMessage, createdAt, note}]` | Only `DEV_REQUIRED` |
| `get_error_log` | `errorId` | `{id, server{name,host}, errorTime, exceptionClass, message, status, userNote, croppedLog, latestAnalysis{prompt, reply}}` | Any status; unknown ID → tool error |
| `terminate_error` | `errorId`, `resolution` (required, e.g. fix summary / commit) | `{id, status:"TERMINATED"}` | Only from `DEV_REQUIRED`; sets `resolvedBy=AGENT` |

Example client config (Claude Code):
```json
{ "mcpServers": { "logboard": { "type": "http", "url": "http://logboard:8080/mcp",
  "headers": { "Authorization": "Bearer ${LOGBOARD_MCP_TOKEN}" } } } }
```

## 9. Screens (Frontend)

```
┌ Logboard ───────────────────────────────────────────────────────────────┐
│ [Search] [Errors] [Servers] [Settings ▾ LLM endpoints | Prompts]         │
└──────────────────────────────────────────────────────────────────────────┘

/search                         /errors/:id
┌───────────────────────────┐   ┌──────────────────────────────────────────────┐
│ Server   [order-api   ▾]  │   │ 20261108-003 · order-api · CROPPED            │
│ Time     [2026-11-08 14:32]│  │ NullPointerException: ... at 14:31:58         │
│ Window   [± 5 min ▾]      │   │ Candidates: (●14:31:58 NPE) (○14:33:10 SQLEx) │
│          [ Search ]       │   ├──────────────────────────────────────────────┤
└───────────────────────────┘   │ [▲ +50 lines before]                          │
      ↓ redirects to detail     │  1201 2026-11-08 14:31:57 INFO ...            │
                                │  1203 2026-11-08 14:31:58 ERROR ... ◀ highlighted
/servers  table + add/edit      │  1204 java.lang.NullPointerException: ...     │
 modal + [Test connection]      │  1205   at com.foo.Order.place(Order.java:42) │
                                │ [▼ +50 lines after]                           │
/errors   table filter:         ├──────────────────────────────────────────────┤
 status, server, date range     │ [Discard]                    [Confirm crop ✓] │
                                ├──── after confirm ───────────────────────────┤
/settings/llm      CRUD         │ LLM [internal-vllm ▾] Prompt [Java triage ▾]  │
/settings/prompts  CRUD +       │ [Send to LLM]   … analyzing (spinner)          │
 placeholder help               │ ┌ Reply (markdown) ─────────────────────────┐ │
                                │ └──────────────────────────────────────────┘ │
                                │ [Terminate (note)]   [Developer needed (note)]│
                                └──────────────────────────────────────────────┘
```

## 10. Out of Scope (MVP)

User login/roles, scheduled raw-file cleanup, live log tailing, alerting, streaming LLM
output, multi-instance backend. These can be added later; the design does not block them.

## 11. Open Questions

- A1 SSH port, A3 creation date, A4 OpenAI-compatible: **confirmed**.
- A5: Is dashboard login needed? Not in the MVP. Revisit before production rollout.
