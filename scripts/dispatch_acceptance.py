#!/usr/bin/env python3
"""Server-agnostic acceptance check for formal MCP dispatch (create -> assign -> claim -> progress -> submit -> status).

Runs against ANY MCP endpoint that claims to implement the dispatch contract: the App-native
dispatch server (CI runs it there) and, after an approved deploy, the V5 platform endpoint.
It never creates channel messages; it only uses the formal task tools.

Environment (tokens are read from the environment only and are never printed):
  MCP_URL               e.g. https://<v5-host>/api/open/v1/mcp
  DISPATCHER_TOKEN      dispatcher-scoped credential (e.g. issued to agent "chatgpt")
  EXECUTOR_TOKEN        executor credential of EXECUTOR_ID
  EXECUTOR_ID           assignee agent id (e.g. grok-bot)
  OTHER_EXECUTOR_TOKEN  optional: a second executor (e.g. codex) used for the denial checks
  MCP_CA_FILE           optional: PEM to trust (pin a self-signed server). TLS is always verified.
  ACCEPT_FINAL_STATES   optional, comma list, default "completed"
  DISCOVERY_ONLY=1      optional: only check auth + tool discovery, create nothing

Prints PASS/FAIL lines; last line is ACCEPTANCE_OK on success. Exit code != 0 on any failure.
"""
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request

DISPATCH_TOOLS = {"create_task", "assign_task", "get_task_status"}
EXECUTOR_TOOLS = {"list_my_tasks", "claim_task", "report_progress", "submit_result"}


def env(name, required=True):
    v = os.environ.get(name, "")
    if required and not v:
        sys.exit(f"FAIL: environment variable {name} is required")
    return v


URL = env("MCP_URL")
SECRETS = [s for s in (os.environ.get(k, "") for k in ("DISPATCHER_TOKEN", "EXECUTOR_TOKEN", "OTHER_EXECUTOR_TOKEN")) if len(s) >= 8]
CTX = ssl.create_default_context(cafile=os.environ.get("MCP_CA_FILE") or None)
if os.environ.get("MCP_CA_FILE"):
    # A pinned self-signed certificate usually has no matching hostname for a bare IP; trust is the pin.
    CTX.check_hostname = False


def redact(text):
    for s in SECRETS:
        text = text.replace(s, "***")
    return text


def ok(label, value=""):
    print(redact(f"PASS: {label}{(' = ' + str(value)) if value != '' else ''}"), flush=True)


def fail(label, detail=""):
    print(redact(f"FAIL: {label}: {detail}"), flush=True)
    sys.exit(1)


class Client:
    def __init__(self, name, token):
        self.name, self.token, self.session, self.seq, self.tools = name, token, None, 0, {}

    def _post(self, payload, auth=True):
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": "2025-06-18",
        }
        if auth:
            headers["Authorization"] = "Bearer " + self.token
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        req = urllib.request.Request(URL, data=json.dumps(payload).encode(), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=20, context=CTX) as resp:
                sid = resp.headers.get("Mcp-Session-Id")
                if sid:
                    self.session = sid
                return resp.status, resp.headers.get("Content-Type", ""), resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get("Content-Type", ""), e.read().decode("utf-8", "replace")
        except (urllib.error.URLError, OSError) as e:
            fail(f"[{self.name}] connect {URL}", str(e))

    @staticmethod
    def _parse(ctype, body):
        if not body.strip():
            return None
        if "text/event-stream" in ctype:
            data = [l[5:].strip() for l in body.splitlines() if l.startswith("data:")]
            body = data[-1] if data else "{}"
        return json.loads(body)

    def rpc(self, method, params):
        self.seq += 1
        status, ctype, body = self._post({"jsonrpc": "2.0", "id": self.seq, "method": method, "params": params})
        if status >= 400:
            fail(f"[{self.name}] {method}", f"HTTP {status} {body[:200]}")
        return self._parse(ctype, body)

    def connect(self):
        r = self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                    "clientInfo": {"name": "dispatch-acceptance", "version": "1"}})
        if not r or "result" not in r:
            fail(f"[{self.name}] initialize", json.dumps(r)[:200])
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        r = self.rpc("tools/list", {})
        self.tools = {t["name"]: t for t in r["result"]["tools"]}
        return set(self.tools)

    def call(self, tool, args):
        """Returns (ok, payload). Tool errors, JSON-RPC errors and unknown tools all count as not ok."""
        r = self.rpc("tools/call", {"name": tool, "arguments": self.fit(tool, args)})
        if "error" in r:
            return False, r["error"]
        res = r.get("result", {})
        payload = res.get("structuredContent")
        if payload is None:
            text = "".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
            try:
                payload = json.loads(text)
            except ValueError:
                payload = {"text": text}
        if res.get("isError") or (isinstance(payload, dict) and payload.get("ok") is False):
            return False, payload
        return True, payload

    def fit(self, tool, args):
        """Adapts argument names to the server's published schema (App: message/result, V5: text/summary)."""
        schema = (self.tools.get(tool) or {}).get("inputSchema") or {}
        props = schema.get("properties")
        if not props:
            return {k.split("|")[0]: v for k, v in args.items() if v not in (None, "")}
        out = {}
        for key, value in args.items():
            if value is None or value == "":
                continue
            names = key.split("|")
            hit = next((n for n in names if n in props), None)
            if hit:
                out[hit] = value
        return out


def task_id_of(payload):
    t = payload.get("task") if isinstance(payload.get("task"), dict) else payload
    return t.get("task_id") or t.get("id")


def state_of(payload):
    t = payload.get("task") if isinstance(payload.get("task"), dict) else payload
    return str(t.get("state") or t.get("status") or "")


def main():
    executor_id = env("EXECUTOR_ID")
    dispatcher = Client("dispatcher", env("DISPATCHER_TOKEN"))
    executor = Client("executor", env("EXECUTOR_TOKEN"))
    other_tok = env("OTHER_EXECUTOR_TOKEN", required=False)
    other = Client("other-executor", other_tok) if other_tok else None
    finals = {s.strip() for s in os.environ.get("ACCEPT_FINAL_STATES", "completed").split(",") if s.strip()}
    run = f"{int(time.time())}-{os.getpid()}"

    # 1. Anonymous requests are refused.
    status, _, _ = Client("anonymous", "")._post({"jsonrpc": "2.0", "id": 0, "method": "ping"}, auth=False)
    if status not in (401, 403):
        fail("anonymous request refused", f"HTTP {status}")
    ok("anonymous request refused", status)

    # 2. Discovery is scoped by credential.
    d_tools = dispatcher.connect()
    missing = DISPATCH_TOOLS - d_tools
    if missing:
        fail("dispatcher discovers create_task/assign_task/get_task_status", f"missing {sorted(missing)}; has {sorted(d_tools)}")
    ok("dispatcher discovers dispatch tools", ",".join(sorted(DISPATCH_TOOLS)))
    for c in [executor] + ([other] if other else []):
        tools = c.connect()
        if tools & {"create_task", "assign_task"}:
            fail(f"[{c.name}] executor must not discover create/assign", sorted(tools & {"create_task", "assign_task"}))
        if EXECUTOR_TOOLS - tools:
            fail(f"[{c.name}] executor discovers executor tools", f"missing {sorted(EXECUTOR_TOOLS - tools)}")
        ok(f"[{c.name}] executor scope", "no create/assign; has list/claim/progress/submit")
    if os.environ.get("DISCOVERY_ONLY") == "1":
        print("ACCEPTANCE_OK")
        return

    # 3. Executor credentials cannot create or assign even when calling the tool by name.
    good, p = executor.call("create_task", {"title": "x", "goal": "y", "idempotency_key": f"acc-evil-{run}"})
    if good:
        fail("executor create_task refused", json.dumps(p)[:200])
    ok("executor create_task refused")

    # 4. Dispatcher creates (idempotently) and assigns.
    create = {"title": "dispatch acceptance", "goal": "只读验收：报告一次进度后交付", "idempotency_key": f"acc-create-{run}"}
    good, p = dispatcher.call("create_task", create)
    if not good:
        fail("dispatcher create_task", json.dumps(p)[:300])
    task_id = task_id_of(p)
    ok("created", task_id)
    good, p = dispatcher.call("create_task", create)
    if not good or task_id_of(p) != task_id:
        fail("create_task with the same idempotency_key returns the same task", json.dumps(p)[:200])
    ok("idempotent create")
    good, p = executor.call("assign_task", {"task_id": task_id, "agent_id": executor_id})
    if good:
        fail("executor assign_task refused", json.dumps(p)[:200])
    ok("executor assign_task refused")
    good, p = dispatcher.call("assign_task", {"task_id": task_id, "agent_id": f"no-such-agent-{run}"})
    if good:
        fail("assign to unknown agent refused", json.dumps(p)[:200])
    ok("assign to unknown agent refused")
    good, p = dispatcher.call("assign_task", {"task_id": task_id, "agent_id": executor_id})
    if not good:
        fail("dispatcher assign_task", json.dumps(p)[:300])
    ok("assigned", executor_id)

    # 5. Only the assignee sees and can claim it.
    if other:
        good, p = other.call("list_my_tasks", {})
        if any(task_id_of(t) == task_id for t in (p.get("tasks") or [])):
            fail("non-assignee does not see the task", json.dumps(p)[:200])
        ok("non-assignee does not see the task")
        good, p = other.call("claim_task", {"task_id": task_id})
        if good:
            fail("non-assignee claim refused", json.dumps(p)[:200])
        ok("non-assignee claim refused")
    good, p = executor.call("list_my_tasks", {})
    if not good or not any(task_id_of(t) == task_id for t in (p.get("tasks") or [])):
        fail("assignee list_my_tasks shows the task", json.dumps(p)[:300])
    ok("assignee sees the task")
    good, claim = executor.call("claim_task", {"task_id": task_id, "attempt_id": f"acc-claim-{run}"})
    if not good:
        fail("assignee claim_task", json.dumps(claim)[:300])
    attempt = claim.get("attempt_id") or f"acc-claim-{run}"
    goal_version = claim.get("goal_version") or (claim.get("task") or {}).get("goal_version") or 1
    ok("claimed", state_of(claim))
    if other:
        good, p = other.call("claim_task", {"task_id": task_id})
        if good:
            fail("double claim by another executor refused", json.dumps(p)[:200])
        ok("double claim by another executor refused")
        good, p = other.call("submit_result", {"task_id": task_id, "attempt_id": attempt, "goal_version": goal_version,
                                               "result|summary": "hijack", "branch": "x"})
        if good:
            fail("submit by non-claimer refused", json.dumps(p)[:200])
        ok("submit by non-claimer refused")

    # 6. Progress then delivery.
    good, p = executor.call("report_progress", {"task_id": task_id, "attempt_id": attempt,
                                                "message|text": "验收：已开始", "percent": 50})
    if not good:
        fail("report_progress", json.dumps(p)[:300])
    ok("progress reported")
    good, p = executor.call("submit_result", {"task_id": task_id, "attempt_id": attempt, "goal_version": goal_version,
                                              "result|summary": "验收交付：只读任务完成",
                                              "branch": os.environ.get("ACCEPT_BRANCH", "acceptance"),
                                              "commit": os.environ.get("ACCEPT_COMMIT", "")})
    if not good:
        fail("submit_result", json.dumps(p)[:300])
    ok("delivered", state_of(p))

    # 7. The dispatcher (creator) reads the final status; another executor cannot.
    good, p = dispatcher.call("get_task_status", {"task_id": task_id})
    if not good:
        fail("dispatcher get_task_status", json.dumps(p)[:300])
    if state_of(p) not in finals:
        fail("final state", f"{state_of(p)} not in {sorted(finals)}")
    ok("final status", state_of(p))
    if other:
        good, p = other.call("get_task_status", {"task_id": task_id})
        if good:
            fail("unrelated executor cannot read status", json.dumps(p)[:200])
        ok("unrelated executor cannot read status")
    print("ACCEPTANCE_OK", flush=True)


if __name__ == "__main__":
    main()
