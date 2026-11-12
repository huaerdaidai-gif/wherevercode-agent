#!/usr/bin/env python3
"""WhereverCode V5 远程 Hand 服务端（阶段 B，VPS 侧最小实现）。

协议与 App 侧 `core/mcp/HandClient`、`core/mcp/HandProtocol`、
`core/security/DeviceKeys`、`core/security/TaskCredential` 同款，
源码自主写（只学老项目 cross_device_mcp.py / cross_device_crypto.py /
cross_device_credentials.py 的协议思路，不复用其代码与 bug）。

传输：TCP（Tailnet 内明文 TCP，认证/加密边界是密封信封本身，
不另做 TLS）。每帧 = 4 字节大端长度 + 负载，最大 4MB。
一次调用一个新连接；一次 run 调用在连接上流式回 progress/result。

握手（明文 JSON，一帧来回，不执行任何操作）：
  App → {"type":"wherevercode_hello","version":1,
         "device_id":…,"public_key_b64":…}
  VPS → 同 type/version + device_id + display_name +
         public_key_b64 + agents:[{agent_id,display_name,kind,
         capabilities,folder,available}]
  available = 服务端检查过该 agent 的入口命令存在。

调用（密封帧，AES-256-GCM：12 字节 nonce 在前，密文，16 字节 tag 在后）：
  信封 {"type":"cross_device_tool_call","version":1,"seq":N,
    "task_id":…,"agent_id":…,"source_device_id":…,
    "target_device_id":…,"folder":…,"action":"list|read|write|run",
    "args":{…}}，其中
    - args["credential"]：v2 任务凭证（Base64url 的 JSON，
      v=2，HMAC-SHA256 签名，密钥 = SHA-256(signingKey)，
      canonical payload 以 "|" 连接
      user_id|task_id|attempt|source_device_id|target_device_id|
      agent_id|folder|iat|exp|jti|scope_agent_id|scope_folder|
      epoch|goal_version）；
    - run 的 argv 走 args["argv"]（JSON 数组），超时走 args["timeout"]；
    - 执行栅栏快照走 args["control"]（JSON：{"goal_version":N,"epoch":N}）。
  会话密钥：X25519 + HKDF-SHA256，salt = SHA-256(两公钥排序拼接)，
  info = "wherevercode-cross-device/v1"。
  凭证签名密钥：HKDF-SHA256(sessionKey,
    salt=SHA-256("wherevercode-credential-signing/v1"),
    info="wherevercode-credential-signing/v1", 32)，双方各自推导，
  不另行交换。

回包（同密封帧，type 三种）：
  cross_device_progress {"message":…,"percent":…?}
  cross_device_result   {"output":…}
  cross_device_error    {"code":…,"message":…}
  服务端随后关闭连接。run 被控制指令停掉时，终态 error 帧的
  code 为 "remote_controlled_stop"（App 侧据此落 cancelled）。

配对（双向、显式、无自动信任）：
  App 的 hello 到达时，若其 device_id 不在 paired_clients.json 里，
  记为 PENDING（只存公钥，不执行）；maintainer 在 VPS 上跑
  `hand_server.py pair-approve <device-id>` 后才算 APPROVED，
  之后该设备的密封调用才会被受理。未配对设备的密封调用
  一律以 cross_device_not_authorized 拒绝。

授权（auth.json）：agent_id → 允许访问的文件夹列表。
  凭证的 folder 必须在该 agent 的授权列表里（词法归一化后比较），
  否则 wrong_folder。run 只允许注册在 agents.json 里的 agent
  命令（argv[0] == "agent"，argv[1] 为 agent_id），或控制命令
  （argv[0] == "__control__"）。不接受任意 shell 命令。

状态文件（--state-dir，默认 ~/.wherevercode/hand-server）：
  server_key.json  服务端 X25519 密钥对（0600 权限，首次启动生成）
  paired_clients.json  device_id → {public_key_b64, status, added_at}
  auth.json        agent_id → [folder, …]
  agents.json      agent_id → {display_name, kind, capabilities,
                    folder, command:[…], env:{…}}
  tasks.json       持久任务栅栏：task_id → {goal_version, epoch, stopped,
                    control_stopped}；不存文件夹路径、凭证或正文
  audit.jsonl      审计日志（JSON 行，绝不写凭证、密钥与正文密钥）

依赖：Python 3.10+ 标准库 + cryptography（pip install cryptography）。
不依赖旧平台服务器/账号/数据库。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import signal
import socket
import socketserver
import subprocess
import sys
import threading
import time
import uuid

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

VERSION = 1
HKDF_INFO = b"wherevercode-cross-device/v1"
CREDENTIAL_SIGNING_INFO = b"wherevercode-credential-signing/v1"
MAX_FRAME_BYTES = 4 * 1024 * 1024
MAX_TEXT_BYTES = 2 * 1024 * 1024
MAX_OUTPUT_CHARS = 20_000
DEFAULT_PORT = 18743
DEFAULT_TIMEOUT_SECONDS = 600
CREDENTIAL_TTL_SECONDS = 900

ERR_NOT_AUTHORIZED = "cross_device_not_authorized"
ERR_INVALID_CREDENTIAL = "invalid_credential"
ERR_EXPIRED_CREDENTIAL = "expired_credential"
ERR_REVOKED_CREDENTIAL = "revoked_credential"
ERR_WRONG_TASK = "wrong_task"
ERR_WRONG_TARGET = "wrong_target"
ERR_WRONG_SOURCE = "wrong_source"
ERR_WRONG_AGENT = "wrong_agent"
ERR_WRONG_SCOPE = "wrong_scope"
ERR_WRONG_FOLDER = "wrong_folder"
ERR_COMMAND_NOT_ALLOWED = "command_not_allowed"
ERR_PATH_NOT_ALLOWED = "path_not_allowed"
ERR_STALE_GOAL_VERSION = "stale_goal_version"
ERR_STALE_EPOCH = "stale_epoch"
ERR_AGENT_NOT_FOUND = "agent_not_found"
ERR_REMOTE_CONTROLLED_STOP = "remote_controlled_stop"
ERR_AGENT_TIMEOUT = "agent_timeout"
ERR_REPLAYED_REQUEST = "replayed_request"
ERR_TASK_STOPPED = "task_stopped"


# --------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------

def b64e(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def b64d(text: str) -> bytes:
    padded = text + "=" * ((4 - len(text) % 4) % 4)
    return base64.b64decode(padded)


def b64url_e(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_d(text: str) -> bytes:
    padded = text + "=" * ((4 - len(text) % 4) % 4)
    return base64.urlsafe_b64decode(padded)


def canonical_folder(folder: str) -> str:
    """词法归一化：与 App 侧 TaskCredentialIssuer.canonicalFolderLexical 同款。"""
    folder = folder.replace("\\", "/")
    parts = []
    for part in folder.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if parts:
                parts.pop()
        else:
            parts.append(part)
    prefix = "/" if folder.startswith("/") else ""
    return prefix + "/".join(parts)


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=length,
        salt=salt,
        info=info,
    ).derive(ikm)


def derive_session_key(own_private: bytes, own_public: bytes, peer_public: bytes) -> bytes:
    """X25519 + HKDF；与 App 侧 DeviceKeys.deriveSessionKey 同款。"""
    private = X25519PrivateKey.from_private_bytes(own_private)
    peer = X25519PublicKey.from_public_bytes(peer_public)
    shared = private.exchange(peer)
    first, second = (
        (own_public, peer_public)
        if own_public <= peer_public
        else (peer_public, own_public)
    )
    salt = hashlib.sha256(first + second).digest()
    return hkdf_sha256(shared, salt, HKDF_INFO, 32)


def derive_signing_key(session_key: bytes) -> bytes:
    """凭证签名密钥：由会话密钥再 HKDF 一次，双方各自推导。"""
    salt = hashlib.sha256(CREDENTIAL_SIGNING_INFO).digest()
    return hkdf_sha256(session_key, salt, CREDENTIAL_SIGNING_INFO, 32)


def seal(session_key: bytes, plaintext: bytes) -> bytes:
    """AES-256-GCM：12 字节 nonce 在前，密文，16 字节 tag 在后。"""
    nonce = os.urandom(12)
    return nonce + AESGCM(session_key).encrypt(nonce, plaintext, None)


def open_sealed(session_key: bytes, sealed: bytes) -> bytes:
    if len(sealed) < 12 + 16:
        raise ValueError("sealed blob too short")
    nonce, ciphertext = sealed[:12], sealed[12:]
    return AESGCM(session_key).decrypt(nonce, ciphertext, None)


def read_frame(conn: socket.socket, timeout: float) -> bytes:
    conn.settimeout(timeout)
    header = b""
    while len(header) < 4:
        chunk = conn.recv(4 - len(header))
        if not chunk:
            raise ConnectionError("connection closed while reading frame header")
        header += chunk
    length = int.from_bytes(header, "big")
    if length <= 0 or length > MAX_FRAME_BYTES:
        raise ValueError(f"frame length out of range: {length}")
    payload = b""
    while len(payload) < length:
        chunk = conn.recv(min(65536, length - len(payload)))
        if not chunk:
            raise ConnectionError("connection closed while reading frame")
        payload += chunk
    return payload


def write_frame(conn: socket.socket, payload: bytes) -> None:
    if len(payload) > MAX_FRAME_BYTES:
        raise ValueError("frame too large")
    conn.sendall(len(payload).to_bytes(4, "big") + payload)


# --------------------------------------------------------------------------
# 任务凭证（v2，与 App 侧 TaskCredential 同款）
# --------------------------------------------------------------------------

def canonical_payload(cred: dict) -> bytes:
    fields = [
        cred.get("user_id", ""),
        cred.get("task_id", ""),
        cred.get("attempt", ""),
        cred.get("source_device_id", ""),
        cred.get("target_device_id", ""),
        cred.get("agent_id", ""),
        cred.get("folder", ""),
        str(cred.get("iat", 0)),
        str(cred.get("exp", 0)),
        cred.get("jti", ""),
        cred.get("scope", {}).get("agent_id", ""),
        cred.get("scope", {}).get("folder", ""),
        str(cred.get("epoch", 1)),
        str(cred.get("goal_version", 1)),
    ]
    return "|".join(fields).encode("utf-8")


def parse_wire_credential(wire: str) -> dict | None:
    try:
        obj = json.loads(b64url_d(wire).decode("utf-8"))
        if obj.get("v") != 2:
            return None
        sig = b64d(obj.get("sig_b64", ""))
        if not sig:
            return None
        cred = {
            "user_id": obj.get("user_id", ""),
            "task_id": obj.get("task_id", ""),
            "attempt": obj.get("attempt", ""),
            "source_device_id": obj.get("source_device_id", ""),
            "target_device_id": obj.get("target_device_id", ""),
            "agent_id": obj.get("agent_id", ""),
            "folder": obj.get("folder", ""),
            "iat": obj.get("iat", 0),
            "exp": obj.get("exp", 0),
            "jti": obj.get("jti", ""),
            "scope": obj.get("scope", {}) or {},
            "epoch": obj.get("epoch", 1),
            "goal_version": obj.get("goal_version", 1),
            "sig": sig,
        }
        if not (cred["task_id"] and cred["jti"] and cred["target_device_id"]
                and cred["folder"] and cred["exp"] > 0):
            return None
        return cred
    except Exception:
        return None


def verify_credential(
    cred: dict,
    signing_key: bytes,
    expected_task_id: str,
    expected_target_device_id: str,
    expected_folder: str,
    expected_agent_id: str,
    authorized_folders: list[str],
    revoked_jtis: set[str],
    revoked_tasks: set[str],
    now: int,
    expected_source_device_id: str = "",
) -> str | None:
    """返回 None 表示通过，否则返回错误码。校验顺序与 App 侧一致：
    签名 → 过期 → 撤销 → 绑定（任务/目标/文件夹）→ 授权仍存在。

    注意：App 侧 HandClient 目前签发的是 legacy v2 凭证（user_id /
    attempt / source_device_id / agent_id 为空串，见
    TaskCredentialIssuer.issue 的 legacy 重载），所以这里不校验
    agent 绑定——agent 归属由信封 agentId + auth.json 授权表约束
    （folder 必须在该 agent 的授权列表里）；epoch / goal_version
    由 args["control"] 快照约束（见 _check_fence）。legacy 校验是
    完整 v2 校验的子集，未来客户端改签完整凭证时同样通过。"""
    mac_key = hashlib.sha256(signing_key).digest()
    expected_sig = hmac.new(mac_key, canonical_payload(cred), hashlib.sha256).digest()
    if not hmac.compare_digest(expected_sig, cred["sig"]):
        return ERR_INVALID_CREDENTIAL
    if cred["exp"] <= now:
        return ERR_EXPIRED_CREDENTIAL
    if cred["jti"] in revoked_jtis or cred["task_id"] in revoked_tasks:
        return ERR_REVOKED_CREDENTIAL
    if cred["task_id"] != expected_task_id:
        return ERR_WRONG_TASK
    if cred["target_device_id"] != expected_target_device_id:
        return ERR_WRONG_TARGET
    if cred["source_device_id"] and expected_source_device_id and cred["source_device_id"] != expected_source_device_id:
        return ERR_WRONG_SOURCE
    if canonical_folder(cred["folder"]) != canonical_folder(expected_folder):
        return ERR_WRONG_FOLDER
    if cred["agent_id"] and cred["agent_id"] != expected_agent_id:
        return ERR_WRONG_AGENT
    scope = cred.get("scope", {})
    if scope.get("agent_id") and scope["agent_id"] != expected_agent_id:
        return ERR_WRONG_SCOPE
    if scope.get("folder") and canonical_folder(scope["folder"]) != canonical_folder(expected_folder):
        return ERR_WRONG_SCOPE
    if canonical_folder(expected_folder) not in [canonical_folder(f) for f in authorized_folders]:
        return ERR_WRONG_FOLDER
    return None


# --------------------------------------------------------------------------
# 状态存储
# --------------------------------------------------------------------------

class StateStore:
    """state-dir 下的 JSON 文件集合（小文件，全量读写，加锁）。"""

    def __init__(self, state_dir: str):
        self.dir = os.path.expanduser(state_dir)
        os.makedirs(self.dir, exist_ok=True)
        self.lock = threading.Lock()

    def _path(self, name: str) -> str:
        return os.path.join(self.dir, name)

    def load_json(self, name: str, default):
        path = self._path(name)
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return default

    def save_json(self, name: str, data) -> None:
        path = self._path(name)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)

    def audit(self, event: dict) -> None:
        """审计日志：只记元数据（task/agent/action/结果），绝不记凭证与密钥。"""
        event = dict(event)
        event["ts"] = int(time.time())
        line = json.dumps(event, ensure_ascii=False)
        with self.lock:
            with open(self._path("audit.jsonl"), "a", encoding="utf-8") as f:
                f.write(line + "\n")


# --------------------------------------------------------------------------
# 服务端
# --------------------------------------------------------------------------

def git_repos_for_folder(folder: str) -> list[dict]:
    """P1 能力卡（2026-11-11）：读一个文件夹的 git 检出信息。

    只读 `git rev-parse`（分支 + 12 位短 commit），不写任何东西。
    不是 git 仓库、git 不在、超时、任何异常都返回 []——调用方按
    “未知”处理，绝不编造分支/commit。"""
    if not folder or not os.path.isdir(folder):
        return []
    try:
        branch = subprocess.run(
            ["git", "-C", folder, "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        commit = subprocess.run(
            ["git", "-C", folder, "rev-parse", "--short=12", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        if branch.returncode != 0 or commit.returncode != 0:
            return []
        b = branch.stdout.strip()
        c = commit.stdout.strip()
        if not b or not c:
            return []
        return [{"path": folder, "branch": b, "commit": c}]
    except Exception:
        return []


class HandServer:
    def __init__(self, state_dir: str, device_id: str, display_name: str, port: int = DEFAULT_PORT):
        self.state = StateStore(state_dir)
        self.device_id = device_id
        self.display_name = display_name
        self.port = port
        self.private_key, self.public_key = self._load_or_create_key()
        self.processes: dict[str, subprocess.Popen] = {}  # task_id → 进程
        self.process_lock = threading.Lock()
        self.task_meta_lock = threading.RLock()
        loaded_tasks = self.state.load_json("tasks.json", {})
        self.task_meta: dict[str, dict] = {
            str(task_id): dict(meta)
            for task_id, meta in loaded_tasks.items()
            if isinstance(task_id, str) and isinstance(meta, dict)
        } if isinstance(loaded_tasks, dict) else {}
        self.revoked_jtis: set[str] = set()
        self.revoked_tasks: set[str] = set()
        loaded_jtis = self.state.load_json("used_credentials.json", {})
        now = int(time.time())
        self.used_jtis: dict[str, int] = {
            str(jti): int(exp) for jti, exp in loaded_jtis.items()
            if isinstance(exp, (int, float)) and int(exp) > now
        } if isinstance(loaded_jtis, dict) else {}
        self.used_jtis_lock = threading.Lock()

    def _persist_task_meta_locked(self) -> None:
        """Persist task fences and stop state; caller holds task_meta_lock."""
        durable = {
            task_id: {
                key: meta[key]
                for key in ("goal_version", "epoch", "stopped", "control_stopped")
                if key in meta
            }
            for task_id, meta in self.task_meta.items()
        }
        self.state.save_json("tasks.json", durable)

    # -- 密钥与状态 -------------------------------------------------------

    def _load_or_create_key(self) -> tuple[bytes, bytes]:
        key_path = os.path.join(self.state.dir, "server_key.json")
        try:
            with open(key_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            private = b64d(data["private_key_b64"])
            public = b64d(data["public_key_b64"])
            if len(private) == 32 and len(public) == 32:
                return private, public
        except (FileNotFoundError, KeyError, ValueError, json.JSONDecodeError):
            pass
        private_obj = X25519PrivateKey.generate()
        private = private_obj.private_bytes_raw()
        public = private_obj.public_key().public_bytes_raw()
        tmp = key_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(
                {"private_key_b64": b64e(private), "public_key_b64": b64e(public)},
                f,
            )
        os.chmod(tmp, 0o600)
        os.replace(tmp, key_path)
        return private, public

    def fingerprint(self) -> str:
        return hashlib.sha256(self.public_key).hexdigest()

    def agents(self) -> list[dict]:
        raw = self.state.load_json("agents.json", {})
        out = []
        for agent_id, cfg in raw.items():
            command = cfg.get("command", [])
            available = bool(command) and os.path.exists(command[0])
            out.append({
                "agent_id": agent_id,
                "display_name": cfg.get("display_name", agent_id),
                "kind": cfg.get("kind", "agent"),
                "capabilities": cfg.get("capabilities", []),
                "folder": cfg.get("folder", ""),
                "available": available,
                # P1 能力卡：工作文件夹的 git 检出（分支/commit），不是
                # git 仓库时为空数组。
                "repos": git_repos_for_folder(cfg.get("folder", "")),
            })
        return out

    def agent_config(self, agent_id: str) -> dict | None:
        raw = self.state.load_json("agents.json", {})
        cfg = raw.get(agent_id)
        return cfg if isinstance(cfg, dict) else None

    def authorized_folders(self, agent_id: str) -> list[str]:
        auth = self.state.load_json("auth.json", {})
        folders = auth.get(agent_id, [])
        return folders if isinstance(folders, list) else []

    # -- 配对 --------------------------------------------------------------

    def note_client_hello(self, device_id: str, public_key_b64: str) -> str:
        """记录来访设备；已 APPROVED 的保持，已 PENDING 的保持，
        新设备记为 PENDING。返回当前状态。"""
        with self.state.lock:
            clients = self.state.load_json("paired_clients.json", {})
            entry = clients.get(device_id)
            if entry is None:
                entry = {
                    "public_key_b64": public_key_b64,
                    "status": "PENDING",
                    "added_at": int(time.time()),
                }
                clients[device_id] = entry
                self.state.save_json("paired_clients.json", clients)
            return entry.get("status", "PENDING")

    def client_status(self, device_id: str) -> str | None:
        clients = self.state.load_json("paired_clients.json", {})
        entry = clients.get(device_id)
        return entry.get("status") if isinstance(entry, dict) else None

    def client_public_key(self, device_id: str) -> bytes | None:
        clients = self.state.load_json("paired_clients.json", {})
        entry = clients.get(device_id)
        if not isinstance(entry, dict):
            return None
        try:
            return b64d(entry.get("public_key_b64", ""))
        except Exception:
            return None

    # -- 连接处理 ------------------------------------------------------------

    def handle_connection(self, conn: socket.socket, addr) -> None:
        try:
            self._handle_connection(conn, addr)
        except (ConnectionError, ValueError, OSError) as e:
            self.state.audit({"event": "connection_error", "from": str(addr), "error": str(e)[:200]})
        except Exception as e:  # 绝不让一个连接杀死服务进程
            self.state.audit({"event": "connection_error", "from": str(addr), "error": repr(e)[:200]})
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _handle_connection(self, conn: socket.socket, addr) -> None:
        # 1. hello（明文 JSON，一帧来回，不执行）。
        hello_raw = read_frame(conn, timeout=30)
        try:
            hello = json.loads(hello_raw.decode("utf-8"))
        except Exception:
            return
        if hello.get("type") != "wherevercode_hello" or hello.get("version") != VERSION:
            return
        client_id = str(hello.get("device_id", ""))
        client_pub_b64 = str(hello.get("public_key_b64", ""))
        if not client_id or not client_pub_b64:
            return
        try:
            client_pub = b64d(client_pub_b64)
            if len(client_pub) != 32:
                return
        except Exception:
            return
        status = self.note_client_hello(client_id, client_pub_b64)
        reply = {
            "type": "wherevercode_hello",
            "version": VERSION,
            "device_id": self.device_id,
            "display_name": self.display_name,
            "public_key_b64": b64e(self.public_key),
            "client_status": status,
            "agents": self.agents(),
        }
        write_frame(conn, json.dumps(reply, ensure_ascii=False).encode("utf-8"))
        self.state.audit({"event": "hello", "client": client_id, "status": status})

        # 2. 密封调用：未配对（PENDING）一律拒绝，不读后续帧以外的任何内容。
        if status != "APPROVED":
            return
        session_key = derive_session_key(self.private_key, self.public_key, client_pub)
        signing_key = derive_signing_key(session_key)
        sealed = read_frame(conn, timeout=DEFAULT_TIMEOUT_SECONDS + 60)
        try:
            plaintext = open_sealed(session_key, sealed)
        except Exception:
            return  # 验签失败：静默关闭，不泄露原因细节
        try:
            envelope = json.loads(plaintext.decode("utf-8"))
        except Exception:
            return
        self._dispatch(conn, session_key, signing_key, client_id, envelope)

    # -- 调用分发 ------------------------------------------------------------

    def _send(self, conn, session_key, envelope, resp_type, args, seq) -> None:
        resp = {
            "type": resp_type,
            "version": VERSION,
            "seq": seq,
            "task_id": envelope.get("task_id", ""),
            "agent_id": envelope.get("agent_id", ""),
            "source_device_id": envelope.get("target_device_id", ""),
            "target_device_id": envelope.get("source_device_id", ""),
            "folder": envelope.get("folder", ""),
            "action": envelope.get("action", ""),
            "args": args,
        }
        write_frame(conn, seal(session_key, json.dumps(resp, ensure_ascii=False).encode("utf-8")))

    def _error(self, conn, session_key, envelope, seq, code, message) -> None:
        self._send(conn, session_key, envelope, "cross_device_error",
                   {"code": code, "message": message}, seq)

    def _dispatch(self, conn, session_key, signing_key, client_id, envelope) -> None:
        seq = int(envelope.get("seq", 1))
        task_id = str(envelope.get("task_id", ""))
        agent_id = str(envelope.get("agent_id", ""))
        folder = str(envelope.get("folder", ""))
        action = str(envelope.get("action", ""))
        args = envelope.get("args", {}) or {}
        if not isinstance(args, dict):
            args = {}
        if envelope.get("type") != "cross_device_tool_call" or envelope.get("version") != VERSION:
            return
        if action not in ("list", "read", "write", "run"):
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "不支持的操作：跨设备只允许 list/read/write/run")
            return
        if not task_id or not agent_id or not folder:
            self._error(conn, session_key, envelope, seq + 1, ERR_INVALID_CREDENTIAL,
                        "信封缺少 task_id / agent_id / folder")
            return
        if str(envelope.get("target_device_id", "")) != self.device_id:
            self._error(conn, session_key, envelope, seq + 1, ERR_WRONG_TARGET,
                        "连接凭证不属于本设备，请重新选择设备。")
            return
        if str(envelope.get("source_device_id", "")) != client_id:
            self._error(conn, session_key, envelope, seq + 1, ERR_WRONG_SOURCE,
                        "来源设备 ID 与已配对的客户端不匹配。")
            return
        # 凭证校验。
        wire = args.get("credential", "")
        cred = parse_wire_credential(wire) if wire else None
        if cred is None:
            self._error(conn, session_key, envelope, seq + 1, ERR_INVALID_CREDENTIAL,
                        "任务连接凭证无效，请重新下达任务。")
            return
        err = verify_credential(
            cred, signing_key,
            expected_task_id=task_id,
            expected_target_device_id=self.device_id,
            expected_folder=folder,
            expected_agent_id=agent_id,
            authorized_folders=self.authorized_folders(agent_id),
            revoked_jtis=self.revoked_jtis,
            revoked_tasks=self.revoked_tasks,
            now=int(time.time()),
            expected_source_device_id=client_id,
        )
        if err is not None:
            self._error(conn, session_key, envelope, seq + 1, err,
                        _credential_error_message(err))
            return
        # 执行栅栏：args["control"] 快照必须不旧于服务端记录。
        control_err = self._check_fence(task_id, args)
        if control_err is not None:
            code, message = control_err
            self._error(conn, session_key, envelope, seq + 1, code, message)
            return
        # HandClient issues a fresh jti for each call. Consume it once so a
        # captured sealed request cannot replay a write or a control operation.
        with self.used_jtis_lock:
            now = int(time.time())
            for old_jti, expires in list(self.used_jtis.items()):
                if expires <= now:
                    self.used_jtis.pop(old_jti, None)
            if cred["jti"] in self.used_jtis:
                self.state.audit({"event": "replay_rejected", "task": task_id,
                                  "agent": agent_id, "action": action, "client": client_id})
                self._error(conn, session_key, envelope, seq + 1, ERR_REPLAYED_REQUEST,
                            "该任务凭证已使用：重复请求已拒绝。")
                return
            self.used_jtis[cred["jti"]] = int(cred["exp"])
            self.state.save_json("used_credentials.json", self.used_jtis)
        # A newly scoped App task may start at counters above 1 (for example,
        # after authorization was renewed before dispatch). Its signed task
        # credential is the authority for the initial snapshot; later calls
        # must match the Hand's stored counters and cannot roll them back.
        with self.task_meta_lock:
            meta = self.task_meta.setdefault(task_id, {
                "goal_version": int(cred.get("goal_version", 1)),
                "epoch": int(cred.get("epoch", 1)),
            })
            self._persist_task_meta_locked()
            meta = dict(meta)
        is_control = False
        if action == "run":
            try:
                argv = json.loads(args.get("argv", "[]"))
                is_control = isinstance(argv, list) and bool(argv) and argv[0] == "__control__"
            except Exception:
                pass
        if meta.get("stopped") and (action == "write" or (action == "run" and not is_control)):
            self._error(conn, session_key, envelope, seq + 1, ERR_TASK_STOPPED,
                        "任务已停止：执行与写入已拒绝，请先修改目标并重新授权。")
            return
        self.state.audit({"event": "call", "task": task_id, "agent": agent_id,
                          "action": action, "client": client_id})
        if action == "list":
            self._do_list(conn, session_key, envelope, seq, folder, args)
        elif action == "read":
            self._do_read(conn, session_key, envelope, seq, folder, args)
        elif action == "write":
            self._do_write(conn, session_key, envelope, seq, folder, args)
        else:
            args["_credential_jti"] = cred["jti"]
            self._do_run(conn, session_key, envelope, seq, task_id, agent_id, folder, args)

    def _check_fence(self, task_id: str, args: dict) -> tuple[str, str] | None:
        """goal_version/epoch 快照：旧于服务端记录即拒绝（改目标后旧执行不复活）。"""
        raw = args.get("control", "")
        if not raw:
            return None
        try:
            snap = json.loads(raw)
        except Exception:
            return (ERR_STALE_GOAL_VERSION, "控制快照格式不对，请重新下达任务。")
        with self.task_meta_lock:
            meta = self.task_meta.get(task_id)
        if meta is None:
            return None
        meta = dict(meta)
        if int(snap.get("epoch", 1)) < int(meta.get("epoch", 1)):
            return (ERR_STALE_EPOCH, "凭证已过期（授权已变更）：授权版本已更新，请重新发起任务")
        if int(snap.get("goal_version", 1)) < int(meta.get("goal_version", 1)):
            return (ERR_STALE_GOAL_VERSION, "凭证已过期（目标已变更）：任务目标已更新，请重新发起任务")
        return None

    # -- list/read/write --------------------------------------------------------

    def _resolve_path(self, folder: str, rel: str) -> str | None:
        """把相对路径解析到 folder 内；越界（.. 逃出）返回 None。"""
        base = os.path.realpath(folder)
        target = os.path.realpath(os.path.join(base, rel or ""))
        if target != base and not target.startswith(base + os.sep):
            return None
        return target

    def _do_list(self, conn, session_key, envelope, seq, folder, args) -> None:
        target = self._resolve_path(folder, args.get("path", ""))
        if target is None or not os.path.isdir(target):
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "不允许的路径：该路径不在授权文件夹内。")
            return
        try:
            entries = sorted(os.listdir(target))
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"无法列出文件夹：{e.strerror or e}")
            return
        out = []
        for name in entries:
            path = os.path.join(target, name)
            out.append({"name": name, "is_dir": os.path.isdir(path)})
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": json.dumps(out, ensure_ascii=False)}, seq + 1)

    def _do_read(self, conn, session_key, envelope, seq, folder, args) -> None:
        rel = args.get("path", "")
        if not rel:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "读取需要指定 path。")
            return
        target = self._resolve_path(folder, rel)
        if target is None or not os.path.isfile(target):
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "不允许的路径：该路径不在授权文件夹内。")
            return
        try:
            size = os.path.getsize(target)
            if size > MAX_TEXT_BYTES:
                self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                            f"文件太大（{size} 字节，上限 {MAX_TEXT_BYTES} 字节），请分段读取。")
                return
            with open(target, "r", encoding="utf-8", errors="strict") as f:
                content = f.read()
        except (OSError, UnicodeDecodeError) as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"无法读取文件：{e}")
            return
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": content}, seq + 1)

    def _do_write(self, conn, session_key, envelope, seq, folder, args) -> None:
        rel = args.get("path", "")
        content = args.get("content", "")
        if not rel:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "写入需要指定 path。")
            return
        if len(content.encode("utf-8")) > MAX_TEXT_BYTES:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"内容太大（上限 {MAX_TEXT_BYTES} 字节）。")
            return
        target = self._resolve_path(folder, rel)
        if target is None:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "不允许的路径：该路径不在授权文件夹内。")
            return
        try:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8") as f:
                f.write(content)
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"无法写入文件：{e}")
            return
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": f"已写入 {rel}"}, seq + 1)

    # -- run -------------------------------------------------------------------

    def _do_run(self, conn, session_key, envelope, seq, task_id, agent_id, folder, args) -> None:
        try:
            argv = json.loads(args.get("argv", "[]"))
        except Exception:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "argv 格式不对：应为 JSON 数组。")
            return
        if not isinstance(argv, list) or not argv or not all(isinstance(x, str) for x in argv):
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "argv 格式不对：应为非空字符串数组。")
            return
        timeout = args.get("timeout", "")
        try:
            timeout_s = float(timeout) if timeout else DEFAULT_TIMEOUT_SECONDS
        except ValueError:
            timeout_s = DEFAULT_TIMEOUT_SECONDS
        timeout_s = max(1.0, min(timeout_s, 3600.0))
        if argv[0] == "__control__":
            self._do_control(conn, session_key, envelope, seq, task_id, argv)
            return
        if argv[0] != "agent" or len(argv) < 3:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "不允许的命令：请使用白名单内的命令（构建、测试、查看类命令）。")
            return
        run_agent_id = argv[1]
        prompt = argv[2]
        if run_agent_id != agent_id:
            self._error(conn, session_key, envelope, seq + 1, ERR_WRONG_TASK,
                        "连接凭证不属于本任务，请重新下达任务。")
            return
        cfg = self.agent_config(run_agent_id)
        if cfg is None:
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        f"该设备上没有这个 Agent（{run_agent_id}）：请先在设备上确认 Agent 已安装。")
            return
        command = cfg.get("command", [])
        if not command or not os.path.exists(command[0]):
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        f"Agent 入口不存在（{run_agent_id}）：请先在设备上确认 Agent 已安装。")
            return
        full_argv = [
            part.replace("{prompt}", prompt).replace("{task_id}", task_id)
            for part in command
        ]
        env = dict(os.environ)
        env.update(cfg.get("env", {}) or {})
        with self.task_meta_lock:
            meta = self.task_meta.setdefault(task_id, {"goal_version": 1, "epoch": 1})
            meta.update({
                "agent_id": run_agent_id,
                "folder": folder,
                "active_credential_jti": args.get("_credential_jti", ""),
                "control_stopped": False,
            })
            self._persist_task_meta_locked()
        try:
            proc = subprocess.Popen(
                full_argv,
                cwd=folder,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                bufsize=0,  # 二进制无缓冲：下文用 os.read + select 直接读 fd
                env=env,
                start_new_session=True,
            )
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        f"无法启动 Agent：{e}")
            return
        with self.process_lock:
            self.processes[task_id] = proc
        output_lines: list[str] = []
        next_seq = seq + 1
        try:
            deadline = time.time() + timeout_s
            assert proc.stdout is not None
            # select 循环读行：无输出的挂起进程也能按时超时被杀，
            # 不会被阻塞在 stdout 读上。
            import select as _select

            out_fd = proc.stdout.fileno()
            buf = ""
            timed_out = False
            while True:
                if time.time() > deadline:
                    timed_out = True
                    break
                ready, _, _ = _select.select([out_fd], [], [], 0.2)
                if ready:
                    chunk = os.read(out_fd, 65536).decode("utf-8", errors="replace")
                    if not chunk:
                        break
                    buf += chunk
                    while "\n" in buf:
                        line, buf = buf.split("\n", 1)
                        line += "\n"
                        output_lines.append(line)
                        self._send(conn, session_key, envelope, "cross_device_progress",
                                   {"message": line.rstrip("\n")[-1000:]}, next_seq)
                        next_seq += 1
                # A process may exit while bytes are still buffered in its
                # pipe. Keep selecting until read() observes EOF so the last
                # progress/result lines are not dropped.
            if buf:
                output_lines.append(buf)
                self._send(conn, session_key, envelope, "cross_device_progress",
                           {"message": buf[-1000:]}, next_seq)
                next_seq += 1
            if timed_out:
                self._kill_process(task_id)
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                self._error(conn, session_key, envelope, next_seq, ERR_AGENT_TIMEOUT,
                            f"Agent 执行超时（{int(timeout_s)} 秒）：已终止。")
                return
            proc.wait(timeout=max(0.0, deadline - time.time()))
        except (BrokenPipeError, ConnectionError, OSError):
            # 客户端已断开：杀掉子进程，不再回包。
            self._kill_process(task_id)
            return
        finally:
            with self.process_lock:
                self.processes.pop(task_id, None)
            if proc.stdout is not None:
                proc.stdout.close()
        if proc.returncode is not None and proc.returncode < 0:
            # 负退出码 = 被信号杀死。SIGTERM/SIGKILL 且是控制停掉的，
            # 走 remote_controlled_stop；否则按失败。
            if self._was_control_stopped(task_id):
                self._error(conn, session_key, envelope, next_seq,
                            ERR_REMOTE_CONTROLLED_STOP, "已按控制指令停止。")
                return
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_NOT_FOUND,
                        f"Agent 被信号终止（退出码 {proc.returncode}）。")
            return
        output = "".join(output_lines)
        if proc.returncode != 0:
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_NOT_FOUND,
                        f"Agent 执行失败（退出码 {proc.returncode}）："
                        + output[-2000:])
            return
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": output[-MAX_OUTPUT_CHARS:]}, next_seq)

    def _was_control_stopped(self, task_id: str) -> bool:
        with self.task_meta_lock:
            meta = self.task_meta.get(task_id, {})
            return bool(meta.get("control_stopped"))

    def _kill_process(self, task_id: str) -> None:
        with self.process_lock:
            proc = self.processes.get(task_id)
        if proc is not None and proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except (OSError, ProcessLookupError):
                try:
                    proc.terminate()
                except OSError:
                    pass

    # -- 控制 -------------------------------------------------------------------

    def _do_control(self, conn, session_key, envelope, seq, task_id, argv) -> None:
        """argv = ["__control__", pause|resume|stop|change_goal, operationId, newGoal?]
        回执是 result 帧，output 为 JSON {"control":…,"confirmed":bool,"message":…}。"""
        if len(argv) < 3:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "控制指令格式不对：需要操作与 operationId。")
            return
        action = argv[1]
        operation_id = argv[2]
        new_goal = argv[3] if len(argv) > 3 else ""
        if action not in ("pause", "resume", "stop", "change_goal"):
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        f"不支持的控制操作：{action}。")
            return
        with self.process_lock:
            proc = self.processes.get(task_id)
        running = proc is not None and proc.poll() is None
        confirmed = True
        message = ""
        if action == "pause":
            if running:
                assert proc is not None
                try:
                    os.killpg(proc.pid, signal.SIGSTOP)
                    message = "远端执行已暂停。"
                except (OSError, ProcessLookupError):
                    confirmed = False
                    message = "暂停失败：进程已不存在。"
            else:
                message = "任务未在运行，无需暂停。"
        elif action == "resume":
            if running:
                assert proc is not None
                try:
                    os.killpg(proc.pid, signal.SIGCONT)
                    message = "远端执行已继续。"
                except (OSError, ProcessLookupError):
                    confirmed = False
                    message = "继续失败：进程已不存在。"
            else:
                message = "任务未在运行，无需继续。"
        elif action == "stop":
            if running:
                assert proc is not None
                self._kill_process(task_id)
                message = "远端执行已停止。"
            else:
                message = "任务未在运行，无需停止。"
            with self.task_meta_lock:
                meta = self.task_meta.setdefault(task_id, {})
                if running:
                    meta["control_stopped"] = True
                meta["stopped"] = True
                self._persist_task_meta_locked()
        else:  # change_goal
            if running:
                self._kill_process(task_id)
            with self.task_meta_lock:
                meta = self.task_meta.setdefault(task_id, {})
                if running:
                    meta["control_stopped"] = True
                meta["goal_version"] = int(meta.get("goal_version", 1)) + 1
                meta["stopped"] = False
                if new_goal:
                    meta["new_goal"] = new_goal
                self._persist_task_meta_locked()
            message = f"目标已更新（goal_version={meta['goal_version']}），旧执行已停止，请按新目标重新派发。"
            self.state.audit({"event": "change_goal", "task": task_id,
                              "operation": operation_id,
                              "goal_version": meta["goal_version"]})
        if action in ("stop", "change_goal"):
            # 只撤销当次运行的凭证（jti），不撤销整个任务：改目标后
            # 任务要能凭新凭证、新 goal_version 快照重新派发；整任务
            # 撤销会让重派永远失败。
            with self.task_meta_lock:
                jti = self.task_meta.get(task_id, {}).get("active_credential_jti")
            if jti:
                self.revoked_jtis.add(jti)
        result = {"control": action, "confirmed": confirmed, "message": message}
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": json.dumps(result, ensure_ascii=False)}, seq + 1)


def _credential_error_message(code: str) -> str:
    return {
        ERR_INVALID_CREDENTIAL: "任务连接凭证无效，请重新下达任务。",
        ERR_EXPIRED_CREDENTIAL: "任务连接已过期，请重新下达任务。",
        ERR_REVOKED_CREDENTIAL: "凭证已被收回：任务已结束或设备授权已被收回。",
        ERR_WRONG_TASK: "连接凭证不属于本任务，请重新下达任务。",
        ERR_WRONG_TARGET: "连接凭证不属于本设备，请重新选择设备。",
        ERR_WRONG_FOLDER: "连接凭证不允许访问该项目文件夹。",
        ERR_STALE_GOAL_VERSION: "凭证已过期（目标已变更）：任务目标已更新，请重新发起任务",
        ERR_STALE_EPOCH: "凭证已过期（授权已变更）：授权版本已更新，请重新发起任务",
    }.get(code, "跨设备调用失败：请稍后重试。")


# --------------------------------------------------------------------------
# TCP 服务
# --------------------------------------------------------------------------

class ThreadedHandServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address, handler_class, hand_server: HandServer):
        self.hand_server = hand_server
        super().__init__(server_address, handler_class)


class HandConnectionHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.server.hand_server.handle_connection(self.request, self.client_address)


def serve(state_dir: str, device_id: str, display_name: str, host: str, port: int) -> None:
    hand = HandServer(state_dir, device_id, display_name, port)
    with ThreadedHandServer((host, port), HandConnectionHandler, hand) as server:
        print(f"hand server listening on {host}:{port} device={device_id}", flush=True)
        print(f"fingerprint: {hand.fingerprint()}", flush=True)
        print(f"state dir: {hand.state.dir}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def default_agents_path(state_dir: str) -> str:
    return os.path.join(os.path.expanduser(state_dir), "agents.json")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="WhereverCode V5 remote Hand server")
    parser.add_argument("--state-dir", default="~/.wherevercode/hand-server")
    parser.add_argument("--device-id", default=socket.gethostname())
    parser.add_argument("--display-name", default=socket.gethostname())
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("serve", help="启动 Hand 服务")
    sub.add_parser("fingerprint", help="打印服务端公钥指纹（SHA-256）")
    sub.add_parser("public-key", help="打印服务端公钥（标准 Base64；不含私钥）")
    sub.add_parser("pair-list", help="列出已记录的客户端配对")
    approve = sub.add_parser("pair-approve", help="批准一个待配对的客户端")
    approve.add_argument("device_id")
    revoke = sub.add_parser("pair-revoke", help="撤销一个客户端的配对")
    revoke.add_argument("device_id")
    grant = sub.add_parser("grant", help="授权某 agent 访问某文件夹")
    grant.add_argument("agent_id")
    grant.add_argument("folder")
    sub.add_parser("agents", help="列出已注册的 agent 及其可用性")
    sub.add_parser("init-agents", help="写入默认 agents.json（example-agent）")

    args = parser.parse_args(argv)
    hand = HandServer(args.state_dir, args.device_id, args.display_name, args.port)

    if args.command in (None, "serve"):
        serve(args.state_dir, args.device_id, args.display_name, args.host, args.port)
        return 0
    if args.command == "fingerprint":
        print(hand.fingerprint())
        return 0
    if args.command == "public-key":
        print(b64e(hand.public_key))
        return 0
    if args.command == "pair-list":
        clients = hand.state.load_json("paired_clients.json", {})
        print(json.dumps(clients, ensure_ascii=False, indent=2))
        return 0
    if args.command == "pair-approve":
        with hand.state.lock:
            clients = hand.state.load_json("paired_clients.json", {})
            entry = clients.get(args.device_id)
            if entry is None:
                print(f"未知设备：{args.device_id}（先让 App 发一次 hello）", file=sys.stderr)
                return 1
            entry["status"] = "APPROVED"
            hand.state.save_json("paired_clients.json", clients)
        print(f"已批准：{args.device_id}")
        return 0
    if args.command == "pair-revoke":
        with hand.state.lock:
            clients = hand.state.load_json("paired_clients.json", {})
            entry = clients.get(args.device_id)
            if entry is None:
                print(f"未知设备：{args.device_id}", file=sys.stderr)
                return 1
            entry["status"] = "REVOKED"
            hand.state.save_json("paired_clients.json", clients)
        print(f"已撤销：{args.device_id}")
        return 0
    if args.command == "grant":
        with hand.state.lock:
            auth = hand.state.load_json("auth.json", {})
            folders = auth.setdefault(args.agent_id, [])
            if args.folder not in folders:
                folders.append(args.folder)
            hand.state.save_json("auth.json", auth)
        print(f"已授权：{args.agent_id} → {args.folder}")
        return 0
    if args.command == "agents":
        print(json.dumps(hand.agents(), ensure_ascii=False, indent=2))
        return 0
    if args.command == "init-agents":
        path = default_agents_path(args.state_dir)
        if os.path.exists(path):
            print(f"agents.json 已存在：{path}（不覆盖）")
            return 0
        default = {
            "example-agent": {
                "display_name": "Example Agent",
                "kind": "agent",
                "capabilities": ["dev"],
                "folder": "/workspace/example-project",
                "command": ["/workspace/example-project/run-grok.sh", "{prompt}"],
                "env": {},
            }
        }
        hand.state.save_json("agents.json", default)
        print(f"已写入默认 agents.json：{path}")
        return 0
    parser.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
