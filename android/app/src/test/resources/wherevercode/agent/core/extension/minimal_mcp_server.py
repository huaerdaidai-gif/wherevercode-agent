#!/usr/bin/env python3
"""Minimal MCP server (test double, written for this project).

Speaks the JSON-RPC 2.0 dialect the App's McpClient implements
(MCP 2025-11-25): initialize / ping / tools/list / tools/call /
resources/list / resources/read / resources/subscribe /
resources/unsubscribe / prompts/list / prompts/get, over stdio
(one JSON object per line) or Streamable HTTP (POST one JSON object,
SSE reply; Mcp-Session-Id header; GET SSE stream for server-initiated
messages; Last-Event-ID replay).

Tools (all real, no mock):
  echo(text)  -> returns the text back
  add(a, b)   -> returns str(a + b)

Resources (all real):
  test://note     -> "hello resource\n" (text/plain)
  test://numbers  -> "1,2,3" (text/plain)

Prompts (all real):
  greet(name)     -> "Hello, <name>!"
  summarize()     -> fixed summary prompt

Test hooks (method names starting with "test/", NOT part of MCP):
  test/add-tool        -> append a "bonus" tool, notify tools/list_changed
  test/update-resource {uri} -> notify resources/updated to subscribers
  test/expire-session  -> (HTTP only) delete this session; next request 404s
  test/request-roots   -> (HTTP only) send a roots/list server request
                          (id 9001) to the client over the GET stream;
                          the client's POSTed response is stored
  test/get-roots-response -> return the stored roots/list response (or null)

Modes:
  --legacy   simulate the old Hand server: protocol 2024-11-05, only the
             tools capability; resources/prompts methods answer -32601.
  --sse      also serve the legacy 2024-11-05 SSE endpoints on the same
             port: GET /sse (endpoint event + message stream) and
             POST /messages?session_id=... (requests get 202, replies
             arrive on the stream). Streamable HTTP stays on /.

Usage:
  python3 minimal_mcp_server.py --stdio [--legacy]
  python3 minimal_mcp_server.py --http 0 [--legacy]  # 0 = ephemeral; prints READY <port>
  python3 minimal_mcp_server.py --http 0 --sse [--legacy]
"""

import argparse
import json
import secrets
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


PROTOCOL_VERSION = "2025-11-25"
LEGACY_PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "minimal-mcp-test"
SERVER_VERSION = "1"

LEGACY = False  # set by --legacy


def tool_defs():
    return [
        {
            "name": "echo",
            "description": "Return the given text unchanged.",
            "inputSchema": {
                "type": "object",
                "properties": {"text": {"type": "string"}},
                "required": ["text"],
                "additionalProperties": False,
            },
        },
        {
            "name": "add",
            "description": "Add two numbers and return the sum.",
            "inputSchema": {
                "type": "object",
                "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                "required": ["a", "b"],
                "additionalProperties": False,
            },
        },
    ]


# Shared mutable tool list (test/add-tool appends "bonus" once).
TOOLS = tool_defs()

RESOURCE_TEXT = {
    "test://note": "hello resource\n",
    "test://numbers": "1,2,3",
}


def resource_defs():
    return [
        {
            "uri": "test://note",
            "name": "note",
            "description": "A test note resource.",
            "mimeType": "text/plain",
        },
        {
            "uri": "test://numbers",
            "name": "numbers",
            "description": "A test numbers resource.",
            "mimeType": "text/plain",
        },
    ]


def prompt_defs():
    return [
        {
            "name": "greet",
            "description": "Greet someone by name.",
            "arguments": [
                {"name": "name", "description": "Who to greet.", "required": True}
            ],
        },
        {
            "name": "summarize",
            "description": "Ask for a short summary.",
            "arguments": [],
        },
    ]


def text_result(text, is_error=False):
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


def call_tool(name, arguments):
    if name == "echo":
        text = arguments.get("text")
        if not isinstance(text, str):
            return text_result("echo needs a text argument", is_error=True)
        return text_result(text)
    if name == "add":
        a, b = arguments.get("a"), arguments.get("b")
        if not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
            return text_result("add needs numeric a and b", is_error=True)
        total = a + b
        if isinstance(total, float) and total.is_integer():
            total = int(total)
        return text_result(str(total))
    if name == "bonus":
        return text_result("bonus tool works")
    return text_result("unknown tool: %s" % name, is_error=True)


def read_resource(uri):
    if uri not in RESOURCE_TEXT:
        return None
    return {
        "contents": [
            {"uri": uri, "mimeType": "text/plain", "text": RESOURCE_TEXT[uri]}
        ]
    }


def get_prompt(name, arguments):
    if name == "greet":
        who = arguments.get("name", "world")
        return {
            "description": "Greeting prompt.",
            "messages": [
                {
                    "role": "user",
                    "content": {"type": "text", "text": "Hello, %s!" % who},
                }
            ],
        }
    if name == "summarize":
        return {
            "description": "Summary prompt.",
            "messages": [
                {
                    "role": "user",
                    "content": {
                        "type": "text",
                        "text": "Please summarize the above briefly.",
                    },
                }
            ],
        }
    return None


class Ctx:
    """Per-connection context: subscriptions + how to emit notifications."""

    def __init__(self, emit):
        self.subscriptions = set()
        self.emit = emit  # fn(notification_dict) -> None


def handle_request(req, ctx):
    """One JSON-RPC request dict -> (response dict | None, extra notifications).

    Returns (response, [notification, ...]) — response None means the
    request was a notification (no reply); notifications are emitted
    via ctx.emit by the caller after the response is sent (stdio) or
    queued into the session stream (HTTP).
    """
    if not isinstance(req, dict) or "method" not in req:
        return (
            {
                "jsonrpc": "2.0",
                "id": req.get("id") if isinstance(req, dict) else None,
                "error": {"code": -32600, "message": "Invalid Request"},
            },
            [],
        )
    method = req.get("method", "")
    req_id = req.get("id")
    params = req.get("params", {}) or {}
    if method.startswith("notifications/"):
        return None, []
    # --- test hooks (not part of MCP) ---
    if method == "test/add-tool":
        added = False
        if not any(t["name"] == "bonus" for t in TOOLS):
            TOOLS.append(
                {
                    "name": "bonus",
                    "description": "Bonus tool added by test hook.",
                    "inputSchema": {"type": "object", "properties": {}},
                }
            )
            added = True
        notes = []
        if added:
            notes.append(
                {"jsonrpc": "2.0", "method": "notifications/tools/list_changed"}
            )
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}, notes
    if method == "test/update-resource":
        uri = params.get("uri", "")
        notes = []
        if uri:
            notes.append(
                {
                    "jsonrpc": "2.0",
                    "method": "notifications/resources/updated",
                    "params": {"uri": uri},
                }
            )
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}, notes
    if method == "test/expire-session":
        # HTTP handler deletes the session itself (see do_POST); here
        # just answer so the client sees the hook succeed.
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}, []
    if method == "test/request-roots":
        # HTTP-only: push a roots/list server request (id 9001) to the
        # client over its GET stream; the client's POSTed response is
        # stored by do_POST and read back via test/get-roots-response.
        if not HTTP_MODE:
            return (
                {"jsonrpc": "2.0", "id": req_id, "result": {"ok": False, "error": "http-only"}},
                [],
            )
        broadcast(
            {"jsonrpc": "2.0", "id": 9001, "method": "roots/list", "params": {}}
        )
        return {"jsonrpc": "2.0", "id": req_id, "result": {"ok": True, "requestId": 9001}}, []
    if method == "test/get-roots-response":
        return (
            {"jsonrpc": "2.0", "id": req_id, "result": {"response": ROOTS_RESPONSES.get("last")}},
            [],
        )
    # --- MCP methods ---
    if method == "initialize":
        if LEGACY:
            caps = {"tools": {"listChanged": False}}
            version = LEGACY_PROTOCOL_VERSION
        else:
            caps = {
                "tools": {"listChanged": True},
                "resources": {"subscribe": True, "listChanged": True},
                "prompts": {"listChanged": True},
            }
            version = PROTOCOL_VERSION
        result = {
            "protocolVersion": version,
            "capabilities": caps,
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": list(TOOLS)}
    elif method == "tools/call":
        result = call_tool(
            str(params.get("name", "")), params.get("arguments", {}) or {}
        )
    elif method == "resources/list":
        if LEGACY:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": "Method not found: %s" % method},
                },
                [],
            )
        result = {"resources": resource_defs()}
    elif method == "resources/read":
        if LEGACY:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": "Method not found: %s" % method},
                },
                [],
            )
        content = read_resource(str(params.get("uri", "")))
        if content is None:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32602, "message": "Resource not found"},
                },
                [],
            )
        result = content
    elif method == "resources/subscribe":
        if LEGACY:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": "Method not found: %s" % method},
                },
                [],
            )
        ctx.subscriptions.add(str(params.get("uri", "")))
        result = {}
    elif method == "resources/unsubscribe":
        if LEGACY:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": "Method not found: %s" % method},
                },
                [],
            )
        ctx.subscriptions.discard(str(params.get("uri", "")))
        result = {}
    elif method == "prompts/list":
        if LEGACY:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": "Method not found: %s" % method},
                },
                [],
            )
        result = {"prompts": prompt_defs()}
    elif method == "prompts/get":
        if LEGACY:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": "Method not found: %s" % method},
                },
                [],
            )
        prompt = get_prompt(
            str(params.get("name", "")), params.get("arguments", {}) or {}
        )
        if prompt is None:
            return (
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32602, "message": "Prompt not found"},
                },
                [],
            )
        result = prompt
    else:
        return (
            {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32601, "message": "Method not found: %s" % method},
            },
            [],
        )
    return {"jsonrpc": "2.0", "id": req_id, "result": result}, []


def serve_stdio():
    def emit(notification):
        sys.stdout.write(json.dumps(notification, ensure_ascii=False) + "\n")
        sys.stdout.flush()

    ctx = Ctx(emit)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            sys.stdout.write(
                json.dumps(
                    {
                        "jsonrpc": "2.0",
                        "id": None,
                        "error": {"code": -32700, "message": "Parse error"},
                    }
                )
                + "\n"
            )
            sys.stdout.flush()
            continue
        resp, notes = handle_request(req, ctx)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()
        for note in notes:
            emit(note)


# ---- Streamable HTTP -------------------------------------------------


class Session:
    def __init__(self, sid):
        self.sid = sid
        self.subscriptions = set()
        self.events = []  # list of {"seq": int, "payload": dict}
        self.next_seq = 1
        self.cond = threading.Condition()

    def enqueue(self, payload):
        with self.cond:
            self.events.append({"seq": self.next_seq, "payload": payload})
            self.next_seq += 1
            self.cond.notify_all()


SESSIONS = {}
SESSIONS_LOCK = threading.Lock()

# Set by serve_http(): handle_request's HTTP-only hooks (roots E2E)
# refuse to run under --stdio, where the server cannot push requests.
HTTP_MODE = False

# Last response the client POSTed to a server-initiated request
# (roots/list E2E: test/request-roots -> test/get-roots-response).
ROOTS_RESPONSES = {}


def broadcast(payload, only_subscribers_of=None):
    """Enqueue a server-initiated message into matching sessions."""
    with SESSIONS_LOCK:
        sessions = list(SESSIONS.values())
    for sess in sessions:
        if only_subscribers_of is not None and only_subscribers_of not in sess.subscriptions:
            continue
        sess.enqueue(payload)


def sse_body(payload):
    return ("event: message\ndata: %s\n\n" % json.dumps(payload, ensure_ascii=False)).encode(
        "utf-8"
    )


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send_json(self, status, payload, extra_headers=None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _send_sse(self, status, payload, extra_headers=None):
        body = sse_body(payload)
        self.send_response(status)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            self._send_json(
                400,
                {
                    "jsonrpc": "2.0",
                    "id": None,
                    "error": {"code": -32700, "message": "Parse error"},
                },
            )
            return
        path = self.path.split("?", 1)[0]
        if path == "/messages":
            self._do_post_sse(req)
            return
        self._do_post_streamable(req)

    def _do_post_streamable(self, req):
        # A response to a server-initiated request (roots/list E2E):
        # no method, has id + result/error. Store it, answer 202.
        if isinstance(req, dict) and "method" not in req and "id" in req:
            ROOTS_RESPONSES["last"] = req
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        method = req.get("method", "") if isinstance(req, dict) else ""
        sid = self.headers.get("Mcp-Session-Id")
        if method == "initialize":
            # New session (or re-initialize after expiry): mint an id.
            sid = secrets.token_hex(16)
            with SESSIONS_LOCK:
                SESSIONS[sid] = Session(sid)
        else:
            if not sid:
                self._send_json(
                    400,
                    {
                        "jsonrpc": "2.0",
                        "id": "server-error",
                        "error": {
                            "code": -32600,
                            "message": "Bad Request: Missing session ID",
                        },
                    },
                )
                return
            with SESSIONS_LOCK:
                sess = SESSIONS.get(sid)
            if sess is None:
                self._send_json(
                    404,
                    {
                        "jsonrpc": "2.0",
                        "id": "server-error",
                        "error": {
                            "code": -32600,
                            "message": "Session not found",
                        },
                    },
                )
                return
        with SESSIONS_LOCK:
            sess = SESSIONS.get(sid)
        ctx = Ctx(lambda note: broadcast(note))
        ctx.subscriptions = sess.subscriptions
        resp, notes = handle_request(req, ctx)
        # test/expire-session: drop the session AFTER answering.
        expire = method == "test/expire-session"
        if resp is None:
            # Client notification (e.g. notifications/initialized): 202.
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            self._send_sse(200, resp, {"Mcp-Session-Id": sid})
        if expire:
            with SESSIONS_LOCK:
                SESSIONS.pop(sid, None)
        for note in notes:
            if note.get("method") == "notifications/resources/updated":
                uri = (note.get("params") or {}).get("uri", "")
                broadcast(note, only_subscribers_of=uri)
            else:
                broadcast(note)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/sse":
            self._do_get_sse()
            return
        self._do_get_streamable()

    def _do_get_streamable(self):
        sid = self.headers.get("Mcp-Session-Id")
        if not sid:
            self._send_json(
                400,
                {
                    "jsonrpc": "2.0",
                    "id": "server-error",
                    "error": {
                        "code": -32600,
                        "message": "Bad Request: Missing session ID",
                    },
                },
            )
            return
        with SESSIONS_LOCK:
            sess = SESSIONS.get(sid)
        if sess is None:
            self._send_json(
                404,
                {
                    "jsonrpc": "2.0",
                    "id": "server-error",
                    "error": {"code": -32600, "message": "Session not found"},
                },
            )
            return
        last_event_id = self.headers.get("Last-Event-ID")
        try:
            resume_after = int(last_event_id) if last_event_id else 0
        except ValueError:
            resume_after = 0
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        idx = 0
        with sess.cond:
            idx = sum(1 for e in sess.events if e["seq"] <= resume_after)
            while True:
                while idx < len(sess.events):
                    event = sess.events[idx]
                    idx += 1
                    line = (
                        "id: %d\nevent: message\ndata: %s\n\n"
                        % (
                            event["seq"],
                            json.dumps(event["payload"], ensure_ascii=False),
                        )
                    ).encode("utf-8")
                    try:
                        self.wfile.write(line)
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        return
                sess.cond.wait(timeout=30)

    # ---- Legacy SSE (2024-11-05): GET /sse + POST /messages --------

    def _do_get_sse(self):
        """GET /sse: mint a session, send the endpoint event, then stream."""
        sid = secrets.token_hex(16)
        with SESSIONS_LOCK:
            SESSIONS[sid] = Session(sid)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        try:
            self.wfile.write(
                ("event: endpoint\ndata: /messages?session_id=%s\n\n" % sid).encode("utf-8")
            )
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            return
        with SESSIONS_LOCK:
            sess = SESSIONS.get(sid)
        if sess is None:
            return
        idx = 0
        with sess.cond:
            while True:
                while idx < len(sess.events):
                    event = sess.events[idx]
                    idx += 1
                    line = (
                        "id: %d\nevent: message\ndata: %s\n\n"
                        % (
                            event["seq"],
                            json.dumps(event["payload"], ensure_ascii=False),
                        )
                    ).encode("utf-8")
                    try:
                        self.wfile.write(line)
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        return
                sess.cond.wait(timeout=30)

    def _do_post_sse(self, req):
        """POST /messages?session_id=...: handle the request, 202, reply on the stream."""
        from urllib.parse import parse_qs, urlparse

        query = parse_qs(urlparse(self.path).query)
        sid = (query.get("session_id") or [None])[0]
        # A response to a server-initiated request: store it, answer 202.
        if isinstance(req, dict) and "method" not in req and "id" in req:
            ROOTS_RESPONSES["last"] = req
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with SESSIONS_LOCK:
            sess = SESSIONS.get(sid) if sid else None
        if sess is None:
            self._send_json(
                404,
                {
                    "jsonrpc": "2.0",
                    "id": "server-error",
                    "error": {"code": -32600, "message": "Session not found"},
                },
            )
            return
        ctx = Ctx(lambda note: broadcast(note))
        ctx.subscriptions = sess.subscriptions
        resp, notes = handle_request(req, ctx)
        # Legacy SSE: the POST itself only ever returns 202; the response
        # (and any notifications) travel on the GET stream.
        self.send_response(202)
        self.send_header("Content-Length", "0")
        self.end_headers()
        if resp is not None:
            sess.enqueue(resp)
        for note in notes:
            if note.get("method") == "notifications/resources/updated":
                uri = (note.get("params") or {}).get("uri", "")
                broadcast(note, only_subscribers_of=uri)
            else:
                broadcast(note)


def serve_http(port):
    global HTTP_MODE
    HTTP_MODE = True
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("READY %d" % server.server_address[1], flush=True)
    server.serve_forever()


def main():
    global LEGACY
    parser = argparse.ArgumentParser()
    parser.add_argument("--stdio", action="store_true")
    parser.add_argument("--http", type=int, default=None)
    parser.add_argument("--legacy", action="store_true")
    parser.add_argument(
        "--sse",
        action="store_true",
        help="also serve legacy SSE endpoints (GET /sse, POST /messages) on the HTTP port",
    )
    args = parser.parse_args()
    LEGACY = args.legacy
    if args.http is not None:
        serve_http(args.http)
    else:
        serve_stdio()


if __name__ == "__main__":
    main()
