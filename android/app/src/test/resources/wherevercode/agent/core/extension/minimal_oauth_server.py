#!/usr/bin/env python3
"""Minimal OAuth 2.1 + MCP server (test double, written for this project).

Serves, on one ephemeral port:
  - an MCP endpoint (/mcp) guarded by Bearer token: no/invalid token
    -> 401 + WWW-Authenticate: Bearer resource_metadata="...";
  - OAuth 2.1 discovery:
      /.well-known/oauth-protected-resource
      /.well-known/oauth-authorization-server
  - /authorize (GET): takes response_type=code, client_id, redirect_uri,
    code_challenge (S256); stores the challenge and returns JSON
    {"code": "<code>", "state": "<state>"} (simulates the browser
    landing after the user approves; the test's codeProvider GETs
    this URL and extracts the code).
  - /token (POST, form-encoded):
      grant_type=authorization_code: verifies code_verifier against
        the stored S256 challenge (wrong verifier -> 400 invalid_grant);
        issues access_token + refresh_token + expires_in.
      grant_type=refresh_token: issues a fresh access_token.

The MCP side reuses minimal_mcp_server.handle_request (same directory);
it is stateless here (no Mcp-Session-Id issued or required) so the
test exercises the auth path, not sessions.

Usage:
  python3 minimal_oauth_server.py 0   # 0 = ephemeral port; prints READY <port>
"""

import base64
import hashlib
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from minimal_mcp_server import Ctx, handle_request  # noqa: E402


class OAuthState:
    def __init__(self):
        self.codes = {}  # code -> {"challenge": str, "client_id": str}
        self.access_tokens = set()
        self.refresh_tokens = set()
        self.counter = 0

    def next(self, prefix):
        self.counter += 1
        return "%s-%d" % (prefix, self.counter)


STATE = OAuthState()


def b64url_sha256(verifier):
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _base(self):
        return "http://127.0.0.1:%d" % self.server.server_address[1]

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

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/.well-known/oauth-protected-resource":
            self._send_json(
                200,
                {
                    "resource": self._base() + "/mcp",
                    "authorization_servers": [self._base()],
                },
            )
            return
        if parsed.path == "/.well-known/oauth-authorization-server":
            self._send_json(
                200,
                {
                    "issuer": self._base(),
                    "authorization_endpoint": self._base() + "/authorize",
                    "token_endpoint": self._base() + "/token",
                    "code_challenge_methods_supported": ["S256"],
                },
            )
            return
        if parsed.path == "/authorize":
            qs = parse_qs(parsed.query)
            challenge = qs.get("code_challenge", [""])[0]
            method = qs.get("code_challenge_method", [""])[0]
            if qs.get("response_type", [""])[0] != "code" or method != "S256" or not challenge:
                self._send_json(400, {"error": "invalid_request"})
                return
            code = STATE.next("code")
            STATE.codes[code] = {
                "challenge": challenge,
                "client_id": qs.get("client_id", [""])[0],
            }
            self._send_json(
                200, {"code": code, "state": qs.get("state", [""])[0]}
            )
            return
        self._send_json(404, {"error": "not_found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length > 0 else b""
        if parsed.path == "/token":
            form = parse_qs(raw.decode("utf-8"))
            grant = form.get("grant_type", [""])[0]
            if grant == "authorization_code":
                code = form.get("code", [""])[0]
                verifier = form.get("code_verifier", [""])[0]
                stored = STATE.codes.pop(code, None)
                if stored is None:
                    self._send_json(400, {"error": "invalid_grant"})
                    return
                if b64url_sha256(verifier) != stored["challenge"]:
                    self._send_json(400, {"error": "invalid_grant"})
                    return
                access = STATE.next("tok")
                refresh = STATE.next("ref")
                STATE.access_tokens.add(access)
                STATE.refresh_tokens.add(refresh)
                self._send_json(
                    200,
                    {
                        "access_token": access,
                        "token_type": "Bearer",
                        "expires_in": 3600,
                        "refresh_token": refresh,
                    },
                )
                return
            if grant == "refresh_token":
                refresh = form.get("refresh_token", [""])[0]
                if refresh not in STATE.refresh_tokens:
                    self._send_json(400, {"error": "invalid_grant"})
                    return
                access = STATE.next("tok")
                STATE.access_tokens.add(access)
                self._send_json(
                    200,
                    {
                        "access_token": access,
                        "token_type": "Bearer",
                        "expires_in": 3600,
                        "refresh_token": refresh,
                    },
                )
                return
            self._send_json(400, {"error": "unsupported_grant_type"})
            return
        if parsed.path == "/mcp":
            auth = self.headers.get("Authorization", "")
            token = auth[len("Bearer "):] if auth.startswith("Bearer ") else ""
            if token not in STATE.access_tokens:
                self._send_json(
                    401,
                    {"error": "unauthorized"},
                    {
                        "WWW-Authenticate": (
                            'Bearer resource_metadata="%s/.well-known/oauth-protected-resource"'
                            % self._base()
                        )
                    },
                )
                return
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
            ctx = Ctx(lambda note: None)
            resp, _notes = handle_request(req, ctx)
            if resp is None:
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                self._send_json(200, resp)
            return
        self._send_json(404, {"error": "not_found"})


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("READY %d" % server.server_address[1], flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
