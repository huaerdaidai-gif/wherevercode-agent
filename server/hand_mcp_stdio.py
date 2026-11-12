#!/usr/bin/env python3
"""Vendor-neutral stdio MCP adapter for configured WhereverCode Hand devices.

The adapter is a client of the existing Hand v1 wire protocol. It uses the
same hello, X25519/HKDF, AES-GCM envelope, and v2 task credential as the Android
HandClient; it does not execute device operations locally.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import socket
import sys
import time
import uuid
from pathlib import Path
from typing import Any

import hand_server as hs

MCP_VERSION = "2024-11-05"
MAX_LINE_BYTES = 4 * 1024 * 1024
TOOLS = [
    {
        "name": "hand_devices",
        "description": "List configured Hand devices and their current reachability/pairing state. Use the returned device_id with another Hand tool.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "device_list",
        "description": "List files on a paired Hand device in a configured authorized folder.",
        "inputSchema": {"type": "object", "properties": {
            "path": {"type": "string", "description": "Relative path; empty means the authorized folder."},
            "device_id": {"type": "string", "description": "Configured Hand device ID."},
            "authorization_id": {"type": "string", "description": "Configured grant ID for this device."},
        }, "required": ["device_id", "authorization_id"], "additionalProperties": False},
    },
    {
        "name": "device_read",
        "description": "Read a UTF-8 text file from a paired Hand device's configured authorized folder.",
        "inputSchema": {"type": "object", "properties": {
            "path": {"type": "string"},
            "device_id": {"type": "string"}, "authorization_id": {"type": "string"},
        }, "required": ["device_id", "authorization_id", "path"], "additionalProperties": False},
    },
    {
        "name": "device_write",
        "description": "Write a UTF-8 text file to a paired Hand device's configured authorized folder. Requires the Hand-side grant.",
        "inputSchema": {"type": "object", "properties": {
            "path": {"type": "string"}, "content": {"type": "string"},
            "device_id": {"type": "string"}, "authorization_id": {"type": "string"},
        }, "required": ["device_id", "authorization_id", "path", "content"], "additionalProperties": False},
    },
    {
        "name": "device_run",
        "description": "Send pause, resume, stop, or change_goal control to a task on the selected Hand device. This adapter never starts an Agent process on the device.",
        "inputSchema": {"type": "object", "properties": {
            "operation": {"type": "string", "enum": ["pause", "resume", "stop", "change_goal"]},
            "operation_id": {"type": "string"}, "new_goal": {"type": "string"},
            "device_id": {"type": "string"}, "authorization_id": {"type": "string"},
        }, "required": ["device_id", "authorization_id", "operation", "operation_id"], "additionalProperties": False},
    },
]


class AdapterError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _credential(signing_key: bytes, *, task_id: str, source_device_id: str,
                target_device_id: str, agent_id: str, folder: str,
                epoch: int, goal_version: int, now: int | None = None) -> str:
    issued = int(time.time()) if now is None else now
    cred = {
        "user_id": "mcp-client",
        "task_id": task_id,
        "attempt": "1",
        "source_device_id": source_device_id,
        "target_device_id": target_device_id,
        "agent_id": agent_id,
        "folder": folder,
        "iat": issued,
        "exp": issued + hs.CREDENTIAL_TTL_SECONDS,
        "jti": str(uuid.uuid4()),
        "scope": {"agent_id": agent_id, "folder": folder},
        "epoch": epoch,
        "goal_version": goal_version,
    }
    signature_key = hashlib.sha256(signing_key).digest()
    signature = hmac.new(signature_key, hs.canonical_payload(cred), hashlib.sha256).digest()
    wire = dict(cred, v=2, sig_b64=base64.b64encode(signature).decode("ascii").rstrip("="))
    return b64url_encode(json.dumps(wire, separators=(",", ":")).encode("utf-8"))


def load_config(path: str) -> dict[str, Any]:
    try:
        with open(os.path.expanduser(path), "r", encoding="utf-8") as f:
            config = json.load(f)
    except (OSError, json.JSONDecodeError) as exc:
        raise AdapterError("configuration_error", f"Cannot read MCP config: {exc}") from exc
    if not isinstance(config.get("identity_file"), str) or not config["identity_file"].strip():
        raise AdapterError("configuration_error", "identity_file is required")
    devices = config.get("devices")
    if not isinstance(devices, list) or not devices:
        raise AdapterError("configuration_error", "devices must be a non-empty list")
    seen_devices: set[str] = set()
    for index, device in enumerate(devices):
        if not isinstance(device, dict):
            raise AdapterError("configuration_error", f"devices[{index}] must be an object")
        required = ("device_id", "host", "target_public_key_b64")
        missing = [key for key in required if not isinstance(device.get(key), str) or not device[key].strip()]
        if missing:
            raise AdapterError("configuration_error", f"devices[{index}] missing: " + ", ".join(missing))
        if device["device_id"] in seen_devices:
            raise AdapterError("configuration_error", f"duplicate device_id: {device['device_id']}")
        seen_devices.add(device["device_id"])
        try:
            target_key = base64.b64decode(device["target_public_key_b64"], validate=True)
        except Exception as exc:
            raise AdapterError("configuration_error", f"devices[{index}].target_public_key_b64 must be standard Base64") from exc
        if len(target_key) != 32:
            raise AdapterError("configuration_error", f"devices[{index}] pinned Hand key must be 32 bytes")
        grants = device.get("authorizations")
        if not isinstance(grants, list) or not grants:
            raise AdapterError("configuration_error", f"devices[{index}].authorizations must be a non-empty list")
        seen_grants: set[str] = set()
        for grant_index, grant in enumerate(grants):
            if not isinstance(grant, dict):
                raise AdapterError("configuration_error", f"devices[{index}].authorizations[{grant_index}] must be an object")
            for key in ("authorization_id", "agent_id", "folder"):
                if not isinstance(grant.get(key), str) or not grant[key].strip():
                    raise AdapterError("configuration_error", f"authorization requires non-empty {key}")
            if grant["authorization_id"] in seen_grants:
                raise AdapterError("configuration_error", f"duplicate authorization_id on device {device['device_id']}")
            seen_grants.add(grant["authorization_id"])
    return config


def load_identity(path: str) -> dict[str, Any]:
    identity_path = Path(os.path.expanduser(path))
    try:
        if identity_path.stat().st_mode & 0o077:
            raise AdapterError("identity_permissions", "Identity file permissions must be 0600")
        value = json.loads(identity_path.read_text(encoding="utf-8"))
        device_id = str(value["device_id"])
        private_key = base64.b64decode(value["private_key_b64"], validate=True)
    except AdapterError:
        raise
    except (OSError, KeyError, ValueError, json.JSONDecodeError) as exc:
        raise AdapterError("identity_error", f"Cannot load client identity: {exc}") from exc
    if len(private_key) != 32 or not device_id:
        raise AdapterError("identity_error", "Client identity is malformed")
    public_key = hs.X25519PrivateKey.from_private_bytes(private_key).public_key().public_bytes_raw()
    return {"device_id": device_id, "private_key": private_key, "public_key": public_key}


def init_identity(path: str, device_id: str | None = None) -> dict[str, str]:
    private = hs.X25519PrivateKey.generate()
    private_bytes = private.private_bytes_raw()
    public_bytes = private.public_key().public_bytes_raw()
    identity = {
        "device_id": device_id or "mcp-client-" + uuid.uuid4().hex[:12],
        "private_key_b64": base64.b64encode(private_bytes).decode("ascii"),
    }
    out = Path(os.path.expanduser(path))
    out.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(identity, f, indent=2)
        f.write("\n")
    return {
        "device_id": identity["device_id"],
        "public_key_b64": base64.b64encode(public_bytes).decode("ascii"),
        "fingerprint_sha256": hashlib.sha256(public_bytes).hexdigest(),
        "identity_file": str(out),
    }


class HandMcpAdapter:
    def __init__(self, config: dict[str, Any], identity: dict[str, Any], *,
                 socket_factory=socket.create_connection, connect_timeout: float = 5.0,
                 read_timeout: float = 10.0):
        self.config = config
        self.identity = identity
        self.socket_factory = socket_factory
        self.connect_timeout = connect_timeout
        self.read_timeout = read_timeout
        self.devices = {device["device_id"]: device for device in config["devices"]}
        self.task_states: dict[tuple[str, str], dict[str, Any]] = {}

    def _open_hand(self, target: dict[str, Any]):
        device_id = target["device_id"]
        timeout = float(target.get("timeout_seconds", self.config.get("timeout_seconds", self.read_timeout)))
        try:
            conn = self.socket_factory((target["host"], int(target.get("port", hs.DEFAULT_PORT))), self.connect_timeout)
        except OSError as exc:
            raise AdapterError("device_offline", f"Hand device {device_id} is unreachable: {exc}") from exc
        try:
            conn.settimeout(timeout)
            hello = {
                "type": "wherevercode_hello", "version": hs.VERSION,
                "device_id": self.identity["device_id"],
                "public_key_b64": base64.b64encode(self.identity["public_key"]).decode("ascii"),
            }
            hs.write_frame(conn, json.dumps(hello, separators=(",", ":")).encode("utf-8"))
            try:
                reply = json.loads(hs.read_frame(conn, timeout).decode("utf-8"))
            except (OSError, ValueError, ConnectionError, json.JSONDecodeError) as exc:
                raise AdapterError("device_timeout", f"Timed out or invalid Hand hello from device {device_id}") from exc
            if reply.get("type") != "wherevercode_hello" or reply.get("version") != hs.VERSION:
                raise AdapterError("protocol_error", "Device did not return a Hand v1 hello")
            if reply.get("device_id") != device_id:
                raise AdapterError("wrong_target", f"Hand answered as {reply.get('device_id')!r}, expected configured device {device_id!r}")
            peer_key = base64.b64decode(reply.get("public_key_b64", ""), validate=True)
            pinned_key = base64.b64decode(target["target_public_key_b64"], validate=True)
            if not hmac.compare_digest(peer_key, pinned_key):
                raise AdapterError("wrong_target", f"Hand public key for {device_id} does not match its pinned pairing key")
            return conn, reply, peer_key, timeout
        except Exception:
            conn.close()
            raise

    def _target_and_grant(self, args: dict[str, Any]):
        device_id = args.get("device_id")
        if not isinstance(device_id, str) or device_id not in self.devices:
            raise AdapterError("wrong_target", "device_id must select a configured Hand device")
        target = self.devices[device_id]
        authorization_id = args.get("authorization_id")
        grants = {grant["authorization_id"]: grant for grant in target["authorizations"]}
        if not isinstance(authorization_id, str) or authorization_id not in grants:
            raise AdapterError("authorization_required", "authorization_id must select a configured task grant for this device")
        return target, grants[authorization_id]

    def _task_state(self, target: dict[str, Any], grant: dict[str, Any]) -> dict[str, Any]:
        key = (target["device_id"], grant["authorization_id"])
        return self.task_states.setdefault(key, {
            "task_id": "mcp-task-" + uuid.uuid4().hex,
            "goal_version": int(grant.get("goal_version", 1)),
            "epoch": int(grant.get("epoch", 1)),
            "stopped": False,
            "paused": False,
        })

    def _probe(self, target: dict[str, Any]) -> dict[str, Any]:
        conn, reply, _peer_key, _timeout = self._open_hand(target)
        try:
            status = reply.get("client_status", "UNKNOWN")
            return {
                "device_id": target["device_id"],
                "display_name": str(reply.get("display_name", target["device_id"])),
                "status": "paired" if status == "APPROVED" else str(status).lower(),
                "authorizations": [grant["authorization_id"] for grant in target["authorizations"]],
            }
        finally:
            conn.close()

    def _call(self, action: str, args: dict[str, Any], target: dict[str, Any], grant: dict[str, Any]) -> str:
        state = self._task_state(target, grant)
        if state["stopped"] and action == "write":
            raise AdapterError("task_stopped", "Task was stopped; issue change_goal before writing again")
        conn, reply, peer_key, timeout = self._open_hand(target)
        try:
            status = reply.get("client_status")
            if status != "APPROVED":
                raise AdapterError("pairing_required", f"This MCP client identity is {status or 'not paired'} on device {target['device_id']}; approve it on the Hand device")
            identity = self.identity
            device_id = target["device_id"]
            session_key = hs.derive_session_key(identity["private_key"], identity["public_key"], peer_key)
            signing_key = hs.derive_signing_key(session_key)
            wire_args = {k: v for k, v in args.items() if k not in {"device_id", "authorization_id"}}
            if action == "run":
                operation = wire_args.pop("operation")
                operation_id = wire_args.pop("operation_id")
                new_goal = wire_args.pop("new_goal", "")
                wire_args["argv"] = json.dumps(["__control__", operation, operation_id, new_goal], separators=(",", ":"))
                wire_args["timeout"] = "30"
                wire_args["control"] = json.dumps({"goal_version": state["goal_version"], "epoch": state["epoch"]}, separators=(",", ":"))
            wire_args["credential"] = _credential(
                signing_key, task_id=state["task_id"], source_device_id=identity["device_id"],
                target_device_id=device_id, agent_id=grant["agent_id"], folder=grant["folder"],
                epoch=state["epoch"], goal_version=state["goal_version"],
            )
            envelope = {
                "type": "cross_device_tool_call", "version": hs.VERSION,
                "seq": 1, "task_id": state["task_id"], "agent_id": grant["agent_id"],
                "source_device_id": identity["device_id"], "target_device_id": device_id,
                "folder": grant["folder"], "action": action, "args": wire_args,
            }
            hs.write_frame(conn, hs.seal(session_key, json.dumps(envelope, separators=(",", ":"), ensure_ascii=False).encode("utf-8")))
            while True:
                try:
                    response = json.loads(hs.open_sealed(session_key, hs.read_frame(conn, timeout)).decode("utf-8"))
                except (OSError, ValueError, ConnectionError, json.JSONDecodeError, TimeoutError) as exc:
                    raise AdapterError("device_timeout", f"No complete response from Hand device {device_id}: {exc}") from exc
                if response.get("task_id") != state["task_id"] or response.get("target_device_id") != identity["device_id"]:
                    raise AdapterError("wrong_target", "Refusing response routed for a different task or client device")
                kind = response.get("type")
                if kind == "cross_device_progress":
                    continue
                response_args = response.get("args") or {}
                if kind == "cross_device_error":
                    raise AdapterError(str(response_args.get("code", "hand_error")), str(response_args.get("message", "Hand request failed")))
                if kind != "cross_device_result":
                    raise AdapterError("protocol_error", f"Unexpected Hand response type: {kind!r}")
                if action == "run":
                    operation = args["operation"]
                    if operation == "stop":
                        state["stopped"] = True
                    elif operation == "pause":
                        state["paused"] = True
                    elif operation == "resume":
                        state["paused"] = False
                    elif operation == "change_goal":
                        state["goal_version"] += 1
                        state["stopped"] = False
                        state["paused"] = False
                return str(response_args.get("output", ""))
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def call_tool(self, name: str, args: dict[str, Any]) -> str:
        if name == "hand_devices":
            devices = []
            for target in self.devices.values():
                try:
                    devices.append(self._probe(target))
                except AdapterError as exc:
                    devices.append({"device_id": target["device_id"], "status": exc.code,
                                    "authorizations": [g["authorization_id"] for g in target["authorizations"]]})
            return json.dumps(devices, ensure_ascii=False)
        if name not in {"device_list", "device_read", "device_write", "device_run"}:
            raise AdapterError("unknown_tool", f"Unknown tool: {name}")
        target, grant = self._target_and_grant(args)
        if name == "device_run":
            operation = args.get("operation")
            if operation not in {"pause", "resume", "stop", "change_goal"}:
                raise AdapterError("command_not_allowed", "device_run only accepts pause/resume/stop/change_goal controls; it never launches an Agent")
            if not str(args.get("operation_id", "")).strip():
                raise AdapterError("invalid_arguments", "operation_id is required")
            return self._call("run", args, target, grant)
        action = {"device_list": "list", "device_read": "read", "device_write": "write"}[name]
        if action in ("read", "write") and not str(args.get("path", "")):
            raise AdapterError("invalid_arguments", "path is required")
        if action == "write" and not isinstance(args.get("content"), str):
            raise AdapterError("invalid_arguments", "content must be a string")
        return self._call(action, args, target, grant)


def _text_result(text: str, is_error: bool = False) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


def _rpc_error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def serve_stdio(adapter: HandMcpAdapter, input_stream=None, output_stream=None) -> None:
    source = input_stream or sys.stdin.buffer
    sink = output_stream or sys.stdout.buffer
    while True:
        raw = source.readline(MAX_LINE_BYTES + 1)
        if not raw:
            return
        if len(raw) > MAX_LINE_BYTES:
            continue
        request_id = None
        try:
            request = json.loads(raw.decode("utf-8"))
            request_id = request.get("id")
            method = request.get("method")
            if request_id is None and method and str(method).startswith("notifications/"):
                continue
            if method == "initialize":
                result = {"protocolVersion": MCP_VERSION, "capabilities": {"tools": {}},
                          "serverInfo": {"name": "wherevercode-hand-device", "version": "1.0.0"}}
                response = {"jsonrpc": "2.0", "id": request_id, "result": result}
            elif method == "ping":
                response = {"jsonrpc": "2.0", "id": request_id, "result": {}}
            elif method == "tools/list":
                response = {"jsonrpc": "2.0", "id": request_id, "result": {"tools": TOOLS}}
            elif method == "tools/call":
                params = request.get("params") or {}
                try:
                    text = adapter.call_tool(str(params.get("name", "")), params.get("arguments") or {})
                    result = _text_result(text)
                except AdapterError as exc:
                    result = _text_result(f"{exc} ({exc.code})", is_error=True)
                response = {"jsonrpc": "2.0", "id": request_id, "result": result}
            else:
                response = _rpc_error(request_id, -32601, f"Method not found: {method}")
        except Exception as exc:
            response = _rpc_error(request_id, -32700, f"Invalid JSON-RPC request: {exc}")
        sink.write(json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
        sink.flush()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Vendor-neutral WhereverCode Hand stdio MCP adapter")
    parser.add_argument("--config", default="~/.config/wherevercode-agent/hand-mcp.json")
    parser.add_argument("--init-identity", metavar="PATH", help="Create a new 0600 MCP client identity and print the public pairing details")
    args = parser.parse_args(argv)
    if args.init_identity:
        print(json.dumps(init_identity(args.init_identity), indent=2))
        return 0
    try:
        config = load_config(args.config)
        identity = load_identity(config["identity_file"])
        serve_stdio(HandMcpAdapter(config, identity))
        return 0
    except AdapterError as exc:
        print(f"{exc} ({exc.code})", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
