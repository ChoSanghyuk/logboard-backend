# Logboard backend

Spring Boot 4.1 · Java 21 · PostgreSQL 16 + Flyway · Apache MINA SSHD · Spring AI MCP server.
Pulls exception logs from Java servers over SFTP, crops the stack trace, asks an LLM, and serves a REST API (`/api`,
OpenAPI at `/v3/api-docs`) plus an MCP server (`/mcp`) for code agents. Code map: [`structure.md`](structure.md).
Specs: `spec/logboard-mvp/`.

## Environment variables

| Variable | Required | Default | Meaning |
|----------|----------|---------|---------|
| `LOGBOARD_CRYPTO_MASTER_KEY` | yes | – (dev profile: sample) | Encrypts stored secrets (SSH passwords/keys, LLM apiKeys) with AES-GCM. See the warning below. |
| `LOGBOARD_MCP_TOKEN` | yes, **≥ 32 chars** | – (dev profile: sample) | Bearer token for `/mcp`. Generate: `openssl rand -hex 32`. The app refuses to start if it is missing or shorter. |
| `LOGBOARD_DB_URL` | no | `jdbc:postgresql://localhost:5434/logboard` | JDBC URL |
| `LOGBOARD_DB_USERNAME` / `LOGBOARD_DB_PASSWORD` | no | `logboard` / `logboard` | DB credentials |
| `LOGBOARD_STORAGE_ROOT` | no | `./data` (`/data` in Docker) | Raw log copies (`raw/{serverId}/…`) and crop files (`errors/{ID}.log`) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | no | plain text | `ecs`, `logstash` or `gelf` for JSON logs (compose uses `ecs`) |
| `SPRINGDOC_API_DOCS_ENABLED` | no | `true` | `false` removes the OpenAPI document `/v3/api-docs` (the frontend generates its types from it, so keep it on where types are generated) |

> **⚠ Master key.** Do not change `LOGBOARD_CRYPTO_MASTER_KEY` after secrets are stored. There is no re-encryption in
> the MVP: with another key every stored SSH password, private key and LLM apiKey becomes unreadable (connection tests
> and fetches fail with a decryption error) and must be re-entered in the UI. Keep the key in your secret store and
> back it up together with the database.

Secrets are never returned by the API (`hasSecret` only) and are masked in logs (known values plus `Bearer …`,
`password=…`, `"apiKey":"…"` and PEM private key patterns).

## Run

```bash
# Full stack in Docker (from the project root /home/lomon/workspace/logboard)
docker-compose up -d --build                     # postgres, backend :8080, frontend :8088 (needs the F8 frontend image)
docker-compose --profile demo up -d --build      # + demo SSH server with sample logs + stub LLM
docker-compose --profile demo up -d --build postgres backend demo-sshd demo-llm   # backend only
python3 "Backend Developer/demo/e2e_flow.py"     # full flow: server → search → crop → analyze → MCP terminate

# Local development (backend only)
docker-compose -f docker-compose.dev.yml up -d   # Postgres on localhost:5434
./gradlew bootRun                                # profile "dev": sample master key/token, CORS for localhost:5173
```

**docker-compose 1.29 recreate bug**: on newer Docker versions, `up -d --build` over existing containers crashes with
`KeyError: 'ContainerConfig'`. Run `docker-compose down` first (or `docker-compose rm -sf <service>` for one service).
Compose v2 (`docker compose …`, same arguments) is supported but optional.

The compose file ships **sample** secrets; override them via environment variables or a `.env` file next to
`docker-compose.yml` before using real servers.

**Demo profile** (`demo-sshd`, `demo-llm`): register the server as host `demo-sshd`, port `2222`, user `demo`,
password `demo-password`, logPath `/logs/application.log`, rotatedPattern `application-%d{yyyy-MM-dd}-%i.log.gz`, pattern
`SPRING_BOOT_3`, and search `2026-11-08T14:32:00+09:00` (NPE at 14:31:58; more exceptions at 14:30:41 and 14:33:10;
one in the rotated `application-2026-11-07-2.log.gz` at 2026-11-07 23:58:45). The LLM endpoint is `http://demo-llm:8080`, model `demo-model`, no
apiKey. Host ports: SSH 2222, stub LLM 8089.

## Test

```bash
./gradlew test     # needs Docker: Testcontainers (postgres:16-alpine, linuxserver/openssh-server), WireMock in-process
```

## Example server configuration

Our servers write today's log to `application.log` and keep past days as `application-YYYY-MM-DD-N.log.gz` in the
same directory:

```json
{ "logPath": "/var/log/myapp/application.log",
  "rotatedPattern": "application-%d{yyyy-MM-dd}-%i.log.gz",
  "logPatternKey": "SPRING_BOOT_3", "timezone": "Asia/Seoul", "charset": "UTF-8" }
```

A search reads the rotated files whose date overlaps the window (same day: `-1`, `-2`, … in index order), then
`application.log`. The pattern is editable if the naming changes (`%d{<java date format>}` once, optional `%i`,
optional `.gz`). Timezone options inside `%d` (`%d{…, UTC}`) and date folders (`%d` in the directory) are rejected.

## SSH host keys (trust on first use)

The first successful login stores the server's host-key fingerprint (`hostKeyFingerprint` on the server, `SHA256:…`).
Later connections whose key differs fail before any credentials are sent ("Host key of … changed …"). After a
legitimate change (server reinstalled) reset it with `POST /api/servers/{id}/reset-host-key`; the next test or search
trusts the new key. Changing a server's host or port forgets the stored fingerprint.

## Tuning analysis concurrency

LLM analyses run on Spring Boot's task executor (default: **8 threads, unbounded queue**). Analyses beyond that wait
in the queue as `PENDING`. Tune with standard properties, e.g. as environment variables:

```bash
SPRING_TASK_EXECUTION_POOL_MAX_SIZE=16      # spring.task.execution.pool.max-size
SPRING_TASK_EXECUTION_POOL_CORE_SIZE=16     # spring.task.execution.pool.core-size
SPRING_TASK_EXECUTION_POOL_QUEUE_CAPACITY=100
```

Analyses still `PENDING` when the app stops are marked `FAILED` at the next start.

## MCP (code agents)

Streamable HTTP at `http://<host>:8080/mcp` (through the frontend's nginx: `http://<host>:8088/mcp`), server name
`logboard`, header `Authorization: Bearer $LOGBOARD_MCP_TOKEN` (otherwise `401`). Tools: `list_dev_required_errors`,
`get_error_log`, `terminate_error` (see `spec/logboard-mvp/api-contract.md`, "MCP").

- **Claude Code**:
  `claude mcp add --transport http logboard http://localhost:8080/mcp --header "Authorization: Bearer $LOGBOARD_MCP_TOKEN"`
  or in `.mcp.json`:
  ```json
  { "mcpServers": { "logboard": { "type": "http", "url": "http://localhost:8080/mcp",
    "headers": { "Authorization": "Bearer ${LOGBOARD_MCP_TOKEN}" } } } }
  ```
  Then `/mcp` in Claude Code shows `logboard` connected.
- **MCP Inspector**: `npx @modelcontextprotocol/inspector` → Transport *Streamable HTTP*, URL `http://localhost:8080/mcp`,
  header `Authorization: Bearer <token>` → Connect → Tools → List (3 tools). A wrong token must fail with 401.
- **curl**: see `demo/e2e_flow.py` (initialize → `Mcp-Session-Id` header → `tools/call`).
