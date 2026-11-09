# PM → Backend: B9, OpenAPI schema for nullable fields (small)

The frontend found drift in `/v3/api-docs` after regenerating its types. The data sent is correct; only the schema is wrong.
1. `ErrorLogDetail.latestAnalysis` is described as "absent if none". Change it to "`null` if none (always present)".
2. Nullable response fields (`note`, `resolution`, `resolvedBy`, `resolvedAt`, `latestAnalysis`, `exceptionClass`,
   `hostKeyFingerprint`, etc.) are not listed in `required`, so they generate as `field?: T | null`. Following the contract rule
   (always present, sent as `null`), mark them **required + nullable** in response DTO schemas, for example with a springdoc
   customizer or `@Schema(requiredMode = REQUIRED, nullable = true)`. Request DTOs are unchanged.
- Verify: add a test that checks `/v3/api-docs` for a few representative fields. The build must stay green.
- When done: update todo.md/structure.md and reply with the list of affected DTOs/fields so the frontend can regenerate in F8.
