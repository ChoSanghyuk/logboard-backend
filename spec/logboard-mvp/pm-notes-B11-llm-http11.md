# PM → Backend: B11, LLM calls fail against vLLM because of the HTTP/2 upgrade (bug, small)

## Symptom
`POST /api/llm-endpoints/{id}/test` against a real vLLM server returns ok=false:
```
LLM endpoint returned HTTP 400: {"error":{"message":"1 validation error:\n {'type': 'missing', 'loc': ('body',), 'msg': 'Field required', 'input': None} ... POST /v1/chat/completions ...
```
vLLM (uvicorn) receives the request but sees an empty body.

## Root cause (confirmed by the owner with curl)
`LlmClient` uses `java.net.http.HttpClient`, whose default version is HTTP/2. On plain `http://` URLs it adds the h2c upgrade
headers (`Connection: Upgrade, HTTP2-Settings`, `Upgrade: h2c`, `HTTP2-Settings`). uvicorn does not support h2c, and on such a
request it drops the body.
- A plain curl POST with the same JSON works.
- A curl POST with chunked transfer encoding works, so chunked bodies are **not** the problem.
- A curl POST with the h2c upgrade headers fails with the same error.

The owner's LLM server supports only plain `http://` (no TLS, no HTTP/2).

## Fix
- In `LlmClient`, build the client with `.version(HttpClient.Version.HTTP_1_1)`, so it never sends the h2c upgrade headers.
  Keep the change limited to this; no new config option.
- Test: in `LlmClientTest`, add a WireMock check that the request to `/v1/chat/completions` has no `Upgrade` header
  (and still carries the JSON body). Today's WireMock tests pass because WireMock accepts the upgrade, which is why this slipped through.
- The build must stay green.
- When done: update todo.md, and reply with the changed files and test results.
