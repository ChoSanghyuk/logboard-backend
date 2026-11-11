# PM → Backend: Multi-instance servers (B12)

Read first: `design.md`, `api-contract.md` in this folder (changes on top of `../logboard-mvp/`).
Assumptions M1–M6 in design §2 are confirmed by the owner. Note M1: credentials (username, auth type, secret) are **per instance**. Ask the PM if anything is unclear; do not diverge silently.

## B12 — Multi-instance servers
- [x] Flyway `V7`: `server_instances` table + data migration from `servers` (design §3); `error_logs.instance_id`,
      `raw_files`, `fetch_failures`; `instanceId` added to existing candidates JSON; drop moved `servers` columns
- [x] `ServerInstance` entity (incl. username, authType, encrypted secret); `ServerUpsert`/`ServerDto` with `instances`
      (create/update/remove by `id`, soft delete when referenced, per-instance secret rules, validation and field keys per
      contract, host/port change clears fingerprint). `SecretMasker` must still mask every instance's secret
- [x] `SshConnector` / host-key TOFU per instance; `POST /servers/{id}/instances/{instanceId}/reset-host-key`;
      remove `/servers/{id}/reset-host-key`
- [x] `/test` → all instances in parallel → `{ ok, results[] }`
- [x] Fetch all active instances in parallel (bounded, no DB transaction held), one raw file per instance under
      `{root}/raw/{serverId}/{instanceId}/`. Partial failure → `fetchFailures`; all failed → 502 with `details.failures`
- [x] Scan each raw file separately, pool candidates with `instanceId`, anchor = closest over all (tie: position, line)
- [x] `PATCH crop` with `instanceId` (switch moves crop to that instance's raw file); 404 details per contract
- [x] DTOs: `instance` on summary/detail, `fetchFailures`, candidate `instanceId`/`host`; OpenAPI required/nullable rules (B9)
- [x] `{{host}}` placeholder; MCP `get_error_log` `server.host`/`server.logPath` from the instance
- [x] Demo stack: a second sshd (`demo-sshd-2`) and a demo server with 2 instances; extend `e2e_flow.py`
      (anchor found on instance 2; one instance stopped → partial `fetchFailures`)
- [x] `structure.md`, README updated

## Verify
- Migration test: a V6 DB with servers + error logs → V7 → old error logs GET / re-crop / analyze still work.
- Integration (2 SSH containers): exception only on instance B → anchor on B; candidates from both → switch A↔B;
  expand stays in the instance; instance B down → 201 with 1 fetch failure; both down → 502 with 2 failures;
  no exception anywhere → 404 with `searchedInstances`.
- Server API: add / update / reorder / remove (hard vs soft) instances, all validation cases, per-instance secrets
  (required on new instance / authType change, blank keeps, never returned), per-instance test and reset.
- Integration: two instances with **different users / auth types** (password on A, key on B) are both fetched.
- `./gradlew build` green. Reply with changed files and test results; update todo.md.

## Done (Backend, 2026-11-11)
`./gradlew clean build` green: 209 tests, 0 failures. Every Verify item has a test: `V7MigrationIntegrationTest`,
`MultiInstanceSearchIntegrationTest` (2 SSH containers: alice/password, bob/ed25519 key), `ServerApiIntegrationTest`,
`SshConnectionIntegrationTest`. `e2e_flow.py` passes against the demo stack. The V7 upgrade was also run on a stack
created by the pre-B12 image: old logs GET / expand / switch / analyze OK.
Interpretations (tell the PM if any is wrong):
- 404 `searchedInstances` = instances that were read (fetched and scanned); instances that failed are in `failures`.
- `candidates` order: by instance position, then line (the contract does not say).
- The 502 `message` joins the failures as `host:port: message` with `; `.
- "Instance down" in e2e is an instance on `demo-sshd-2:2299` (nothing listens) rather than stopping the container.

