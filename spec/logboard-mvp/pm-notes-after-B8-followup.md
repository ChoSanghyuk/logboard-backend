# PM → Backend: follow-up accepted

The api-docs toggle check, the real-naming tests, the demo switch, and the `RotatedPattern` `{…}`-slash fix are accepted.

1. **Spring AI startup WARNs**: leave them. Don't raise the `PostProcessorRegistrationDelegate` level, because it would also hide warnings about our own beans.
2. **Old example in the docs**: api-contract.md and design.md now use `application-%d{yyyy-MM-dd}-%i.log.gz` as the example; your copies are synced.
   The frontend is switching its mocks and fixtures in F7. `app.%d{yyyy-MM-dd}.%i.log.gz` stays a valid pattern.

No further backend work is queued.
