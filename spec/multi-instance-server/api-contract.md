# Feature: Multi-instance servers — API contract changes (v2)

Status: CONFIRMED (2026-11-11) · Base: `../logboard-mvp/api-contract.md`. Only changes are listed; everything else stays.
This is a **breaking** change to server endpoints. Backend and frontend ship it together (the frontend regenerates
`schema.d.ts`). If the two disagree, raise it with the PM.

## Servers

```
ServerUpsert { name, instances: InstanceUpsert[]  (1–20, order = position),
               rotatedPattern?, logPatternKey | customLogRegex, timezone="Asia/Seoul", charset="UTF-8" }
InstanceUpsert { id?, host, sshPort=22, logPath, username, authType: PASSWORD|KEY, password?, privateKey? }
               -- id given  = update that instance (must belong to this server, else 400)
               -- id absent = new instance
               -- an existing instance missing from the list = removed (soft-deleted if error logs reference it)

ServerDto    = ServerUpsert with instances: InstanceDto[] (active only, by position) + { id, createdAt, updatedAt }
InstanceDto  { id, host, sshPort, logPath, username, authType, hasSecret, hostKeyFingerprint (nullable),
               createdAt, updatedAt }                       -- never password / privateKey
```
Removed from Server: top-level `host`, `sshPort`, `logPath`, `username`, `authType`, `password`, `privateKey`,
`hasSecret`, `hostKeyFingerprint`.

Validation (all `400 VALIDATION_FAILED`, field keys use bean-validation paths):
- `instances` empty or more than 20 → `fields.instances`.
- Bad instance field → `fields["instances[1].host"]`, `fields["instances[0].logPath"]`, …
- Same `(host, sshPort, logPath)` twice in one server → on the later one's `fields["instances[N].host"]`.
- An `id` that is not an active instance of this server → `fields["instances[N].id"]`.
- Changing an instance's `host` or `sshPort` clears its `hostKeyFingerprint`.
- Secrets are per instance, same rules as before applied to each instance: the secret matching `authType` is
  **required for a new instance** (no `id`) and when an instance's `authType` changes → `fields["instances[N].password"]`
  / `fields["instances[N].privateKey"]`. Otherwise a blank secret keeps that instance's stored one. Secrets are
  write-only and never returned.

| Method | Path | Change |
|--------|------|--------|
| POST | `/servers/{id}/test` | Response is now `200 ConnectionTestResult { ok, results: InstanceTestResult[] }`. `ok` = all results ok. Instances are tested in parallel |
| POST | `/servers/{id}/reset-host-key` | **Removed** |
| POST | `/servers/{id}/instances/{instanceId}/reset-host-key` | **New.** `200 ServerDto` (that instance's `hostKeyFingerprint: null`). `404` if the server or instance is unknown / soft-deleted |

```
InstanceTestResult { instanceId, host, sshPort, logPath, ok, message, logFileFound, logFileSize (nullable) }
```
(`message` rules are unchanged, incl. "Host key of {host}:{port} changed: …" and the trailing "Host key SHA256:…".)

## Error logs

```
InstanceRef   { id, host, sshPort, logPath }     -- also for soft-deleted instances
FetchFailure  { instanceId, host, sshPort, logPath, message }

ErrorLogSummary + { instance: InstanceRef }      -- the instance the crop comes from
ErrorLogDetail  + { fetchFailures: FetchFailure[] }   -- always present, [] if none
candidates: [{ instanceId, host, anchorLine, time, exceptionClass, message }]
```
- `crop.*` line numbers and `totalLines` refer to the raw copy of `instance`.
- `PATCH /error-logs/{id}/crop`: body is now `{ expandBefore?, expandAfter?, anchorLine?, instanceId? }`.
  - `anchorLine` switches candidate. `instanceId` says which instance's candidate; when absent, the current `instance`
    is used (so old clients keep working on single-instance servers). (`anchorLine`, `instanceId`) must match a
    candidate, else `400 VALIDATION_FAILED` on `fields.anchorLine`. The UI always sends both.
  - `instanceId` without `anchorLine` → `400 VALIDATION_FAILED` on `fields.instanceId`.
  - Expand works within the current instance.
- `POST /error-logs/search`:
  - `201` even if some instances failed; they are in `fetchFailures`.
  - `502 FETCH_FAILED` only when every instance failed: `details: { failures: FetchFailure[] }`; `message` joins them.
  - `404 NO_EXCEPTION_FOUND`: `details: { errorTime, windowMin, from, to, searchedInstances: InstanceRef[], failures: FetchFailure[] }`.
- `GET /error-logs` filters unchanged (`serverId` covers all its instances).
- Settled with backend B12 (2026-11-11):
  - `404` `searchedInstances` = only the instances that were **actually read**; failed ones are only in `failures`.
  - `candidates` are ordered by instance position, then line number.
  - `502` `message` = the failures joined as `host:port: message`, separated by `; `.

## Prompts
New optional placeholder `{{host}}` = host of the error log's instance.

## MCP
`get_error_log`: `server { name, host, logPath }` — `host`/`logPath` of the error log's instance. Other tools unchanged.
