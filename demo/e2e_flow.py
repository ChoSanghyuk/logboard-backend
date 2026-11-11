#!/usr/bin/env python3
"""Full Logboard flow against `docker-compose --profile demo up` (stdlib only).

add server with 2 instances (demo-sshd, demo-sshd-2) → /test (per instance) → search in rotated .gz (discard)
→ search found on instance 2 → switch candidate to instance 1 (discard) → partial failure (instance down → fetchFailures)
→ search → expand crop → confirm → analyze (stub LLM) → dev-required
→ MCP: 401 without token, list_dev_required_errors → get_error_log → terminate_error → REST check.

Usage: python3 e2e_flow.py [--base http://localhost:8080] [--token $LOGBOARD_MCP_TOKEN]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

COMPOSE_SAMPLE_TOKEN = "compose-sample-mcp-token-change-me-0123456789"


def http(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json",
                                                                         **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            return res.status, dict(res.headers), res.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def api(base, method, path, body=None, expect=200):
    status, _, text = http(method, base + path, body)
    if status != expect:
        sys.exit(f"FAIL {method} {path}: expected {expect}, got {status}: {text}")
    return json.loads(text) if text else None


def step(msg):
    print(f"✔ {msg}")


class Mcp:
    """Minimal Streamable HTTP MCP client."""

    def __init__(self, url, token):
        self.url, self.token, self.session, self.next_id = url, token, None, 1

    def _post(self, payload):
        headers = {"Accept": "application/json, text/event-stream", "Authorization": f"Bearer {self.token}"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        status, res_headers, text = http("POST", self.url, payload, headers)
        if status not in (200, 202):
            sys.exit(f"FAIL MCP {payload.get('method')}: HTTP {status}: {text}")
        self.session = self.session or {k.lower(): v for k, v in res_headers.items()}.get("mcp-session-id")
        for line in text.splitlines():  # SSE ("data: {...}") or plain JSON
            line = line[5:] if line.startswith("data:") else line
            if line.strip().startswith("{"):
                return json.loads(line)
        return None

    def request(self, method, params):
        msg = {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params}
        self.next_id += 1
        res = self._post(msg)
        if "error" in res:
            sys.exit(f"FAIL MCP {method}: {res['error']}")
        return res["result"]

    def initialize(self):
        info = self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                           "clientInfo": {"name": "e2e_flow", "version": "1"}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        return info

    def call(self, tool, args):
        result = self.request("tools/call", {"name": tool, "arguments": args})
        text = result["content"][0]["text"]
        if result.get("isError"):
            sys.exit(f"FAIL tool {tool}: {text}")
        return json.loads(text)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--token", default=os.environ.get("LOGBOARD_MCP_TOKEN", COMPOSE_SAMPLE_TOKEN))
    a = p.parse_args()
    base, suffix = a.base.rstrip("/"), time.strftime("%H%M%S")

    node1 = {"host": "demo-sshd", "sshPort": 2222, "username": "demo", "authType": "PASSWORD",
             "password": "demo-password", "logPath": "/logs/application.log"}
    node2 = {"host": "demo-sshd-2", "sshPort": 2222, "username": "demo2", "authType": "PASSWORD",
             "password": "demo2-password", "logPath": "/logs/application.log"}
    common = {"rotatedPattern": "application-%d{yyyy-MM-dd}-%i.log.gz", "logPatternKey": "SPRING_BOOT_3"}
    server = api(base, "POST", "/api/servers", {"name": f"demo-app-{suffix}", "instances": [node1, node2], **common},
                 expect=201)
    inst1, inst2 = (i["id"] for i in server["instances"])
    assert all(i["hasSecret"] for i in server["instances"]), server
    step(f"server {server['id']} created with instances {inst1} (demo-sshd), {inst2} (demo-sshd-2)")
    test = api(base, "POST", f"/api/servers/{server['id']}/test")
    assert test["ok"] and [r["instanceId"] for r in test["results"]] == [inst1, inst2], test
    listed = next(s for s in api(base, "GET", "/api/servers") if s["id"] == server["id"])
    assert all(i["hostKeyFingerprint"].startswith("SHA256:") for i in listed["instances"]), listed
    step("/test ok: " + " | ".join(r["message"] for r in test["results"]))

    old = api(base, "POST", "/api/error-logs/search", {
        "serverId": server["id"], "errorTime": "2026-11-07T23:58:45+09:00", "windowMin": 5}, expect=201)
    assert old["exceptionClass"] == "java.lang.IllegalStateException", old  # in application-2026-11-07-2.log.gz
    assert old["instance"]["id"] == inst1 and old["fetchFailures"] == [], old
    assert api(base, "POST", f"/api/error-logs/{old['id']}/discard")["status"] == "DISCARDED"
    step(f"search in rotated -1/-2 .gz → {old['id']} {old['exceptionClass']}, discarded")

    # the exception only exists on node 2; node 1's NPE (14:31:58) is in the 40 min window too
    two = api(base, "POST", "/api/error-logs/search", {
        "serverId": server["id"], "errorTime": "2026-11-08T15:05:00+09:00", "windowMin": 40}, expect=201)
    assert two["instance"]["id"] == inst2 and two["instance"]["host"] == "demo-sshd-2", two
    assert two["exceptionClass"] == "java.net.SocketTimeoutException", two
    assert {c["instanceId"] for c in two["candidates"]} == {inst1, inst2}, two["candidates"]
    step(f"search → {two['id']} {two['exceptionClass']} on instance {inst2} ({two['instance']['host']}), "
         f"candidates {[(c['host'], c['anchorLine']) for c in two['candidates']]}")
    npe = next(c for c in two["candidates"] if c["instanceId"] == inst1 and c["exceptionClass"] == "java.lang.NullPointerException")
    switched = api(base, "PATCH", f"/api/error-logs/{two['id']}/crop",
                   {"anchorLine": npe["anchorLine"], "instanceId": inst1})
    assert switched["instance"]["id"] == inst1 and switched["exceptionClass"] == "java.lang.NullPointerException", switched
    assert api(base, "POST", f"/api/error-logs/{two['id']}/discard")["status"] == "DISCARDED"
    step(f"switched candidate to instance {inst1} line {npe['anchorLine']} (crop moved), discarded")

    # partial failure: a node that is down (nothing listens on demo-sshd-2:2299) is reported, the search goes on
    partial = api(base, "POST", "/api/servers", {"name": f"demo-partial-{suffix}", **common, "instances": [
        node1, {**node2, "sshPort": 2299}]}, expect=201)
    down = partial["instances"][1]["id"]
    test = api(base, "POST", f"/api/servers/{partial['id']}/test")
    assert not test["ok"] and test["results"][0]["ok"] and not test["results"][1]["ok"], test
    part = api(base, "POST", "/api/error-logs/search", {
        "serverId": partial["id"], "errorTime": "2026-11-08T14:32:00+09:00", "windowMin": 5}, expect=201)
    assert [f["instanceId"] for f in part["fetchFailures"]] == [down], part["fetchFailures"]
    assert part["exceptionClass"] == "java.lang.NullPointerException", part
    assert api(base, "POST", f"/api/error-logs/{part['id']}/discard")["status"] == "DISCARDED"
    step(f"instance down → 201 {part['id']} with fetchFailures: {part['fetchFailures'][0]['message']}")
    status, _, text = http("POST", base + "/api/error-logs/search", {
        "serverId": partial["id"], "errorTime": "2026-11-08T10:00:00+09:00", "windowMin": 1})
    body = json.loads(text)
    assert status == 404 and len(body["details"]["searchedInstances"]) == 1 \
        and len(body["details"]["failures"]) == 1, (status, body)
    step("no exception → 404 NO_EXCEPTION_FOUND with searchedInstances + failures")

    log = api(base, "POST", "/api/error-logs/search", {
        "serverId": server["id"], "errorTime": "2026-11-08T14:32:00+09:00", "windowMin": 5}, expect=201)
    assert log["status"] == "CROPPED" and log["exceptionClass"] == "java.lang.NullPointerException", log
    assert log["instance"]["id"] == inst1 and log["fetchFailures"] == [], log
    step(f"search → {log['id']} {log['exceptionClass']} crop {log['crop']['startLine']}-{log['crop']['endLine']}"
         f" anchor {log['crop']['anchorLine']}, candidates {[c['anchorLine'] for c in log['candidates']]}")
    log = api(base, "PATCH", f"/api/error-logs/{log['id']}/crop", {"expandBefore": log["crop"]["expandStep"]})
    step(f"expanded crop → {log['crop']['startLine']}-{log['crop']['endLine']} (maxLines {log['crop']['maxLines']})")
    log = api(base, "POST", f"/api/error-logs/{log['id']}/confirm")
    assert log["status"] == "CONFIRMED"
    step("confirmed")

    llm = api(base, "POST", "/api/llm-endpoints", {"name": f"demo-llm-{suffix}", "baseUrl": "http://demo-llm:8080",
                                                   "model": "demo-model"}, expect=201)
    assert api(base, "POST", f"/api/llm-endpoints/{llm['id']}/test")["ok"]
    prompt = api(base, "POST", "/api/prompts", {"name": f"demo-prompt-{suffix}",
                                                "content": "Explain this {{exceptionClass}} on {{serverName}} ({{host}}):\n{{errorLog}}"},
                 expect=201)
    analysis = api(base, "POST", f"/api/error-logs/{log['id']}/analyses",
                   {"llmEndpointId": llm["id"], "promptTemplateId": prompt["id"]}, expect=202)
    assert analysis["status"] == "PENDING"
    for _ in range(60):
        analysis = api(base, "GET", f"/api/analyses/{analysis['id']}")
        if analysis["status"] != "PENDING":
            break
        time.sleep(0.5)
    assert analysis["status"] == "DONE", analysis
    log = api(base, "GET", f"/api/error-logs/{log['id']}")
    assert log["status"] == "ANALYZED" and log["latestAnalysis"]["id"] == analysis["id"]
    step(f"analysis {analysis['id']} DONE → error log ANALYZED")

    log = api(base, "POST", f"/api/error-logs/{log['id']}/dev-required", {"note": "customer.name can be null"})
    assert log["status"] == "DEV_REQUIRED"
    step("dev-required")

    status, _, _ = http("POST", base + "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
                        {"Accept": "application/json, text/event-stream"})
    assert status == 401, status
    step("MCP without token → 401")
    mcp = Mcp(base + "/mcp", a.token)
    assert mcp.initialize()["serverInfo"]["name"] == "logboard"
    queue = mcp.call("list_dev_required_errors", {})
    assert log["id"] in [e["id"] for e in queue], queue
    detail = mcp.call("get_error_log", {"errorId": log["id"]})
    assert detail["latestAnalysis"]["reply"].startswith("## Cause") and "NullPointerException" in detail["croppedLog"]
    assert detail["server"] == {"name": server["name"], "host": "demo-sshd", "logPath": "/logs/application.log"}, detail
    done = mcp.call("terminate_error", {"errorId": log["id"],
                                        "resolution": "Validate customer.name in OrderService.place (commit abc1234)"})
    assert done == {"id": log["id"], "status": "TERMINATED"}, done
    step("MCP list → get → terminate")

    log = api(base, "GET", f"/api/error-logs/{log['id']}")
    assert log["status"] == "TERMINATED" and log["resolvedBy"] == "AGENT" and log["resolvedAt"], log
    step(f"REST: {log['id']} TERMINATED by AGENT at {log['resolvedAt']}")
    print("ALL OK")


if __name__ == "__main__":
    main()
