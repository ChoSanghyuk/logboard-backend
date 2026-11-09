# Logboard MVP — REST API Contract (v1)

Shared by Backend and Frontend. The backend publishes the matching OpenAPI document at `/v3/api-docs`.
If the two disagree, raise it with the PM. Do not diverge silently.

Conventions
- Base path `/api`. JSON. Timestamps are ISO-8601 with offset (`2026-11-08T14:32:00+09:00`).
  Responses are emitted in **UTC** (`2026-11-08T05:32:00Z`). The UI must parse any offset and display
  times in the relevant server's timezone.
- Errors: `{ "code": "NO_EXCEPTION_FOUND", "message": "...", "details": {...} }` with a proper HTTP status.
  Common codes:
  - `400 VALIDATION_FAILED` with `details.fields: { <field>: <message> }`. This covers bean validation and custom checks.
  - `404 NOT_FOUND` for an unknown or soft-deleted id.
  - `409 DUPLICATE_NAME` with `details: { field: "name" }`.
  - `409 INVALID_STATUS` for an illegal status transition.
  - `409 IN_USE` when deleting something that is still referenced.
- Secrets (`password`, `privateKey`, `apiKey`) are **write-only**. Responses only show `hasSecret: true`.
- Error ID `YYYYMMDD-NNN`: the date is the creation date in Asia/Seoul. NNN may exceed 999.
- Lists: `?page=0&size=20` → `{ items: [...], total, page, size }` (only `GET /error-logs` is paged;
  `/servers`, `/llm-endpoints`, `/prompts`, analysis history are plain arrays).
- IDs: numeric (`int64`) everywhere except error logs (string `YYYYMMDD-NNN`).
- Create → `201` + DTO. Delete → `204`.
- Boolean JSON fields keep their exact names (`isDefault`, `hasSecret`, `ok`), not `default`.
- Request timestamps (e.g. search `errorTime`) are ISO-8601 **with offset**. The UI lets the user enter
  the time in the target server's timezone and sends it with that offset.

## Servers
| Method | Path | Body / Query | Response |
|--------|------|--------------|----------|
| GET | `/servers` | | `ServerDto[]` |
| POST | `/servers` | `ServerUpsert` | `201 ServerDto` |
| PUT | `/servers/{id}` | `ServerUpsert` (blank secret = keep) | `ServerDto` |
| DELETE | `/servers/{id}` | | `204`. Hard-delete if no error logs reference it, otherwise soft-delete (`deletedAt` set). Soft-deleted servers are hidden from `GET /servers` and cannot be searched, but existing error logs still show their `serverName` |
| POST | `/servers/{id}/test` | | Always `200 { ok, message, logFileFound, logFileSize? }`. Connection or auth failures and a missing file return `ok:false` with a specific message. Only an unknown id returns `404` |
| POST | `/servers/{id}/reset-host-key` | | `200 ServerDto` with `hostKeyFingerprint: null`. `404` if unknown or soft-deleted. The next `/test` or search trusts the key presented then |
| GET | `/log-patterns` | | `[{ key:"SPRING_BOOT_3", regex, example }, ...]` presets. Every preset regex has the named groups `ts` and `level`. `LOGBACK_DEFAULT` is time-only; the date comes from the file |

```
ServerUpsert { name, host, sshPort=22, username, authType: PASSWORD|KEY,
               password?, privateKey?, logPath, rotatedPattern?,
               logPatternKey | customLogRegex, timezone="Asia/Seoul", charset="UTF-8" }
ServerDto    = ServerUpsert minus secrets + { id, hasSecret, hostKeyFingerprint, createdAt, updatedAt }
```
Server validation (all `400 VALIDATION_FAILED`):
- Exactly one of `logPatternKey` / `customLogRegex` must be set. The error is reported on `fields.logPatternKey`.
  An unknown preset key is reported on `fields.logPatternKey`. A bad regex is reported on `fields.customLogRegex`.
- `customLogRegex` must compile and contain the named groups `ts` and `level`.
  The `ts` group must capture one of these timestamp shapes:
  - `[yyyy-MM-dd(T| )]HH:mm:ss[(.|,)SSS…][Z|±HH:mm|±HHmm]`
  - With no offset, the server `timezone` is used. With no date, the date is taken from the file.
  - Other shapes are accepted on save, but their entries can never be exception candidates.
- `timezone` and `charset` must be valid.
- The secret matching `authType` (`password` or `privateKey`) is **required on create**, and on update when
  `authType` changes. Otherwise a blank secret on update keeps the stored one.
- `name` must be unique among active servers (`409 DUPLICATE_NAME`).
- `rotatedPattern` (optional) uses logback-style syntax, e.g. `application-%d{yyyy-MM-dd}-%i.log.gz` (real server convention):
  - `%d` or `%d{<java date format>}` is required exactly once. `%i` is optional.
  - A trailing `.gz` / `[.gz]` is optional.
  - An optional fixed directory prefix (`/var/log/app/archive/app.%d.log`) may be given. Without one, the
    directory of `logPath` is used.
  - Rejected: unknown tokens, tokens in the directory, and timezone options inside `%d` (`%d{…, UTC}`).
    The error is on `fields.rotatedPattern`.
- Passphrase-protected private keys are not supported in the MVP.

SSH host key (trust on first use):
- `hostKeyFingerprint` (nullable, e.g. `"SHA256:d/7R…"`) is pinned after the first **successful** login. Changing a
  server's host or port forgets it.
- A changed key → `/test` `ok:false` and fetch `502 FETCH_FAILED`, with the message "Host key of {host}:{port} changed: …
  reset the host key for this server … No credentials were sent." The UI offers "Reset host key" for this.
- A successful `/test` message ends with "Host key SHA256:…".

## Error logs
| Method | Path | Body / Query | Response |
|--------|------|--------------|----------|
| POST | `/error-logs/search` | `{ serverId, errorTime, windowMin=5 }` | `201 ErrorLogDetail` · `404 NO_EXCEPTION_FOUND` (`details: { errorTime, windowMin, from, to }`) · `502 FETCH_FAILED` |
| GET | `/error-logs` | `status?` (repeatable), `serverId?`, `from?`, `to?` (filter on `requestedTime`), `page`, `size` | page of `ErrorLogSummary`, sorted `createdAt` desc |
| GET | `/error-logs/{id}` | | `ErrorLogDetail` |
| PATCH | `/error-logs/{id}/crop` | `{ expandBefore?, expandAfter?, anchorLine? }` | `ErrorLogDetail` (`409` if not CROPPED) |
| POST | `/error-logs/{id}/confirm` | | `ErrorLogDetail` → CONFIRMED (only from CROPPED) |
| POST | `/error-logs/{id}/discard` | | `ErrorLogDetail` → DISCARDED (only from CROPPED) |
| POST | `/error-logs/{id}/terminate` | `{ resolution }` | `ErrorLogDetail` → TERMINATED (from CONFIRMED/ANALYZED/DEV_REQUIRED) |
| POST | `/error-logs/{id}/dev-required` | `{ note }` | `ErrorLogDetail` → DEV_REQUIRED (from ANALYZED) |

```
ErrorLogSummary { id, serverId, serverName, serverTimezone, requestedTime, exceptionClass,
                  exceptionMessage, status, createdAt }
ErrorLogDetail  = ErrorLogSummary + {
  windowMin, note, resolution, resolvedBy, resolvedAt,
  crop: { startLine, endLine, anchorLine, totalLines, expandStep, maxLines,
          lines: [{ no, text, kind: CONTEXT|ANCHOR|STACK }] },
  candidates: [{ anchorLine, time, exceptionClass, message }],
  latestAnalysis?: AnalysisDto }
```
`expandStep` is the server-configured step (`logboard.crop.expand-step`, default 50). The UI's "+N before/after"
buttons send `expandBefore`/`expandAfter` = `expandStep`. `exceptionClass`/`exceptionMessage` describe the
**top-level** exception (the first exception line), not the deepest `Caused by`. For an ERROR entry with no
trace, `exceptionClass` is null and the message is the header text.
Error-log rules:
- Search `windowMin` must be 1–1440 (default 5). An unknown `serverId` (or a soft-deleted one) → `404 NOT_FOUND`.
  An `errorTime` without an offset → `400 BAD_REQUEST`.
- `GET /error-logs` `size` must be 1–100. `from`/`to` must be URL-encoded (`+` → `%2B`).
- `PATCH crop`:
  - An empty body, or an `anchorLine` that isn't a candidate, → `400 VALIDATION_FAILED`.
  - The crop never exceeds `maxLines` (`logboard.crop.max-lines`, default 2000). Expansion beyond it is clamped
    silently. If the anchor entry alone is longer, the crop is the anchor entry.
  - The UI disables the expand buttons when `endLine - startLine + 1 >= maxLines`.
- Candidates are captured when the search runs. Re-crops read the stored raw copy and don't re-scan it, so
  editing a server's log pattern later doesn't change existing error logs.
- Illegal status transition → `409 INVALID_STATUS` with `details: { status, action }`.
- `terminate` `{ resolution }` and `dev-required` `{ note }`: a missing, null or blank value → `400 VALIDATION_FAILED` on
  `fields.resolution` / `fields.note`. Values are trimmed. A request with no body at all → `400 BAD_REQUEST`.
  A note set by dev-required is kept after terminate.
- `resolvedBy` is `USER` or `AGENT`. `resolvedAt` is the time of terminate (null until TERMINATED).
  The agent (MCP) can only terminate from DEV_REQUIRED.
- `latestAnalysis` is the newest analysis of any status, or an explicit `null` (the field is always present) when there is none.
  As a general rule, nullable response fields are always present and sent as `null`, never omitted.
- `serverTimezone` is the server's IANA timezone, also for soft-deleted servers. The UI displays all times for that
  error log in it.
`kind` lets the UI highlight lines: `ANCHOR` = the exception entry's header line, `STACK` = its
continuation lines, `CONTEXT` = surrounding lines.

## LLM endpoints & prompts
| Method | Path | Notes |
|--------|------|-------|
| GET/POST | `/llm-endpoints` | `{ name, baseUrl, model, apiKey?, clearApiKey?, timeoutSec=120, isDefault }` |
| PUT/DELETE | `/llm-endpoints/{id}` | DELETE → `409 IN_USE` if analyses reference it |
| POST | `/llm-endpoints/{id}/test` | sends a trivial prompt → `{ ok, message, latencyMs }` |
| GET/POST | `/prompts` | `{ name, content, isDefault }`. `content` must contain `{{errorLog}}` (else `400`) |
| PUT/DELETE | `/prompts/{id}` | DELETE → `409 IN_USE` if analyses reference it |

```
LlmEndpointDto   { id, name, baseUrl, model, timeoutSec, isDefault, hasSecret, createdAt, updatedAt }  -- no apiKey
PromptTemplateDto { id, name, content, isDefault, createdAt, updatedAt }
```
Setting `isDefault=true` on one clears it on the others.

Rules for LLM endpoints & prompts:
- `name` must be unique within each resource → `409 DUPLICATE_NAME` (`details: { field: "name" }`).
- `apiKey` is write-only. On PUT, a blank or absent value keeps the stored key. Send `clearApiKey: true` to remove
  a stored key (e.g. for a local LLM with no auth). `apiKey` and `clearApiKey: true` together → `400 VALIDATION_FAILED`
  on `fields.apiKey`.
- A prompt `content` without `{{errorLog}}` → `400 VALIDATION_FAILED` with the error on `fields.content`.
- `baseUrl`: a trailing `/` is removed. A `baseUrl` ending in `/v1` → `400` on `fields.baseUrl` ("Leave out /v1").
  `timeoutSec` must be 1–3600.
- `POST /llm-endpoints/{id}/test` always returns `200 { ok, message, latencyMs }`. Connection, auth, timeout and
  bad-response failures give `ok:false` with a specific message. Only an unknown id returns `404`.

Placeholders: `{{errorLog}}` (required), `{{serverName}}`, `{{errorTime}}`, `{{exceptionClass}}`.

## Analyses
| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/error-logs/{id}/analyses` | `{ llmEndpointId, promptTemplateId }` | `202 AnalysisDto(PENDING)` (`409` unless CONFIRMED/ANALYZED) |
| GET | `/analyses/{id}` | | `AnalysisDto` |
| GET | `/error-logs/{id}/analyses` | | `AnalysisDto[]` (history, newest first) |

```
AnalysisDto { id, errorLogId, llmEndpointId, llmEndpointName, promptTemplateId, promptTemplateName,
              status: PENDING|DONE|FAILED, reply?, errorMessage?, createdAt, finishedAt? }
```

Analysis rules:
- `llmEndpointId` and `promptTemplateId` are both **required**. The UI preselects the defaults.
  An unknown id → `404 NOT_FOUND`.
- DONE moves the error log to ANALYZED, but only if the status machine still allows it. A late reply never revives
  a TERMINATED log. FAILED leaves the error-log status unchanged.
- Analyses left PENDING across a restart are marked FAILED at startup.
- The template is rendered in one pass, so `{{…}}` inside the log text is left alone. `{{exceptionClass}}` is
  `(none)` when it is null.

LLM request sent by the backend (OpenAI-compatible):
`POST {baseUrl}/v1/chat/completions { model, messages:[{role:"user", content: renderedPrompt}] }`

## MCP (not REST, for reference)
`/mcp` tools: `list_dev_required_errors`, `get_error_log`, `terminate_error`. See design.md §8.

As built (B7): Streamable HTTP at `/mcp`, server name `logboard`. Every request needs
`Authorization: Bearer <LOGBOARD_MCP_TOKEN>` (constant-time compare). Anything else → `401`. Tool failures come back as
tool errors (`isError=true`) with instructions for the agent.

| Tool | Input | Output |
|---|---|---|
| `list_dev_required_errors` | `serverName?` (exact, case-insensitive), `limit?` 1–100 (default 20) | `[{id, serverName, exceptionClass, exceptionMessage, createdAt (UTC), note}]`, DEV_REQUIRED only, **oldest first** (work queue) |
| `get_error_log` | `errorId` (`YYYYMMDD-NNN`) | `{id, server{name, host}, errorTime (server tz offset), exceptionClass, message, status, userNote, croppedLog (whole crop, ≤ maxLines), latestAnalysis{prompt, reply} (newest **DONE** analysis) \| null}` |
| `terminate_error` | `errorId`, `resolution` (both required) | `{id, status:"TERMINATED"}`. Only from DEV_REQUIRED. Sets `resolvedBy=AGENT` |

Claude Code: `claude mcp add --transport http logboard http://localhost:8080/mcp --header "Authorization: Bearer $LOGBOARD_MCP_TOKEN"`
