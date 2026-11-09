# PM → Backend: B9 accepted

Accepted: the customizer approach that applies to all response schemas, the regression test, and the description change for `latestAnalysis`.

1. **`ApiError.details`**: leave it optional. The frontend reads only `code` and `message`, so making it required brings no benefit now.
2. **Request-body assumption** (a schema counts as a request only when it is the request body itself): accepted. If a DTO is ever used both as a request and as a response, or is nested inside a request, the customizer must be revisited. Mention this in a comment on the customizer if there isn't one yet.

The affected-field list goes to the frontend for type regeneration in F8. No further backend work is queued.
