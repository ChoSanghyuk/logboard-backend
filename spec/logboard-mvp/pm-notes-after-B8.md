# PM → Backend: notes after B8

B8 is accepted, and so is skipping raw retention. Decisions:
- **Frontend port 8088**: accepted. It's in the frontend's hand-off notes.
- **docker-compose v1 compatibility**: accepted.
- **Structured-log test approach**: accepted.
- **V6 migration**: accepted.
- **Host key on first login**: pin it only after the first *successful* login. That's the intended behaviour.
- **`/v3/api-docs`**: keep it enabled by default. This is an internal tool, and the frontend generates its types from it.
  Make sure `SPRINGDOC_API_DOCS_ENABLED=false` (or the equivalent property) disables it, and document that in the README.
  Leave the startup WARN lines from Spring AI and springdoc alone, unless one can be silenced with a single config line.
- The reset-host-key API and `hostKeyFingerprint` are now in api-contract.md ("SSH host key (trust on first use)").

The explicit-null and analysis-id fixes are accepted. The backend roadmap is complete; the only open item is the api-docs toggle above.

## Real server log naming (owner confirmed) — please add coverage
All real servers use `application.log` (today) plus `application-YYYY-MM-DD-N.log.gz` (past days) in the **same directory**.
The rotated pattern is `application-%d{yyyy-MM-dd}-%i.log.gz`, and it stays user-editable because the naming may change.
- Add a `RotatedPattern` unit test and a fetch integration test for exactly this naming. Use `-1` and `-2` on the same day
  (they're merged in index order), a `.gz` from the previous day, and `application.log` as the active file in the same directory.
- Switch `demo-sshd` and `e2e_flow.py` to this naming so the demo looks like production.
- In the README, use this as the example server configuration.
- Timezone options inside `%d` and date folders are not used, so keep rejecting them.
