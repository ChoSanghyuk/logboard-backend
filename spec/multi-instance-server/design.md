# Feature: Multi-instance servers (redundant WAS) — Design

Status: CONFIRMED (owner, 2026-11-11; M1 changed: credentials are per instance) · Author: PM · 2026-11-11
Base: `../logboard-mvp/design.md`, `../logboard-mvp/api-contract.md`. This document lists **only the changes**.

## 1. Goal

Today an enrolled server has exactly one `host` / `sshPort` / `logPath`. Some services run redundantly on
several machines, so one logical server (e.g. `order-api`) needs **several (host, port, log path) groups**.
When the user searches for an error on such a server, Logboard fetches the logs of **all** of them and
finds the exception in whichever one has it.

Term: an **instance** = one `(host, sshPort, logPath, username, auth)` group of a server.

## 2. Assumptions (confirmed 2026-11-11)

| # | Assumption |
|---|------------|
| M1 | Per instance: `host`, `sshPort`, `logPath`, **`username`, `authType`, secret (password or private key)**. Shared per server: `name`, `rotatedPattern`, log pattern, `timezone`, `charset`. (Redundant nodes run the same app, but logins may differ.) |
| M2 | Every search reads **all** active instances. The user does not pick an instance. |
| M3 | Logs of different instances are **never merged** into one file. Each instance is scanned separately, the candidates of all instances are pooled, and the crop is taken from the one instance that holds the chosen anchor. (Merging by time would put unrelated requests from other nodes into the context lines.) |
| M4 | **Partial failure is allowed**: if at least one instance was fetched, the search goes on. The failed instances are listed on the error log as a warning (the error may be on the failed node). Only if **all** instances fail → `502 FETCH_FAILED`. |
| M5 | A server has 1–20 instances. A single-instance server behaves exactly as today. |
| M6 | SSH host key trust (TOFU) is **per instance**: each host:port has its own fingerprint and its own reset. |

## 3. Domain model changes

```
Server(id, name, rotatedPattern?, logPatternKey? | customLogRegex?,
       timezone, charset, deletedAt?, createdAt, updatedAt)
       -- host, sshPort, logPath, username, authType, secretEnc, hostKeyFingerprint MOVED to ServerInstance

ServerInstance(id, serverId, position, host, sshPort, logPath, username, authType[PASSWORD|KEY], secretEnc,
               hostKeyFingerprint?,
               deletedAt?, createdAt, updatedAt)                       -- NEW
   - (serverId, host, sshPort, logPath) unique among active instances of a server
   - position = order given by the user (display order, tie-break order)
   - soft-deleted when error logs reference it, hard-deleted otherwise (same rule as Server)

ErrorLog  + instanceId (NOT NULL, FK)          -- instance whose raw file holds the current anchor/crop
          + rawFiles (JSON: instanceId → raw path, totalLines)   -- one raw copy per fetched instance
          + fetchFailures (JSON: [{instanceId, host, sshPort, logPath, message}], [] if none)
          candidates JSON: each candidate also carries instanceId
          rawFilePath / totalLines / anchorLine / cropStart/End refer to the instance in instanceId
```

Migration (Flyway V7): create `server_instances`; for every existing server insert one instance (position 0) from its
`host`, `ssh_port`, `log_path`, `username`, `auth_type`, `secret_enc`, `host_key_fingerprint` (keep `deleted_at` aligned for soft-deleted servers);
backfill `error_logs.instance_id`, `raw_files`, `fetch_failures = []`, and `instanceId` in existing candidates JSON;
then drop the 7 moved columns from `servers`. Old error logs must still open, re-crop and analyze unchanged.

## 4. Search & crop changes (replaces design §6 steps 2–7 for multi-instance)

1. `POST /api/error-logs/search {serverId, errorTime, windowMin}` — unchanged request.
2. For **each active instance, in parallel**: resolve files (active `logPath` + rotated files from the server's
   `rotatedPattern`; a pattern without a directory prefix uses the directory of that instance's `logPath`), copy
   to `{root}/raw/{serverId}/{instanceId}/{yyyyMMddHHmmss}-{file}`, decompress, merge **that instance's** files
   chronologically (as today). The search waits for all instances; the per-connection timeout stays as today.
3. Instances that fail are collected as `fetchFailures` (same clear messages as today's `FETCH_FAILED`,
   including the "Host key … changed" message). All failed → `502 FETCH_FAILED` with every failure in `details`.
4. Each fetched raw file is parsed and scanned separately (same rules as design §6.4–6.5). Every candidate is
   tagged with its `instanceId`.
5. Anchor = the candidate closest to `errorTime` over **all** instances. Tie → lower instance `position`,
   then lower line number.
6. Crop from the anchor's instance raw file (same before/after/maxLines rules). Save `{root}/errors/{ID}.log`.
7. No candidate in any instance → `404 NO_EXCEPTION_FOUND`; `details` also lists the searched and the failed
   instances, so the user can see if a node was not read. No ID is created.

Re-crop (`PATCH crop`, status `CROPPED`): expand works within the current instance's raw file. Switching to a
candidate of another instance moves the crop (and `instanceId`) to that instance's raw file. No re-download.

## 5. Other behavior

- **Connection test** tests all instances (in parallel) and returns one result per instance; overall `ok` only if all are ok.
- **Host key reset** is per instance. Changing an instance's host or port forgets its fingerprint.
- **Error list / detail** show which instance the error came from (`host:port`).
- **MCP** `get_error_log`: `server.host` becomes the host of the error's instance; add `server.logPath`
  (the instance's log path) so the agent knows which node/file it came from. Other tools unchanged.
- **Prompt placeholders**: add `{{host}}` (instance host). Optional, existing templates are unaffected.

## 6. Screens

```
/servers  add/edit modal                          /errors/:id header
┌ Server ──────────────────────────────────────┐  ┌───────────────────────────────────────────────┐
│ Name [order-api]                              │  │ 20261111-004 · order-api · 10.0.1.12:22 · CROPPED│
│ Instances                                     │  │ ⚠ Logs of 10.0.1.13:22 could not be read:       │
│  #1 Host [10.0.1.11] Port [22] Log [/app/…/application.log] [×] │  "Connection timed out". The error  │
│     User [app] Auth (●Password ○Key) Secret [••• stored]        │  may be on that node.               │
│  #2 Host [10.0.1.12] Port [22] Log [/app/…/application.log] [×] │                                     │
│     User [app2] Auth (○Password ●Key) Key [••• stored]          │                                     │
│  [+ Add instance]                             │  │ Candidates:                                    │
│ Rotated pattern / log pattern / tz / charset  │  │ (● 14:31:58 NPE · 10.0.1.12) (○ 14:33:10 · .11)│
└───────────────────────────────────────────────┘  └───────────────────────────────────────────────┘
/servers table: Host column → "10.0.1.11:22 +1" (tooltip lists all) · host key column per instance
Test connection → per-instance result list (✓ / ✗ + message, "Reset host key" on the failed one)
```

## 7. Out of scope

Per-instance log patterns / timezone / charset, choosing a subset of instances for a search, merging instance logs
into one timeline, auto-discovery of instances.
