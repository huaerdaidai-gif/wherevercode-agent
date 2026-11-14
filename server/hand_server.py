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
  tasks.json       task_id → {goal_version, epoch, agent_id, folder}
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
import logging
import os
import shutil
import secrets
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import stat
import tempfile
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
# Hand 自更新（2026-11-12，App 更新后自动同步已连接设备）：
# 更新文件大小上限 2MB（live 文件约 160KB，留足余量；过大拒绝，
# 防止误传/撑爆）。暂存、备份、看门狗文件都以 live 文件路径派生。
MAX_HAND_UPDATE_BYTES = 2 * 1024 * 1024


def hand_code_version() -> str:
    """Hand 代码版本 = 本文件内容 SHA-256 前 12 位。

    与 App 的 HandInstallBundle.versionOf() 同款定义，两边直接比：
    文件改了版本就变，不用手动维护版本号。算不到时返回 ""，
    调用方按“未知版本”处理（不把未知当成最新、也不当成落后）。
    """
    try:
        digest = hashlib.sha256()
        with open(os.path.realpath(__file__), "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                digest.update(chunk)
        return digest.hexdigest()[:12]
    except OSError:
        return ""
MAX_OUTPUT_CHARS = 20_000
MAX_ARTIFACT_BYTES = 1024 * 1024
DEFAULT_PORT = 18743
DEFAULT_TIMEOUT_SECONDS = 600
CREDENTIAL_TTL_SECONDS = 900

ERR_NOT_AUTHORIZED = "cross_device_not_authorized"
ERR_INVALID_CREDENTIAL = "invalid_credential"
ERR_EXPIRED_CREDENTIAL = "expired_credential"
ERR_REVOKED_CREDENTIAL = "revoked_credential"
ERR_WRONG_TASK = "wrong_task"
ERR_WRONG_TARGET = "wrong_target"
ERR_WRONG_FOLDER = "wrong_folder"
ERR_COMMAND_NOT_ALLOWED = "command_not_allowed"
ERR_PATH_NOT_ALLOWED = "path_not_allowed"
ERR_STALE_GOAL_VERSION = "stale_goal_version"
ERR_STALE_EPOCH = "stale_epoch"
ERR_AGENT_NOT_FOUND = "agent_not_found"
ERR_REMOTE_CONTROLLED_STOP = "remote_controlled_stop"
ERR_AGENT_TIMEOUT = "agent_timeout"
ERR_PAIR_CODE_WRONG = "pair_code_wrong"

# 配对码：6 位数字，存 state-dir/pair_code.json（0600），首次启动生成。
# 5 次码错锁定该设备 1 小时（pair_attempts.json），防暴力破解。
PAIR_CODE_LENGTH = 6
PAIR_CODE_MAX_FAILS = 5
PAIR_CODE_LOCK_SECONDS = 3600

# mDNS 广播：局域网发现用的服务类型（与 App 侧 Discovery.SERVICE_TYPE 同款）。
MDNS_SERVICE_TYPE = "_wherevercode._tcp.local"
MDNS_GROUP = "224.0.0.251"
MDNS_PORT = 5353




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
) -> str | None:
    """返回 None 表示通过，否则返回错误码。校验顺序与 App 侧一致：
    签名 → 过期 → 撤销 → 绑定（任务/目标/文件夹）→ 授权仍存在。

    Older paired clients may omit agent_id. When the credential carries
    an agent binding, it must match the requested Agent.
    """
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
    if canonical_folder(cred["folder"]) != canonical_folder(expected_folder):
        return ERR_WRONG_FOLDER
    if canonical_folder(expected_folder) not in [canonical_folder(f) for f in authorized_folders]:
        return ERR_WRONG_FOLDER
    if cred["agent_id"] and cred["agent_id"] != expected_agent_id:
        return ERR_WRONG_TASK
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


# A detached watchdog owns the read end of a pipe held by Hand. EOF
# means the server exited (including SIGKILL) or finished the run.
_PROCESS_WATCHDOG = """
import os, signal, sys
read_fd = int(sys.argv[1])
group = os.getpgrp()
if os.fork() == 0:
    os.setsid()
    for fd in (0, 1, 2):
        try:
            os.close(fd)
        except OSError:
            pass
    try:
        os.read(read_fd, 1)
        os.killpg(group, signal.SIGKILL)
    except ProcessLookupError:
        pass
    finally:
        os._exit(0)
os.close(read_fd)
os.execv(sys.argv[2], sys.argv[2:])
"""



# Dead-peer detection without waiting for a write: a phone that loses power
# or network never sends FIN, so a paused (SIGSTOP) run would otherwise sit
# until its timeout. Keepalive probes make the socket readable with an error
# within ~35s, and the run loops treat a readable connection as closed.
KEEPALIVE_IDLE_SECONDS = 20
KEEPALIVE_INTERVAL_SECONDS = 5
KEEPALIVE_PROBES = 3


def enable_keepalive(conn) -> None:
    try:
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        idle = getattr(socket, "TCP_KEEPIDLE", None) or getattr(socket, "TCP_KEEPALIVE", None)
        if idle is not None:
            conn.setsockopt(socket.IPPROTO_TCP, idle, KEEPALIVE_IDLE_SECONDS)
        if hasattr(socket, "TCP_KEEPINTVL"):
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, KEEPALIVE_INTERVAL_SECONDS)
        if hasattr(socket, "TCP_KEEPCNT"):
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, KEEPALIVE_PROBES)
    except (OSError, AttributeError, ValueError):
        pass  # Best effort: unsupported platforms keep the old behavior.


def spawn_agent(argv, **kwargs):
    read_fd, lease_fd = os.pipe()
    try:
        proc = subprocess.Popen(
            [sys.executable, "-c", _PROCESS_WATCHDOG, str(read_fd), *argv],
            pass_fds=(read_fd,), start_new_session=True, **kwargs,
        )
    except BaseException:
        os.close(lease_fd)
        raise
    finally:
        os.close(read_fd)
    return proc, lease_fd


class HandServer:
    def __init__(self, state_dir: str, device_id: str, display_name: str, port: int = DEFAULT_PORT,
                 config_policy: str = "standalone"):
        if config_policy not in ("standalone", "bundled"):
            raise ValueError("unknown config policy: " + str(config_policy))
        self.config_policy = config_policy
        self.state = StateStore(state_dir)
        self.device_id = device_id
        self.display_name = display_name
        self.port = port
        self.private_key, self.public_key = self._load_or_create_key()
        self.processes: dict[str, subprocess.Popen] = {}  # task_id → 进程
        self.process_lock = threading.RLock()
        self.starting_tasks: set[str] = set()
        self.task_meta: dict[str, dict] = {}  # task_id → {goal_version, epoch}
        self.revoked_jtis: set[str] = set()
        self.revoked_tasks: set[str] = set(self.state.load_json("stopped_tasks.json", []))
        # task_id -> attempt id of the process currently registered for it.
        self.process_attempts: dict[str, str] = {}
        # pids killed because their attempt was superseded (not a user stop).
        self.superseded_pids: set[int] = set()

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

    def get_or_create_pair_code(self) -> str:
        """取现成配对码；没有就生成一个（6 位数字，0600 存盘）。"""
        path = os.path.join(self.state.dir, "pair_code.json")
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            code = str(data.get("code", "")).strip()
            if len(code) == PAIR_CODE_LENGTH and code.isdigit():
                return code
        except (FileNotFoundError, KeyError, ValueError, json.JSONDecodeError):
            pass
        code = "".join(secrets.choice("0123456789") for _ in range(PAIR_CODE_LENGTH))
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"code": code}, f)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        return code

    def rotate_pair_code(self) -> str:
        """轮换配对码（旧码立即失效）；返回新码。"""
        path = os.path.join(self.state.dir, "pair_code.json")
        try:
            os.remove(path)
        except FileNotFoundError:
            pass
        return self.get_or_create_pair_code()

    def check_pair_code(self, code: str, client_id: str) -> tuple[bool, str]:
        """验配对码。返回 (是否通过, 中文原因)。码错记失败次数，
        5 次锁定该设备 1 小时；锁定期间一律不通过。"""
        now = int(time.time())
        with self.state.lock:
            attempts = self.state.load_json("pair_attempts.json", {})
            entry = attempts.get(client_id) or {}
            locked_until = int(entry.get("locked_until", 0) or 0)
            if locked_until > now:
                return False, "配对码尝试次数过多，请 1 小时后再试，或在目标设备上轮换配对码"
            expected = self.get_or_create_pair_code()
            if hmac.compare_digest(code.strip(), expected):
                attempts.pop(client_id, None)
                self.state.save_json("pair_attempts.json", attempts)
                return True, ""
            fails = int(entry.get("fails", 0) or 0) + 1
            entry = {"fails": fails, "locked_until": 0}
            if fails >= PAIR_CODE_MAX_FAILS:
                entry["locked_until"] = now + PAIR_CODE_LOCK_SECONDS
                attempts[client_id] = entry
                self.state.save_json("pair_attempts.json", attempts)
                return False, "配对码连续错 5 次，已锁定 1 小时：请在目标设备上跑 hand_server.py pair-code --rotate 轮换配对码"
            attempts[client_id] = entry
            self.state.save_json("pair_attempts.json", attempts)
            return False, f"配对码不对（已错 {fails} 次，错 5 次锁定 1 小时）：请核对目标设备上的配对码"

    def agents(self) -> list[dict]:
        """hello 用的 Agent 列表：注册表（内置 + 自定义行）解析出的
        Agent + agents.json 里独有的遗留条目，合成一张表。
        字段形状与旧版完全兼容（agent_id、display_name、kind、
        capabilities、folder、available 一个不少），只加两个可选字段：
        source（builtin/custom/agents-json）与 discovered（二进制
        是否在本机找到）。自动发现的 Agent 在这里出现，不用改
        agents.json、不用重启。"""
        out = []
        seen: set[str] = set()
        resolved = resolve_adapters(self.state.dir, self.device_id)
        for agent_id, reg in resolved.items():
            if not reg["discovered"]:
                # 注册表里已知、但本机没装的 Agent 不进 hello（App 只需
                # 看到本机能用的）；完整已知表走 extensions / discover 查。
                continue
            cfg = self.agent_config(agent_id) or {}
            command = cfg.get("command", []) or reg["command"]
            available = bool(command) and os.path.exists(command[0])
            out.append({
                "agent_id": agent_id,
                "display_name": cfg.get("display_name", reg["display_name"]),
                "kind": cfg.get("kind", "agent"),
                "capabilities": cfg.get("capabilities", []),
                "folder": cfg.get("folder", reg["folder"]),
                "available": available,
                "repos": git_repos_for_folder(cfg.get("folder", reg["folder"])),
                "source": reg["source"],
                "discovered": reg["discovered"],
            })
            seen.add(agent_id)
        raw = self.state.load_json("agents.json", {})
        for agent_id, cfg in raw.items():
            if agent_id in seen or not isinstance(cfg, dict):
                continue
            command = cfg.get("command", [])
            available = bool(command) and os.path.exists(command[0])
            out.append({
                "agent_id": agent_id,
                "display_name": cfg.get("display_name", agent_id),
                "kind": cfg.get("kind", "agent"),
                "capabilities": cfg.get("capabilities", []),
                "folder": cfg.get("folder", ""),
                "available": available,
                "repos": git_repos_for_folder(cfg.get("folder", "")),
                "source": "agents-json",
                "discovered": available,
            })
        return out

    def agent_config(self, agent_id: str) -> dict | None:
        """配置冲突：standalone 保留 agents.json command；bundled 注册表优先。
        bundled 下注册表是“怎么跑”的事实来源
        （argv 模板 + 二进制解析 + 登录检查）；agents.json 是遗留
        的设备层覆盖，只覆盖 display_name/kind/capabilities/
        folder/env，以及注册表里没有的 Agent 的整条 command。
        注册表已有的 Agent，agents.json 里的 command 不再生效
        （避免 wrapper 脚本死灰复燃；冲突时记一条审计，不静默覆盖）。"""
        raw = self.state.load_json("agents.json", {})
        legacy = raw.get(agent_id) if isinstance(raw, dict) else None
        reg = resolve_adapters(self.state.dir, self.device_id).get(agent_id)
        if reg is None and not isinstance(legacy, dict):
            return None
        cfg: dict = {}
        if reg is not None:
            cfg = {
                "display_name": reg["display_name"],
                "kind": reg.get("kind", "agent"),
                "execution": reg.get("execution", "cli"),
                "executor": reg.get("executor", ""),
                "capabilities": reg.get("capabilities", []),
                "folder": reg["folder"],
                "command": list(reg["command"]),
                "env": dict(reg.get("env", {}) or {}),
            }
        if isinstance(legacy, dict):
            for key in ("display_name", "kind", "capabilities", "folder", "env"):
                if legacy.get(key) not in (None, "", {}):
                    cfg[key] = legacy[key]
            if reg is None or self.config_policy == "standalone":
                if legacy.get("command"):
                    cfg["command"] = legacy["command"]
            elif legacy.get("command") and legacy["command"] != reg["command"]:
                self.state.audit({
                    "event": "legacy_command_ignored", "agent": agent_id,
                    "note": "agents.json command ignored; registry argv wins"})
        return cfg or None

    def authorized_folders(self, agent_id: str, client_id: str | None = None) -> list[str]:
        """Host-configured folders are shared; project grants belong to a paired client."""
        auth = self.state.load_json("auth.json", {})
        entry = auth.get(agent_id, []) if isinstance(auth, dict) else []
        raw_folders = entry if isinstance(entry, list) else (
            entry.get("folders", []) if isinstance(entry, dict) else [])
        folders = list(raw_folders) if isinstance(raw_folders, list) else []
        if self.config_policy == "bundled":
            reg = resolve_adapters(self.state.dir, self.device_id).get(agent_id)
            automatic = [
                ("registry", reg.get("folder", "") if reg else ""),
                ("agent_config", (self.agent_config(agent_id) or {}).get("folder", "")),
            ]
            explicit = {canonical_folder(f) for f in folders if isinstance(f, str)}
            for source, folder in automatic:
                if isinstance(folder, str) and folder:
                    folders.append(folder)
                    if canonical_folder(folder) not in explicit:
                        # Audit each implicit authorization decision; do not log credentials.
                        self.state.audit({"event": "folder_auto_authorized",
                                          "policy": self.config_policy, "agent": agent_id,
                                          "client": client_id, "source": source,
                                          "folder": canonical_folder(folder)})
        if client_id:
            grants = self.state.load_json("project_grants.json", {})
            folders.extend(grants.get(client_id, {}).get(agent_id, []))
        seen: set[str] = set()
        out: list[str] = []
        for folder in folders:
            if not isinstance(folder, str) or not folder:
                continue
            canon = canonical_folder(folder)
            if canon not in seen:
                seen.add(canon)
                out.append(canon)
        return out

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
            elif not hmac.compare_digest(entry.get("public_key_b64", ""), public_key_b64):
                return "KEY_MISMATCH"
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
        enable_keepalive(conn)
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
            "agents": self.agents(),
            # V5 修复（2026-11-11，hello 通、run 就 reset）：
            # 把调用方在本设备上的配对状态也告诉客户端。旧版 hello
            # 不含这一项，App 只能凭“hello 有回包”就标“远端可用”，
            # 但 PENDING 的客户端 hello 通、run 调用会被拒——hello 与
            # run 的结论对不上，用户只看到 reset。App 凭此项在探测
            # 时就能报“待批准”，不用等到 run 失败才知道。
            "client_status": status,
            # Hand 自更新（2026-11-12）：把本 Hand 的代码版本告诉 App，
            # App 由此判断远端 Hand 落不落后（与 App 内置安装包的版本
            # 同款定义：文件内容 SHA-256 前 12 位）。旧版 App 不识这
            # 两个字段，直接忽略，hello 兼容不变。
            "hand_version": hand_code_version(),
            "update_supported": False,  # S3f migration pending; never advertise a missing endpoint.
            # 可选字段：注册表摘要（App 由此列出七类扩展与自定义项；
            # 不识该字段的旧版 App 直接忽略，hello 兼容不变）。
            "extensions": registry_summary(self.state.dir, self.device_id),
        }
        write_frame(conn, json.dumps(reply, ensure_ascii=False).encode("utf-8"))
        self.state.audit({"event": "hello", "client": client_id, "status": status})

        # 会话密钥先派生：PENDING 也要靠它回一个密封的拒绝错误。
        session_key = derive_session_key(self.private_key, self.public_key, client_pub)
        # 2. 密封调用：未批准（PENDING）只接受一帧密封配对确认
        # （cross_device_pair_confirm，带 6 位配对码）；除此以外
        # 一律拒绝，不执行。已撤销（REVOKED）的不接受配对确认，
        # 须先在服务端解除撤销（pair-approve）。
        if status != "APPROVED":
            self._handle_pending_pairing(conn, client_id, client_pub, status)
            return
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

    def _handle_pending_pairing(
        self, conn: socket.socket, client_id: str, client_pub: bytes, status: str
    ) -> None:
        """未批准客户端的唯一入口：等一帧密封配对确认，别的不执行。

        流程（App 内配对）：App 发 hello → 本机记 PENDING → 用户在
        App 里核对指纹、填入 6 位配对码 → App 在同一连接上发密封
        cross_device_pair_confirm {code} → 码对即 APPROVED，回密封
        cross_device_pair_approved；码不对回密封 cross_device_error
       （code=pair_code_wrong，含中文原因与剩余次数）。
        不是配对确认帧（旧版 App 直接发的 tool_call 等）：
        standalone 保留 A 的直接断连，bundled 保留 B 的密封
        cross_device_not_authorized 回包；两种策略都不分派工具或执行。
        解不开的帧静默关闭，不泄露原因细节。
        已撤销（REVOKED）的客户端不接受配对确认，须先在服务端
        pair-approve 解除撤销。只等 15 秒：App 的配对确认紧跟 hello
        发出，超时即关，不占线程。
        """
        if status in ("REVOKED", "KEY_MISMATCH"):
            return
        try:
            sealed = read_frame(conn, timeout=15)
        except Exception:
            return
        session_key = derive_session_key(self.private_key, self.public_key, client_pub)
        try:
            plaintext = open_sealed(session_key, sealed)
            envelope = json.loads(plaintext.decode("utf-8"))
        except Exception:
            return  # 解不开：静默关闭
        if envelope.get("type") != "cross_device_pair_confirm" or envelope.get("version") != VERSION:
            if self.config_policy == "standalone":
                return  # A compatibility: close without any response or dispatch.
            # 不是配对确认：按未批准拒绝（与 live 版 _reject_pending 同款，
            # 错误形状与 _dispatch 的 cross_device_error 一致：code 在 args 里）。
            try:
                self._send(
                    conn, session_key, envelope, "cross_device_error",
                    {"code": ERR_NOT_AUTHORIZED,
                     "message": "远端尚未批准此手机：请先在 App 里完成配对（核对指纹 + 配对码），或在服务端跑 hand_server.py pair-approve 批准"},
                    1,
                )
            except Exception:
                pass
            return
        code = str(envelope.get("code", "")).strip()
        ok, reason = self.check_pair_code(code, client_id)
        if ok:
            with self.state.lock:
                clients = self.state.load_json("paired_clients.json", {})
                entry = clients.get(client_id)
                if not isinstance(entry, dict):
                    return
                entry["status"] = "APPROVED"
                clients[client_id] = entry
                self.state.save_json("paired_clients.json", clients)
            self.state.audit({"event": "pair_approved", "client": client_id, "via": "pair_code"})
            try:
                self._send(
                    conn, session_key, envelope, "cross_device_pair_approved",
                    {"device_id": self.device_id}, 1,
                )
            except Exception:
                pass
        else:
            self.state.audit({"event": "pair_code_failed", "client": client_id, "reason": reason})
            try:
                self._send(
                    conn, session_key, envelope, "cross_device_error",
                    {"code": ERR_PAIR_CODE_WRONG, "message": reason}, 1,
                )
            except Exception:
                pass

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
        if action not in ("list", "read", "write", "run", "authorize_project_folder", "get_artifact"):
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "不支持的操作：跨设备只允许 list/read/write/run/authorize_project_folder")
            return
        if not task_id or not agent_id or not folder:
            self._error(conn, session_key, envelope, seq + 1, ERR_INVALID_CREDENTIAL,
                        "信封缺少 task_id / agent_id / folder")
            return
        if str(envelope.get("target_device_id", "")) != self.device_id:
            self._error(conn, session_key, envelope, seq + 1, ERR_WRONG_TARGET,
                        "连接凭证不属于本设备，请重新选择设备。")
            return
        # 凭证校验。
        wire = args.get("credential", "")
        cred = parse_wire_credential(wire) if wire else None
        if cred is None:
            self._error(conn, session_key, envelope, seq + 1, ERR_INVALID_CREDENTIAL,
                        "任务连接凭证无效，请重新下达任务。")
            return
        if (cred["source_device_id"] != client_id
                or envelope.get("source_device_id") != client_id
                or cred["agent_id"] != agent_id):
            self._error(conn, session_key, envelope, seq + 1, ERR_WRONG_TASK,
                        "连接凭证不属于此设备。")
            return
        err = verify_credential(
            cred, signing_key,
            expected_task_id=task_id,
            expected_target_device_id=self.device_id,
            expected_folder=folder,
            expected_agent_id=agent_id,
            authorized_folders=self.authorized_folders(agent_id, client_id),
            revoked_jtis=self.revoked_jtis,
            revoked_tasks=set() if self._is_stop_call(action, args) else self.revoked_tasks,
            now=int(time.time()),
        )
        if err is not None:
            self._error(conn, session_key, envelope, seq + 1, err,
                        _credential_error_message(err))
            return
        if not os.path.isabs(folder) or os.path.realpath(folder) != os.path.realpath(cred["folder"]):
            self._error(conn, session_key, envelope, seq + 1, ERR_WRONG_FOLDER,
                        _credential_error_message(ERR_WRONG_FOLDER))
            return
        if not any(os.path.realpath(folder) == os.path.realpath(grant) for grant in
                   self.authorized_folders(agent_id, client_id)):
            self._error(conn, session_key, envelope, seq + 1, ERR_WRONG_FOLDER,
                        _credential_error_message(ERR_WRONG_FOLDER))
            return
        if action == "run":
            # Reserve the task identity before a run or control can touch its process.
            owner = (client_id, agent_id, os.path.realpath(folder))
            with self.process_lock:
                meta = self.task_meta.setdefault(task_id, {})
                if meta.setdefault("owner", owner) != owner:
                    control_err = (ERR_WRONG_TASK, _credential_error_message(ERR_WRONG_TASK))
                else:
                    control_err = self._check_fence(task_id, args)
            if control_err is not None:
                code, message = control_err
                self._error(conn, session_key, envelope, seq + 1, code, message)
                return
        self.state.audit({"event": "call", "task": task_id, "agent": agent_id,
                          "action": action, "client": client_id})
        if action == "authorize_project_folder":
            self._do_authorize_project_folder(
                conn, session_key, envelope, seq, agent_id, args, client_id)
        elif action == "get_artifact":
            self._get_artifact(conn, session_key, envelope, seq, client_id, args)
        elif action == "list":
            self._do_list(conn, session_key, envelope, seq, folder, args)
        elif action == "read":
            self._do_read(conn, session_key, envelope, seq, folder, args)
        elif action == "write":
            self._do_write(conn, session_key, envelope, seq, folder, args)
        else:
            self._do_run(conn, session_key, envelope, seq, task_id, agent_id, folder, args, client_id)

    def _do_authorize_project_folder(
        self, conn, session_key, envelope, seq, agent_id, args, client_id,
    ) -> None:
        """Save a project grant for the authenticated device and Agent."""
        requested = args.get("folder", "")
        if not isinstance(requested, str) or not requested or not os.path.isabs(requested):
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "授权目录必须是已存在的绝对路径。")
            return
        canonical = os.path.realpath(requested)
        if canonical == os.path.abspath(os.sep) or not os.path.isdir(canonical):
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "授权目录不存在或不是文件夹。")
            return
        with self.state.lock:
            grants = self.state.load_json("project_grants.json", {})
            folders = grants.setdefault(client_id, {}).setdefault(agent_id, [])
            already = canonical in folders
            if not already:
                folders.append(canonical)
                self.state.save_json("project_grants.json", grants)
        self.state.audit({"event": "folder_authorized", "agent": agent_id,
                          "folder": canonical, "already": already,
                          "task": envelope.get("task_id", "")})
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": json.dumps({"authorized": True,
                                          "requested_folder": requested,
                                          "agent_id": agent_id,
                                          "already": already,
                                          "folder": canonical}, ensure_ascii=False)},
                   seq + 1)

    @staticmethod
    def _is_stop_call(action: str, args: dict) -> bool:
        if action != "run":
            return False
        try:
            argv = json.loads(args.get("argv", "[]"))
        except (TypeError, ValueError):
            return False
        return isinstance(argv, list) and argv[:2] == ["__control__", "stop"]

    def _check_fence(self, task_id: str, args: dict) -> tuple[str, str] | None:
        """goal_version/epoch 快照：旧于服务端记录即拒绝（改目标后旧执行不复活）。"""
        try:
            snap = json.loads(args.get("control") or "{}")
            epoch = int(snap.get("epoch", 1))
            goal_version = int(snap.get("goal_version", 1))
        except (AttributeError, TypeError, ValueError, OverflowError):
            return (ERR_STALE_GOAL_VERSION, "控制快照格式不对，请重新下达任务。")
        meta = self.task_meta.get(task_id, {})
        if epoch < meta.get("epoch", 1):
            return (ERR_STALE_EPOCH, "凭证已过期（授权已变更）：授权版本已更新，请重新发起任务")
        if goal_version < meta.get("goal_version", 1):
            return (ERR_STALE_GOAL_VERSION, "凭证已过期（目标已变更）：任务目标已更新，请重新发起任务")
        return None

    # -- list/read/write --------------------------------------------------------

    def _resolve_path(self, folder: str, rel: str) -> str | None:
        """把相对路径解析到 folder 内；越界（.. 逃出）返回 None。"""
        if not isinstance(rel, str) or os.path.isabs(rel) or ".." in rel.split(os.sep):
            return None
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

    def _do_run(self, conn, session_key, envelope, seq, task_id, agent_id, folder, args, client_id) -> None:
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
            self._do_control(conn, session_key, envelope, seq, task_id, argv, args)
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
        reg = resolve_adapters(self.state.dir, self.device_id).get(run_agent_id, {})
        if reg.get("execution", "cli") == "internal":
            # App 自身：执行在 App 内，不经过设备 Hand。
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        "该 Agent 在 App 内执行（BrainEngine + TaskScheduler + "
                        "ChatTaskDispatcher），不经过设备 Hand；"
                        "请在 App 内直接指派。")
            return
        command = cfg.get("command", [])
        if not command or not os.path.exists(command[0]):
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        f"Agent 入口不存在（{run_agent_id}）：请先在设备上确认 Agent 已安装。")
            return
        # 执行：走统一分派（按注册行 execution：cli 走子进程，
        # internal 走 App 内任务引擎——后者在 Hand 上抛错，见上）。
        # 旧的 run-grok.sh / run-codex.sh wrapper 不再使用：grok 直接调
        # 二进制（--always-approve --sandbox workspace --output-format plain，
        # $2 为空的 bug 不再存在），codex 直接调二进制（-s workspace-write
        # 保留，stdin 恒为 /dev/null）。日志副本只对 grok 写一份（见
        # 注册表的 log_template），不是 shell 重定向。
        log_path = adapter_log_path(reg, self.device_id, task_id) if reg else None
        attempt_id = args.get("attempt", "")
        if not isinstance(attempt_id, str) or len(attempt_id) > 64:
            self._error(conn, session_key, envelope, seq + 1, "artifact_invalid", "执行轮次格式错误。")
            return
        staging = None
        with self.process_lock:
            if task_id in self.revoked_tasks:
                self._error(conn, session_key, envelope, seq + 1, ERR_REVOKED_CREDENTIAL,
                            "任务已停止。")
                return
            # A new attempt id means the App released the old attempt (its
            # ledger only re-claims after the old lease is gone): the old
            # process, possibly SIGSTOPped, must not survive next to it.
            if (attempt_id and task_id in self.processes
                    and self.process_attempts.get(task_id, "") != attempt_id
                    and self._check_fence(task_id, args) is None):
                self._supersede_stale_process(task_id, "new_attempt")
            if task_id in self.starting_tasks or task_id in self.processes:
                self._error(conn, session_key, envelope, seq + 1, "task_in_flight",
                            "该任务已有执行正在进行。")
                return
            meta = self.task_meta.get(task_id, {})
            if meta.get("paused") and attempt_id and meta.get("paused_attempt", "") != attempt_id:
                # The pause belonged to an earlier attempt; the App only
                # claims a new attempt after resuming the task.
                meta["paused"] = False
                meta.pop("paused_attempt", None)
            if meta.get("paused"):
                self._error(conn, session_key, envelope, seq + 1, "task_paused", "任务已暂停。")
                return
            fence_error = self._check_fence(task_id, args)
            if fence_error is not None:
                self._error(conn, session_key, envelope, seq + 1, *fence_error)
                return
            if attempt_id:
                path = self._artifact_path(envelope, client_id, attempt_id)
                if os.path.isfile(path):
                    self._get_artifact(conn, session_key, envelope, seq, client_id, args)
                    return
                os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
                try:
                    fd = os.open(path + ".started", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                except FileExistsError:
                    self._error(conn, session_key, envelope, seq + 1, "stale_attempt",
                                "此轮次已执行且没有成功回执，请重新认领。")
                    return
                os.close(fd)
                staging = tempfile.TemporaryDirectory(prefix="hand-delivery-")
                prompt += ("\n\n如有文件产物，把单个文件写入以下绝对路径（最多 1 MiB）："
                           + os.path.join(staging.name, "delivery.bin"))
            self.starting_tasks.add(task_id)
            meta = self.task_meta.setdefault(task_id, {})
            snap = json.loads(args.get("control") or "{}")
            meta.update(goal_version=int(snap.get("goal_version", 1)),
                        epoch=int(snap.get("epoch", 1)), control_stopped=False,
                        agent_id=run_agent_id, folder=folder)
        next_seq = seq + 1
        def _on_line(line: str) -> None:
            nonlocal next_seq
            message = line.rstrip("\n")[-1000:]
            self._send(conn, session_key, envelope, "cross_device_progress",
                       {"message": message}, next_seq)
            next_seq += 1

        owned_proc = []

        def _register_proc(proc) -> None:
            with self.process_lock:
                owned_proc.append(proc)
                self.processes[task_id] = proc
                self.process_attempts[task_id] = attempt_id
                if task_id in self.revoked_tasks:
                    self.task_meta[task_id]["control_stopped"] = True
                    self._kill_process(task_id)

        try:
            entry = dict(reg)
            entry["command"] = command
            returncode, output, timed_out = run_agent_entry(
                entry, prompt, task_id, folder,
                env_extra=cfg.get("env", {}) or {}, timeout_s=timeout_s,
                on_line=_on_line, log_path=log_path,
                register_proc=_register_proc, connection=conn,
                on_log_warning=self.state.audit)
            result = {"output": output[-MAX_OUTPUT_CHARS:]}
            if returncode == 0 and not timed_out and staging is not None:
                with self.process_lock:
                    if not self._was_control_stopped(task_id):
                        result = self._save_artifact(envelope, client_id, attempt_id, staging.name, result["output"])
        except ValueError:
            self._error(conn, session_key, envelope, next_seq, "artifact_invalid", "产物类型或大小不受支持。")
            return
        except InternalAgentExecution as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        str(e))
            return
        except (BrokenPipeError, ConnectionError):
            # 客户端已断开：杀掉子进程，不再回包。
            # 注意顺序：BrokenPipeError/ConnectionError 都是 OSError 的
            # 子类，必须放在 except OSError 之前，否则这个分支永远是
            # 死代码（旧版就是这个 bug：断开时 _kill_process 没跑到，
            # _error 里再抛一次才由外层关连接）。
            # 只杀本次执行自己的进程：任务可能已被新轮次接管。
            for own in owned_proc:
                self._kill_proc(own)
            return
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        f"无法启动 Agent：{e}")
            return
        finally:
            if staging is not None:
                staging.cleanup()
            with self.process_lock:
                mine = owned_proc[0] if owned_proc else None
                self._release_if_owned(task_id, mine)
                superseded = mine is not None and mine.pid in self.superseded_pids
                if mine is not None:
                    self.superseded_pids.discard(mine.pid)
        if superseded:
            self._error(conn, session_key, envelope, next_seq, "stale_attempt",
                        "此执行轮次已被新的轮次取代，已终止。")
            return
        if timed_out:
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_TIMEOUT,
                        f"Agent 执行超时（{int(timeout_s)} 秒）：已终止。")
            return
        if returncode is not None and returncode < 0:
            # 负退出码 = 被信号杀死。SIGTERM/SIGKILL 且是控制停掉的，
            # 走 remote_controlled_stop；否则按失败。
            if self._was_control_stopped(task_id):
                self._error(conn, session_key, envelope, next_seq,
                            ERR_REMOTE_CONTROLLED_STOP, "已按控制指令停止。")
                return
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_NOT_FOUND,
                        f"Agent 被信号终止（退出码 {returncode}）。")
            return
        if returncode != 0:
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_NOT_FOUND,
                        f"Agent 执行失败（退出码 {returncode}）："
                        + output[-2000:])
            return
        if self._was_control_stopped(task_id):
            self._error(conn, session_key, envelope, next_seq, ERR_REMOTE_CONTROLLED_STOP, "已按控制指令停止。")
            return
        self._send(conn, session_key, envelope, "cross_device_result", result, next_seq)

    def _artifact_path(self, envelope, client_id, attempt_id):
        identity = [client_id, envelope["agent_id"], envelope["task_id"],
                    os.path.realpath(envelope["folder"]), attempt_id]
        key = hashlib.sha256(json.dumps(identity).encode("utf-8")).hexdigest()
        return os.path.join(self.state.dir, "artifacts", key + ".json")

    # Bounded retry for transient local I/O while persisting/reading a
    # delivery record (e.g. a busy or briefly unavailable state disk).
    ARTIFACT_IO_RETRY_DELAYS = (0.05, 0.1, 0.2)

    @staticmethod
    def _verify_artifact_record(record) -> str | None:
        """Integrity check of a stored delivery record; returns a reason or None."""
        if not isinstance(record, dict) or not isinstance(record.get("output"), str):
            return "record"
        if record.get("artifact_present") != "true":
            return None
        encoded, digest = record.get("artifact_b64"), record.get("artifact_sha256")
        if not isinstance(encoded, str) or not isinstance(digest, str):
            return "fields"
        try:
            content = base64.b64decode(encoded, validate=True)
        except (ValueError, TypeError):
            return "encoding"
        if len(content) > MAX_ARTIFACT_BYTES:
            return "size"
        size = record.get("artifact_size")
        if size is not None and str(size) != str(len(content)):
            return "size"
        if not hmac.compare_digest(hashlib.sha256(content).hexdigest(), digest):
            return "sha256"
        return None

    def _get_artifact(self, conn, session_key, envelope, seq, client_id, args):
        attempt = args.get("attempt", "")
        if not isinstance(attempt, str) or not 1 <= len(attempt) <= 64:
            self._error(conn, session_key, envelope, seq + 1, "artifact_invalid", "产物请求缺少执行轮次。")
            return
        path = self._artifact_path(envelope, client_id, attempt)
        record = None
        for delay in (0.0, *self.ARTIFACT_IO_RETRY_DELAYS):
            if delay:
                time.sleep(delay)
            try:
                with open(path, encoding="utf-8") as f:
                    record = json.load(f)
                break
            except FileNotFoundError:
                self._error(conn, session_key, envelope, seq + 1, "artifact_missing", "此轮次没有已交付产物。")
                return
            except (OSError, ValueError):
                record = None
        if record is None:
            self._error(conn, session_key, envelope, seq + 1, "artifact_unavailable", "暂时无法读取已交付产物。")
            return
        problem = self._verify_artifact_record(record)
        if problem is not None:
            self.state.audit({"event": "artifact_corrupt", "task": envelope.get("task_id", ""),
                              "attempt": attempt, "check": problem})
            self._error(conn, session_key, envelope, seq + 1, "artifact_corrupt",
                        "已交付产物校验失败，无法安全交付。")
            return
        self._send(conn, session_key, envelope, "cross_device_result", record, seq + 1)

    def _save_artifact(self, envelope, client_id, attempt_id, staging, output):
        # Open the fixed name relative to a directory descriptor. A symlink,
        # directory or FIFO supplied by the runner is never a delivery.
        directory = os.open(staging, os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            try:
                fd = os.open("delivery.bin", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                             dir_fd=directory)
            except FileNotFoundError:
                fd = None
            except OSError as e:
                raise ValueError("cannot open artifact") from e
            content = None
            if fd is not None:
                with os.fdopen(fd, "rb") as artifact:
                    info = os.fstat(artifact.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                        raise ValueError("invalid artifact type")
                    content = artifact.read(MAX_ARTIFACT_BYTES + 1)
                if len(content) > MAX_ARTIFACT_BYTES:
                    raise ValueError("artifact too large")
        finally:
            os.close(directory)
        record = {"output": output, "artifact_present": "false"}
        if content is not None:
            record.update(artifact_present="true", artifact_b64=b64e(content),
                          artifact_sha256=hashlib.sha256(content).hexdigest(),
                          artifact_size=str(len(content)))
        path = self._artifact_path(envelope, client_id, attempt_id)
        last_error = None
        for delay in (0.0, *self.ARTIFACT_IO_RETRY_DELAYS):
            if delay:
                time.sleep(delay)
            try:
                self._write_artifact_record(path, record)
                last_error = None
                break
            except OSError as e:
                last_error = e
        if last_error is not None:
            raise last_error
        return record

    def _write_artifact_record(self, path, record) -> None:
        """Atomic, durable write; the stored record is re-read and verified."""
        os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8",
                                         dir=os.path.dirname(path), delete=False) as f:
            tmp = f.name
            try:
                json.dump(record, f)
                f.flush()
                os.fsync(f.fileno())
                os.replace(tmp, path)
            finally:
                if os.path.exists(tmp):
                    os.unlink(tmp)
        try:
            dir_fd = os.open(os.path.dirname(path), os.O_RDONLY)
            try:
                os.fsync(dir_fd)
            finally:
                os.close(dir_fd)
        except OSError:
            pass  # Directory fsync is not supported everywhere.
        with open(path, encoding="utf-8") as f:
            if json.load(f) != record:
                raise OSError("delivery record verification failed")

    def _was_control_stopped(self, task_id: str) -> bool:
        meta = self.task_meta.get(task_id, {})
        return bool(meta.get("control_stopped"))

    def _kill_process(self, task_id: str) -> None:
        with self.process_lock:
            proc = self.processes.get(task_id)
        if proc is not None and proc.poll() is None:
            try:
                # SIGTERM remains pending while a process is SIGSTOP'ed.
                # Kill the group without resuming paused user code.
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    # -- 控制 -------------------------------------------------------------------


    # -- Dead-attempt process cleanup ------------------------------------------

    def _kill_proc(self, proc) -> None:
        """Kill exactly this process group (never whatever now runs the task)."""
        if proc is not None and proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass

    def _supersede_stale_process(self, task_id: str, reason: str) -> bool:
        """Kill the process of a dead attempt so it can never be resumed or
        keep running next to a newer attempt. Caller holds process_lock."""
        proc = self.processes.get(task_id)
        if proc is None:
            return False
        self.superseded_pids.add(proc.pid)
        self._kill_proc(proc)
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        self.processes.pop(task_id, None)
        self.process_attempts.pop(task_id, None)
        self.starting_tasks.discard(task_id)
        meta = self.task_meta.setdefault(task_id, {})
        meta["paused"] = False
        meta.pop("paused_attempt", None)
        self.state.audit({"event": "stale_attempt_killed", "task": task_id, "reason": reason})
        return True

    def _release_if_owned(self, task_id: str, proc) -> None:
        """Run-thread cleanup: only release bookkeeping this run still owns."""
        if self.processes.get(task_id) is proc:
            self.processes.pop(task_id, None)
            self.process_attempts.pop(task_id, None)
            self.starting_tasks.discard(task_id)

    def _do_control(self, conn, session_key, envelope, seq, task_id, argv, args=None) -> None:
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
            if action == "stop":
                self.task_meta.setdefault(task_id, {})["control_stopped"] = True
                self.revoked_tasks.add(task_id)
                self.state.save_json("stopped_tasks.json", sorted(self.revoked_tasks))
            if task_id in self.starting_tasks and proc is None:
                self._send(conn, session_key, envelope, "cross_device_result",
                           {"output": json.dumps({"control": action, "confirmed": False,
                                                  "message": "任务正在启动，请重试。"})}, seq + 1)
                return
            running = proc is not None and proc.poll() is None
            try:
                snap = json.loads((args or {}).get("control") or "{}")
            except (AttributeError, TypeError, ValueError):
                snap = {}
            # Newer Apps name the live attempt in the control snapshot. A
            # running process of any other attempt is a leftover of a dead
            # attempt: kill it instead of pausing or waking it (SIGCONT would
            # let it run next to the re-dispatched attempt).
            if (running and action in ("pause", "resume") and isinstance(snap, dict)
                    and "attempt" in snap
                    and self.process_attempts.get(task_id, "") != str(snap.get("attempt") or "")):
                self._supersede_stale_process(task_id, f"control_{action}")
                self._send(conn, session_key, envelope, "cross_device_result",
                           {"output": json.dumps({"control": action, "confirmed": True,
                                                  "message": "旧执行轮次已作废并终止，不会被继续。"},
                                                 ensure_ascii=False)}, seq + 1)
                return
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
                    self.task_meta.setdefault(task_id, {})["control_stopped"] = True
                    self._kill_process(task_id)
                    try:
                        proc.wait(timeout=5)
                        message = "远端执行已停止。"
                    except subprocess.TimeoutExpired:
                        confirmed = False
                        message = "执行进程尚未退出，请重试。"
                else:
                    message = "任务未在运行，无需停止。"
            else:  # change_goal
                if running:
                    self.task_meta.setdefault(task_id, {})["control_stopped"] = True
                    self._kill_process(task_id)
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        confirmed = False
                meta = self.task_meta.setdefault(task_id, {})
                meta["goal_version"] = int(meta.get("goal_version", 1)) + 1
                if new_goal:
                    meta["new_goal"] = new_goal
                message = f"目标已更新（goal_version={meta['goal_version']}），旧执行已停止，请按新目标重新派发。"
                self.state.audit({"event": "change_goal", "task": task_id,
                                  "operation": operation_id,
                                  "goal_version": meta["goal_version"]})
            if confirmed and action in ("pause", "resume"):
                meta = self.task_meta.setdefault(task_id, {})
                meta["paused"] = action == "pause"
                if action == "pause":
                    meta["paused_attempt"] = self.process_attempts.get(task_id, "")
                else:
                    meta.pop("paused_attempt", None)
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


# --------------------------------------------------------------------------
# mDNS 广播（局域网发现，stdlib-only，无第三方依赖）
# --------------------------------------------------------------------------
# Hand 启动后广播 `_wherevercode._tcp`，App 的 NsdManager 发现即列出。
# 只做最小实现：响应 PTR/SRV/TXT/A 查询 + 启动时与每 120 秒主动宣告。
# TXT 只含设备 id 与公钥指纹（可公开显示的摘要，不是密钥）与能力；
# 配对码绝不进 TXT。广播失败（端口被占、无组播等）只记日志，
# 绝不影响主服务。


def _dns_encode_name(name: str) -> bytes:
    out = b""
    for label in name.split("."):
        if not label:
            continue
        raw = label.encode("utf-8")
        out += bytes([len(raw)]) + raw
    return out + b"\x00"


def _dns_decode_name(msg: bytes, off: int) -> tuple[str, int]:
    labels: list[str] = []
    jumped = False
    next_off = off
    for _ in range(32):
        if off >= len(msg):
            break
        length = msg[off]
        if length == 0:
            off += 1
            if not jumped:
                next_off = off
            break
        if length & 0xC0 == 0xC0:
            if off + 1 >= len(msg):
                break
            pointer = ((length & 0x3F) << 8) | msg[off + 1]
            if not jumped:
                next_off = off + 2
            off = pointer
            jumped = True
            continue
        off += 1
        if off + length > len(msg):
            break
        labels.append(msg[off:off + length].decode("utf-8", "replace"))
        off += length
    return ".".join(labels), next_off


def _mdns_build_response(
    instance: str, hostname: str, lan_ip: str | None, port: int,
    device_id: str, fingerprint: str,
) -> bytes:
    """宣告/应答共用的 DNS 报文：PTR + SRV + TXT (+ A)。不用名字压缩。"""
    service = MDNS_SERVICE_TYPE
    records: list[bytes] = []
    # PTR: _wherevercode._tcp.local → <instance>._wherevercode._tcp.local
    target = _dns_encode_name(instance + "." + service)
    records.append(
        _dns_encode_name(service) + struct.pack(">HHIH", 12, 1, 120, len(target)) + target
    )
    # SRV: <instance> → 0 0 <port> <hostname>.local
    srv_target = _dns_encode_name(hostname + ".local")
    srv_rdata = struct.pack(">HHH", 0, 0, port) + srv_target
    records.append(
        _dns_encode_name(instance + "." + service)
        + struct.pack(">HHIH", 33, 1, 120, len(srv_rdata)) + srv_rdata
    )
    # TXT: id / fp / caps（长度字节 + 键值串）。
    txt_parts = []
    for kv in (f"id={device_id}", f"fp={fingerprint}", "caps=hand"):
        raw = kv.encode("utf-8")
        txt_parts.append(bytes([len(raw)]) + raw)
    txt_rdata = b"".join(txt_parts)
    records.append(
        _dns_encode_name(instance + "." + service)
        + struct.pack(">HHIH", 16, 1, 120, len(txt_rdata)) + txt_rdata
    )
    # A: <hostname>.local → LAN IP（拿不到 IP 时不发 A 记录）。
    if lan_ip:
        try:
            a_rdata = socket.inet_aton(lan_ip)
            records.append(
                _dns_encode_name(hostname + ".local")
                + struct.pack(">HHIH", 1, 1, 120, len(a_rdata)) + a_rdata
            )
        except OSError:
            pass
    header = struct.pack(">HHHHHH", 0, 0x8400, 0, len(records), 0, 0)
    return header + b"".join(records)


def _mdns_lan_ip() -> str | None:
    """本机 LAN IP（只建 UDP socket 问路由，不发任何流量）。"""
    for target in ("192.168.1.1", "10.0.0.1", "8.8.8.8"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect((target, 80))
                ip = s.getsockname()[0]
            finally:
                s.close()
            if ip and not ip.startswith("127."):
                return ip
        except OSError:
            continue
    return None


def _mdns_safe_label(value: str) -> str:
    """DNS label 安全化：只留字母数字与 -/_，其余改 -，空了回落 host。"""
    out = "".join(c if c.isalnum() or c in ("-", "_") else "-" for c in value).strip("-")
    return out or "hand"


class MdnsAdvertiser(threading.Thread):
    """mDNS 广播线程（daemon，失败自停，不杀主服务）。

    行为：绑定 224.0.0.251:5353，收查询、回 PTR/SRV/TXT/A；
    启动时宣告一次、之后每 120 秒再宣告一次（被动发现者也能看到）。
    """

    def __init__(self, hand: "HandServer") -> None:
        super().__init__(daemon=True, name="mdns-advertiser")
        self.hand = hand
        self.instance = _mdns_safe_label(hand.device_id)
        self.hostname = _mdns_safe_label(socket.gethostname())
        self.lan_ip = _mdns_lan_ip()
        self.sock: socket.socket | None = None
        self.stopped = threading.Event()

    def _announce(self) -> None:
        if self.sock is None:
            return
        try:
            packet = _mdns_build_response(
                self.instance, self.hostname, self.lan_ip, self.hand.port,
                self.hand.device_id, self.hand.fingerprint(),
            )
            self.sock.sendto(packet, (MDNS_GROUP, MDNS_PORT))
        except OSError:
            pass

    def _answer(self, msg: bytes, addr) -> None:
        if len(msg) < 12 or (msg[2] & 0x80):
            return  # 不是查询不回
        qdcount = struct.unpack(">H", msg[4:6])[0]
        off = 12
        asked = False
        for _ in range(qdcount):
            name, off = _dns_decode_name(msg, off)
            if off + 4 > len(msg):
                return
            qtype = struct.unpack(">H", msg[off:off + 2])[0]
            off += 4
            if qtype in (12, 33, 16, 1, 255) and name in (
                MDNS_SERVICE_TYPE,
                self.instance + "." + MDNS_SERVICE_TYPE,
                self.hostname + ".local",
            ):
                asked = True
        if not asked:
            return
        self._announce()

    def run(self) -> None:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("", MDNS_PORT))
            except OSError:
                # 5353 已被系统 mDNS 服务占住：广播不了，只记日志、不报错。
                print("mDNS: 端口 5353 已被占用，局域网广播停用（手动 IP 配对仍可用）", flush=True)
                return
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
            mreq = socket.inet_aton(MDNS_GROUP) + socket.inet_aton("0.0.0.0")
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
            sock.settimeout(5)
            self.sock = sock
        except OSError as e:
            print(f"mDNS: 广播启动失败（{e}），局域网发现停用", flush=True)
            return
        self._announce()
        last_announce = time.monotonic()
        while not self.stopped.is_set():
            try:
                msg, addr = self.sock.recvfrom(2048)
                self._answer(msg, addr)
            except socket.timeout:
                pass
            except OSError:
                break
            if time.monotonic() - last_announce >= 120:
                self._announce()
                last_announce = time.monotonic()
        try:
            self.sock.close()
        except OSError:
            pass


def start_mdns_advertiser(hand: "HandServer") -> MdnsAdvertiser | None:
    """启动 mDNS 广播（best-effort）：任何异常都只记日志、不抛错。"""
    try:
        adv = MdnsAdvertiser(hand)
        adv.start()
        return adv
    except Exception as e:  # noqa: BLE001 - 广播绝不能拖垮主服务
        print(f"mDNS: 广播启动失败（{e}），局域网发现停用", flush=True)
        return None


def serve(state_dir: str, device_id: str, display_name: str, host: str, port: int,
          config_policy: str = "standalone") -> None:
    hand = HandServer(state_dir, device_id, display_name, port, config_policy)
    with ThreadedHandServer((host, port), HandConnectionHandler, hand) as server:
        print(f"hand server listening on {host}:{port} device={device_id}", flush=True)
        print(f"fingerprint: {hand.fingerprint()}", flush=True)
        print(f"pair code: {hand.get_or_create_pair_code()}（App 配对时填入；轮换：hand_server.py pair-code --rotate）", flush=True)
        print(f"state dir: {hand.state.dir}", flush=True)
        start_mdns_advertiser(hand)
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
    parser.add_argument("--config-policy", choices=("standalone", "bundled"), default="standalone",
                        help="standalone: explicit grants/legacy command; bundled: registry defaults (audited)")
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("serve", help="启动 Hand 服务")
    sub.add_parser("fingerprint", help="打印服务端公钥指纹（SHA-256）")
    sub.add_parser("pair-list", help="列出已记录的客户端配对")
    approve = sub.add_parser("pair-approve", help="批准一个待配对的客户端")
    approve.add_argument("device_id")
    pc = sub.add_parser("pair-code", help="查看本机配对码（App 内配对时填入）")
    pc.add_argument("--rotate", action="store_true", help="生成新配对码（旧码立即失效）")
    revoke = sub.add_parser("pair-revoke", help="撤销一个客户端的配对")
    revoke.add_argument("device_id")
    grant = sub.add_parser("grant", help="授权某 agent 访问某文件夹")
    grant.add_argument("agent_id")
    grant.add_argument("folder")
    sub.add_parser("agents", help="列出已注册的 agent 及其可用性")
    sub.add_parser("init-agents", help="写入默认 agents.json（example-agent）")

    # 统一扩展注册表 CLI：注册 / 发现 / 启用三步的入口。
    ext_list = sub.add_parser("extensions", help="列出注册表里的全部扩展（七类）")
    ext_list.add_argument("--kind", default=None,
                          choices=list(EXTENSION_KINDS))
    ext = sub.add_parser("extension", help="注册 / 启用 / 停用 / 删除一个扩展")
    ext_sub = ext.add_subparsers(dest="ext_action")
    reg = ext_sub.add_parser("register", help="注册一个扩展（写自定义行）")
    reg.add_argument("--kind", required=True, choices=list(EXTENSION_KINDS))
    reg.add_argument("--id", required=True)
    reg.add_argument("--json", required=True, help="扩展行 JSON 对象")
    ena = ext_sub.add_parser("enable", help="启用一个扩展")
    ena.add_argument("--kind", required=True, choices=list(EXTENSION_KINDS))
    ena.add_argument("--id", required=True)
    dis = ext_sub.add_parser("disable", help="停用一个扩展（行保留，随时可再启用）")
    dis.add_argument("--kind", required=True, choices=list(EXTENSION_KINDS))
    dis.add_argument("--id", required=True)
    rem = ext_sub.add_parser("remove", help="删除一个自定义行（只能手动删）")
    rem.add_argument("--kind", required=True, choices=list(EXTENSION_KINDS))
    rem.add_argument("--id", required=True)
    sub.add_parser("discover", help="扫描本机已装的 Agent CLI（自动发现）")

    sub.add_parser("mcp", help="启动 stdio MCP 服务")

    args = parser.parse_args(argv)
    hand = HandServer(args.state_dir, args.device_id, args.display_name, args.port, args.config_policy)

    if args.command in (None, "serve"):
        serve(args.state_dir, args.device_id, args.display_name, args.host, args.port, args.config_policy)
        return 0
    if args.command == "fingerprint":
        print(hand.fingerprint())
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
    if args.command == "pair-code":
        if args.rotate:
            print(hand.rotate_pair_code())
        else:
            print(hand.get_or_create_pair_code())
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
            if not isinstance(auth, dict):
                auth = {}
            entry = auth.get(args.agent_id)
            if isinstance(entry, dict):
                # 字典形：授权文件夹在 "folders" 键下（authorize 动作
                # 写入 authorized_project_folders 后仍走这里）。
                folders = entry.get("folders", [])
                if not isinstance(folders, list):
                    folders = []
                entry["folders"] = folders
            else:
                folders = list(entry) if isinstance(entry, list) else []
                auth[args.agent_id] = folders
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
    if args.command == "extensions":
        print(json.dumps(registry_list(args.state_dir, args.kind, args.device_id),
                         ensure_ascii=False, indent=2))
        return 0
    if args.command == "extension":
        if args.ext_action == "register":
            try:
                entry = json.loads(args.json)
            except Exception as e:
                print(f"注册内容不是合法 JSON：{e}", file=sys.stderr)
                return 1
            try:
                saved = registry_register(args.state_dir, args.kind, args.id, entry)
            except (ValueError, KeyError) as e:
                print(f"注册失败：{e}", file=sys.stderr)
                return 1
            print(json.dumps(saved, ensure_ascii=False, indent=2))
            return 0
        if args.ext_action in ("enable", "disable"):
            try:
                saved = registry_set_enabled(args.state_dir, args.kind, args.id,
                                             args.ext_action == "enable",
                                             args.device_id)
            except (ValueError, KeyError) as e:
                print(f"操作失败：{e}", file=sys.stderr)
                return 1
            print(json.dumps(saved, ensure_ascii=False, indent=2))
            return 0
        if args.ext_action == "remove":
            removed = registry_remove(args.state_dir, args.kind, args.id)
            print("已删除自定义行。" if removed else
                  "没有可删的自定义行（内置行删不掉）。")
            return 0 if removed else 1
        print("用法：extension {register|enable|disable|remove} …", file=sys.stderr)
        return 1
    if args.command == "discover":
        resolved = resolve_adapters(args.state_dir, args.device_id)
        out = []
        for ext_id, reg in resolved.items():
            out.append({
                "agent_id": ext_id,
                "display_name": reg["display_name"],
                "binary": reg["binary_path"],
                "discovered": reg["discovered"],
                "folder": reg["folder"],
                "login_check": reg["login_check"],
                "login": check_login(reg),
                "source": reg["source"],
                "verified": reg["verified"],
            })
        brains = [{"brain_id": bid,
                   "display_name": brow.get("display_name", bid),
                   "backend_type": brow.get("backend_type", ""),
                   "source_adapter": brow.get("source_adapter", ""),
                   "login": brow.get("login"),
                   "status": brow.get("status", ""),
                   "enabled": brow.get("enabled", True)}
                  for bid, brow in registry_all(
                      args.state_dir, args.device_id).get("brain", {}).items()
                  if brow.get("source") == "discovered"]
        print(json.dumps({"agents": out, "brains": brains},
                         ensure_ascii=False, indent=2))
        return 0
    if args.command == "mcp":
        return mcp_serve(hand)
    parser.print_help()
    return 1


# --------------------------------------------------------------------------
# 统一扩展注册表：七类扩展同一套注册 / 发现 / 启用机制
# --------------------------------------------------------------------------
# 种类：adapter（Agent 适配器）、connector（连接器）、plugin（插件）、
# mcp（MCP 服务）、wakeup（唤醒机制）、protocol（通信协议）、
# brain（推理后端——App 给自己找的推理后端，不是成员 Agent）。
# 核心代码不认识具体扩展，只认注册表。注册表 = 内置注册（下表，纯数据）
# + 各设备 state-dir 下 extensions.json（用户自定义行）。
# 自定义行与内置行同等对待：注册、发现、启用三步全通；停用只改
# enabled 标志，行保留；代码里没有任何自动清理自定义行的逻辑，
# 部署也不会碰 extensions.json。加新扩展 = 注册表里加一行，
# 不写新脚本、不改核心、不重启（注册表每次调用都重新读文件）。
# 功能种类一个不减：七类分开留着，不合并；MCP 是 protocol 的一种
# 实现，不是唯一协议；webhook 是 wakeup 的一种实现，不是唯一唤醒。
# 大脑扫描的发现来源（只写真实来源，不探沙箱目录）：
#   (a) App BrainConfig：API Key 的模型（OpenAI 兼容、Cloudflare、
#       DeepSeek、自定义添加）与会员（ChatGPT 会员等）；
#   (b) Hand 发现：设备上已装已登录的 CLI，其会员额度可作推理后端
#       （见 derived_brains()，由 adapter 发现结果派生）。
# 沙箱成员 Agent 扫描（探沙箱目录找第三方 CLI 当成员）是已删的错误
# 方向，与大脑扫描无关，本文件任何地方都不探沙箱目录。

EXTENSION_KINDS = ("adapter", "connector", "plugin", "mcp", "wakeup",
                   "protocol", "brain")
REGISTRY_VERSION = 1
OVERLAY_FILENAME = "extensions.json"

DEVICE_ALIASES = {"MacBook-Air": "mac"}


def canonical_device_id(device_id: str) -> str:
    return DEVICE_ALIASES.get(device_id, device_id)


# 内置注册：只写“已知”的扩展（检测方法、调用模板、实现指向）。
# 其中 adapter 行是“已知 Agent 表”：加新 Agent = 表里加一行。
# claude 不在内置表里——它是本次“只加表一行”的证明对象，见 Mac
# 的 extensions.json overlay（设备上已装、OAuth 已过期，见 DELIVERY）。
# App 自己也是 adapter 表里一等的行（wherevercode-agent，
# execution=internal）：id 不是凭空发明的——取自 App 的 applicationId
# "wherevercode.agent"（android/app/build.gradle.kts:35，namespace
# 同值），AgentRecord 的 id 形状（^[a-z0-9][a-z0-9_-]*$，见
# AgentRecord.kt）不许点，点改横线，与仓库名 wherevercode-agent 同形；
# 显示名“本机 · WhereverCode”是 App 内默认对话方（代码里今天用
# ChatViewModel selectedAgent==null 表示“不选 Agent、直接走大脑”，
# 尚无自我 AgentRecord；本行就是把这个默认对话方登记在册）。
# 执行方式 internal：BrainEngine（聊天/大脑）+ TaskScheduler（任务引擎）
# + ChatTaskDispatcher（聊天→任务行）+ 泵（远端走 RemoteTaskPump），
# 全在 App 内，不 spawn 外部二进制，不经过设备 Hand。
BUILTIN_EXTENSIONS: dict[str, dict[str, dict]] = {
    "adapter": {
        "wherevercode-agent": {
            "display_name": "本机 · WhereverCode",
            "execution": "internal",
            "executor": "BrainEngine + TaskScheduler + ChatTaskDispatcher + 泵（App 内）",
            "app_identity": "applicationId wherevercode.agent（build.gradle.kts:35）",
            "addressable_as": "默认对话方（ChatViewModel selectedAgent==null 即指它）；"
                              "任务行 assignedAgentId/memberId 可填本 id",
            "login_check": {"type": "none", "how": "App 自身，无需登录"},
            "folders_by_device": {},
            "capabilities": ["chat", "dev"],
            "consumes": "brain（推理后端）：App 作为 Agent 消费大脑——扫描、"
                        "注册、切换大脑；大脑是独立一类扩展，不是 App 自己。",
            "env": {}, "verified": False,
            "note": "App-as-Agent：能聊天、能执行（经任务引擎+工具）、能协作，"
                    "与外部 CLI Agent 地位相同，区别只在执行方式。"
                    "Hand 不执行 internal 行（见 run_agent_entry）；"
                    "App 侧 AgentRecord/TeamMember 落册是后续 App 工作，见 DELIVERY。",
        },
        "codex": {
            "display_name": "Codex", "binary": "codex",
            "binary_candidates": ["/usr/bin/codex", "~/.local/bin/codex"],
            "argv": ["codex", "exec", "--skip-git-repo-check", "-s",
                     "workspace-write", "{prompt}"],
            "login_check": {"type": "file", "path": "~/.codex/auth.json"},
            "folders_by_device": {
                "remote-device": ["/workspace/codex"],
                "mac": ["/Users/example-user/v5-codex-work"],
            },
            "capabilities": ["dev"], "env": {}, "verified": True,
        },
        "codebuddy": {
            "display_name": "CodeBuddy", "binary": "codebuddy",
            "binary_candidates": ["/usr/bin/codebuddy"],
            "argv": ["codebuddy", "-p", "-y", "{prompt}"],
            "login_check": {"type": "smoke",
                            "how": "无本地凭证文件可查；以一次真实小提示冒烟为准"},
            "folders_by_device": {"remote-device": ["/workspace/codebuddy"]},
            "capabilities": ["dev"], "env": {}, "verified": True,
        },
        # 注册表 id = App 侧 agentId（与已配对的 agents.json / App 记录
        # 一致），binary 是另外的字段。grok 行的 id 是 example-agent
        #（VPS 上一直用的 id，App 已按此配对），不是二进制名 grok。
        "example-agent": {
            "display_name": "Example Agent", "binary": "grok",
            "binary_candidates": ["/usr/local/bin/grok", "~/.local/bin/grok"],
            "argv": ["grok", "-p", "{prompt}", "--always-approve",
                     "--sandbox", "workspace", "--output-format", "plain"],
            "login_check": {"type": "file", "path": "~/.grok/auth.json"},
            "folders_by_device": {
                "remote-device": ["/workspace/example-project"],
                "mac": ["/Users/example-user/v5-grok-work"],
            },
            "log_template": {
                "remote-device": "/workspace/example-project/log-grok-{task_id}.txt",
                "mac": "/Users/example-user/v5-grok-work/log-grok-{task_id}.txt",
            },
            "capabilities": ["dev"], "env": {}, "verified": True,
        },
        "gemini": {
            "display_name": "Gemini", "binary": "gemini",
            "binary_candidates": ["gemini"],
            "argv": ["gemini", "-p", "{prompt}"],
            "login_check": {"type": "file",
                            "path": "~/.gemini/oauth_creds.json"},
            "folders_by_device": {},
            "capabilities": ["dev"], "env": {}, "verified": False,
            "note": "已知行，未在 VPS/Mac 上装过；argv 与凭证路径待真机核实。",
        },
        "agy": {
            "display_name": "agy", "binary": "agy",
            "binary_candidates": ["~/.local/bin/agy", "agy"],
            "argv": ["agy", "--dangerously-skip-permissions", "{prompt}"],
            "login_check": {"type": "smoke",
                            "how": "无头模式冒烟为准；凭证位置待小米 12 上核实"},
            "folders_by_device": {},
            "capabilities": ["dev"], "env": {}, "verified": False,
            "note": "已知行，只在小米 12 Termux 见过（1.5.4）；本次不在范围，"
                    "argv 待小米 12 Hand 接入时核实。",
        },
    },
    "connector": {
        "hand-tcp-transport": {
            "display_name": "Hand 直连传输",
            "implementation": "hand_server.py 本进程：TCP 18743 + 密封信封"
                              "（X25519+HKDF 会话，AES-256-GCM，v2 任务凭证）",
            "status": "live",
            "note": "Hand 正在用的传输本身；VPS/Mac 两边都在跑。",
        },
        "tailscale": {
            "display_name": "Tailscale 组网",
            "implementation": "Tailnet：VPS 192.0.2.10，Mac 198.51.100.10；"
                              "Hand 经该网络可达，2026-11-10/11 实测连通。",
            "status": "live",
        },
    },
    "plugin": {
        "healthcheck": {
            "display_name": "健康检查",
            "implementation": "run_healthcheck()（本文件）；"
                              "CLI：hand_server.py plugin run healthcheck",
            "status": "live",
        },
    },
    "mcp": {
        "hand-mcp-stdio": {
            "display_name": "Hand MCP 服务（stdio）",
            "implementation": "mcp_serve()（本文件）：stdio JSON-RPC 2.0，"
                              "协议 2024-11-05，工具 list/read/write/run；"
                              "run 走通用 runner，文件夹须在授权表里。",
            "status": "live",
        },
    },
    "wakeup": {
        # T4: preserve sender; dispatch-to-webhook-to-MCP claim wiring is incomplete.
        # The missing end-to-end wiring is excluded from executable-union acceptance.
        "webhook": {
            "display_name": "Webhook 唤醒",
            "implementation": "webhook_deliver()（本文件）：出站 POST，"
                              "Standard Webhooks HMAC 签名，https-only，"
                              "重试 (0, 5, 30, 120) 秒；设计见老项目 C3 审计 §3.2 "
                              "（两段式：webhook 只叫醒，执行走 MCP 领单）。",
            "status": "mechanism-live",
            "gap": "端到端接线（派单→webhook→Agent 经 MCP 认领）依赖 maintainer 对"
                   "两段式口径的确认（2026-11-10 13:10 已报、未回）；"
                   "手机无公网地址，直接收公网 webhook 的可达方案也未定。",
        },
        "polling": {
            "display_name": "轮询唤醒",
            "implementation": "已有机制：App HandClient hello 轮询 + "
                              "WhereverCode 队列 10 分钟轮询；"
                              "webhook 失败时的兜底也是轮询（老项目同款规则）。",
            "status": "live",
        },
    },
    "protocol": {
        "mcp": {
            "display_name": "MCP（通信协议）",
            "implementation": "JSON-RPC 2.0，protocolVersion 2024-11-05；"
                              "由 hand-mcp-stdio 提供；工具 list/read/write/run。",
            "status": "live",
            "note": "MCP 只是协议之一，与 direct-transport、WACF 并列，"
                    "不是唯一协议；A2A 不引入（maintainer 已定）。",
        },
        "direct-transport": {
            "display_name": "Hand 直连协议",
            "implementation": "hand_server.py 本进程的密封信封协议："
                              "wherevercode_hello 握手 + cross_device_tool_call "
                              "信封（X25519+HKDF，AES-256-GCM，v2 凭证，"
                              "epoch/goal_version 栅栏）。",
            "status": "live",
        },
        # T4: design registration only; no Hand implementation or fake executor.
        # Future bus: control/message ledger; unwakeable Agents remain pending-claim.
        "wacf": {
            "display_name": "WACF 自有总线",
            "implementation": "WACF 设计（2026-11-08，U1–U12 已定：控制 + "
                              "消息账本，凭证 v2 + AES-GCM v2）；"
                              "Hand 内暂无实现代码。",
            "status": "design-only",
            "gap": "只登记设计，未实现；实现时按 WACF 设计接进注册表，不改核心。",
        },
    },
    # brain（推理后端）：App 给自己找的推理后端。两类来源——
    # (a) 下表是 App 侧 BrainConfig 里已配置的模型后端（K70 C3 实测：
    #     ChatGPT 会员已接、Cloudflare/DeepSeek 已配、自定义添加走
    #     OpenRouter；Key 在 App BrainKeyStore，Hand 不存 Key、不验证，
    #     只登记类型与配置位置，status=app-configured）；
    # (b) CLI 会员额度后端由 Hand 发现派生（见 derived_brains()），
    #     不在下表里写死——设备上新装已登录的 CLI，brain 行自动出现。
    # 消费方：wherevercode-agent（App 自己）——使用者与后端的关系。
    "brain": {
        "openai-compatible": {
            "display_name": "OpenAI 兼容模型",
            "backend_type": "model-api",
            "implementation": "App BrainConfig：OpenAI 兼容 /v1/models；"
                              "Key 在 BrainKeyStore；自定义添加走同一入口。",
            "status": "app-configured",
            "consumed_by": "wherevercode-agent",
            "verified": False,
            "note": "在 App 侧配置与验证；Hand 只登记，不存 Key。",
        },
        "cloudflare": {
            "display_name": "Cloudflare Workers AI",
            "backend_type": "model-api",
            "implementation": "App BrainConfig：Workers AI（免费 10,000 "
                              "Neurons/天）；默认模型待 maintainer 在控制台核对"
                              "免费资格后切换；绝不自动切付费。",
            "status": "app-configured",
            "consumed_by": "wherevercode-agent",
            "verified": False,
        },
        "deepseek": {
            "display_name": "DeepSeek",
            "backend_type": "model-api",
            "implementation": "App BrainConfig：DeepSeek API；Key 在 BrainKeyStore。",
            "status": "app-configured",
            "consumed_by": "wherevercode-agent",
            "verified": False,
        },
        "chatgpt-membership": {
            "display_name": "ChatGPT 会员",
            "backend_type": "membership",
            "implementation": "App BrainConfig：ChatGPT 会员（SIWC；"
                              "K70 上已接通，模型 gpt-6.1-sol）；"
                              "会员额度，非 API Key。",
            "status": "app-configured",
            "consumed_by": "wherevercode-agent",
            "verified": False,
        },
        "custom-model-api": {
            "display_name": "自定义模型 API",
            "backend_type": "model-api",
            "implementation": "App BrainConfig 自定义添加（如 OpenRouter；"
                              "Key 由 maintainer 在 App 内自己填，绝不经手 Muse）。",
            "status": "app-configured",
            "consumed_by": "wherevercode-agent",
            "verified": False,
        },
    },
}


def _overlay_path(state_dir: str) -> str:
    return os.path.join(os.path.expanduser(state_dir), OVERLAY_FILENAME)


def load_overlay_extensions(state_dir: str) -> dict:
    """读用户自定义行（extensions.json）。文件不存在或损坏返回空表，
    损坏时不删文件、不覆盖——宁可读不到，不可丢自定义。"""
    try:
        with open(_overlay_path(state_dir), "r", encoding="utf-8") as f:
            data = json.load(f)
        exts = data.get("extensions", {}) if isinstance(data, dict) else {}
        return exts if isinstance(exts, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def save_overlay_extensions(state_dir: str, extensions: dict) -> None:
    path = _overlay_path(state_dir)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"version": REGISTRY_VERSION, "extensions": extensions},
                  f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _derived_brains(state_dir: str, device_id: str, merged: dict) -> dict[str, dict]:
    """Hand 发现派生的推理后端：设备上已装的 CLI adapter，其会员额度
    可作 App 的推理后端。每发现一个已装 CLI，brain 行自动出现一条；
    设备上新装已登录的 CLI，下次发现自动多一条，不用改表。
    login 证据优先用行里的 smoke_status（冒烟实测），其次用
    login_check（文件/命令 cheap 检查）；smoke 型且无实测记录时为
    None（未知，不猜）。internal 行（App 自身）不派生——它消费大脑，
    不是大脑。"""
    brains: dict[str, dict] = {}
    for ext_id, adapter in resolve_adapters(state_dir, device_id).items():
        if adapter.get("execution", "cli") != "cli":
            continue
        if not adapter.get("discovered"):
            continue
        raw = merged.get("adapter", {}).get(ext_id, {})
        smoke = raw.get("smoke_status", "") or ""
        if isinstance(smoke, str) and smoke.startswith("passed"):
            login: bool | None = True
        elif isinstance(smoke, str) and smoke.startswith("failed"):
            login = False
        else:
            login = check_login(adapter)
        brains[f"{ext_id}-membership"] = {
            "kind": "brain",
            "id": f"{ext_id}-membership",
            "display_name": f"{adapter['display_name']} 会员额度",
            "backend_type": "cli-membership",
            "source_adapter": ext_id,
            "login": login,
            "login_check": adapter.get("login_check", {}) or {},
            "smoke_status": smoke,
            "implementation": f"经 {adapter['display_name']} CLI 的会员额度推理；"
                              "Hand 在本设备上发现已装 CLI。",
            "status": "live" if login else "login-unconfirmed",
            "consumed_by": "wherevercode-agent",
            "source": "discovered",
            "enabled": True,
        }
    return brains


def registry_all(state_dir: str, device_id: str | None = None) -> dict[str, dict[str, dict]]:
    """合并后的注册表：内置 + 自定义行（自定义同 id 覆盖内置行其余字段
    保留合并；自定义独有 id 直接加入）+ device_id 给出时派生的 brain 行。
    每次调用都重新读文件，所以加一行不用重启。注意：resolve_adapters
    内部调本函数时不传 device_id（只取 adapter 行），避免派生递归。"""
    merged: dict[str, dict[str, dict]] = {}
    for kind in EXTENSION_KINDS:
        merged[kind] = {}
        for ext_id, entry in BUILTIN_EXTENSIONS.get(kind, {}).items():
            item = dict(entry)
            item["kind"] = kind
            item["id"] = ext_id
            item["source"] = "builtin"
            item.setdefault("enabled", True)
            merged[kind][ext_id] = item
    overlay = load_overlay_extensions(state_dir)
    for kind, items in overlay.items():
        if kind not in EXTENSION_KINDS or not isinstance(items, dict):
            continue
        for ext_id, entry in items.items():
            if not isinstance(entry, dict):
                continue
            base = merged[kind].get(ext_id, {})
            item = dict(base)
            item.update(entry)
            item["kind"] = kind
            item["id"] = ext_id
            item["source"] = "custom"
            item.setdefault("enabled", True)
            merged[kind][ext_id] = item
    if device_id is not None:
        for brain_id, row in _derived_brains(state_dir, device_id, merged).items():
            base = merged["brain"].get(brain_id, {})
            item = dict(row)
            item.update(base)  # 用户 overlay / 内置行优先于派生默认值
            item.setdefault("enabled", True)
            merged["brain"][brain_id] = item
    return merged


def registry_list(state_dir: str, kind: str | None = None,
                  device_id: str | None = None) -> dict:
    all_exts = registry_all(state_dir, device_id)
    if kind is None:
        return all_exts
    return {kind: all_exts.get(kind, {})} if kind in EXTENSION_KINDS else {}


def registry_register(state_dir: str, kind: str, ext_id: str, entry: dict) -> dict:
    """注册一个扩展（写自定义行）。只写 overlay 文件，绝不改内置表；
    同名自定义行整体替换（用户自己动手才删，见 registry_remove）。"""
    if kind not in EXTENSION_KINDS:
        raise ValueError(f"未知的扩展类型：{kind}（应为 {EXTENSION_KINDS} 之一）")
    if not ext_id or not all(c.isalnum() or c in "-_" for c in ext_id):
        raise ValueError(f"扩展 id 非法：{ext_id!r}（只许字母数字、-、_）")
    if not isinstance(entry, dict):
        raise ValueError("注册内容须为 JSON 对象")
    overlay = load_overlay_extensions(state_dir)
    overlay.setdefault(kind, {})[ext_id] = dict(entry)
    overlay[kind][ext_id]["added_at"] = int(time.time())
    save_overlay_extensions(state_dir, overlay)
    return registry_all(state_dir)[kind][ext_id]


def registry_set_enabled(state_dir: str, kind: str, ext_id: str, enabled: bool,
                         device_id: str | None = None) -> dict:
    """启用 / 停用。停用只写 enabled=false 覆盖行，行保留、随时可再启用；
    对内置行同样只写 overlay 覆盖，不动内置表。device_id 给出时，
    派生的 brain 行也能被停用/启用。"""
    if kind not in EXTENSION_KINDS:
        raise ValueError(f"未知的扩展类型：{kind}")
    current = registry_all(state_dir, device_id)
    if ext_id not in current.get(kind, {}):
        raise KeyError(f"未注册的扩展：{kind}/{ext_id}")
    overlay = load_overlay_extensions(state_dir)
    row = dict(overlay.get(kind, {}).get(ext_id, {}))
    row["enabled"] = bool(enabled)
    overlay.setdefault(kind, {})[ext_id] = row
    save_overlay_extensions(state_dir, overlay)
    return registry_all(state_dir, device_id)[kind][ext_id]


def registry_remove(state_dir: str, kind: str, ext_id: str) -> bool:
    """删自定义行：只能用户自己动手调这一个函数删；代码里任何地方
    都不自动调它，不自动清理自定义行。内置行删不掉（只会清掉 overlay
    覆盖，内置行恢复）。"""
    overlay = load_overlay_extensions(state_dir)
    items = overlay.get(kind, {})
    if ext_id in items:
        del items[ext_id]
        if not items:
            del overlay[kind]
        save_overlay_extensions(state_dir, overlay)
        return True
    return False


def registry_summary(state_dir: str, device_id: str | None = None) -> dict:
    """hello 里带的注册表摘要（只读、可选字段）：App 由此列出七类扩展与自定义项。"""
    all_exts = registry_all(state_dir, device_id)
    custom = [{"kind": k, "id": i} for k in EXTENSION_KINDS
              for i, e in all_exts[k].items() if e.get("source") == "custom"]
    disabled = [{"kind": k, "id": i} for k in EXTENSION_KINDS
                for i, e in all_exts[k].items() if not e.get("enabled", True)]
    return {
        "kinds": list(EXTENSION_KINDS),
        "counts": {k: len(all_exts[k]) for k in EXTENSION_KINDS},
        "custom": custom,
        "disabled": disabled,
    }


def resolve_binary(entry: dict) -> str | None:
    """按 binary_candidates 找二进制：绝对路径先验存在，~ 展开，
    否则按 binary 名查 PATH。找不到返回 None。"""
    seen: list[str] = []
    for cand in entry.get("binary_candidates", []) or []:
        seen.append(os.path.expanduser(str(cand)))
    if entry.get("binary"):
        seen.append(str(entry["binary"]))
    for cand in seen:
        if os.path.isabs(cand):
            if os.path.isfile(cand) and os.access(cand, os.X_OK):
                return cand
        else:
            found = shutil.which(cand)
            if found:
                return found
    return None


def adapter_folder(entry: dict, device_id: str) -> str:
    dev = canonical_device_id(device_id)
    by_device = entry.get("folders_by_device", {}) or {}
    folders = by_device.get(dev) or by_device.get(device_id) or []
    if folders:
        return folders[0]
    plain = entry.get("folders", []) or []
    return plain[0] if plain else ""


def adapter_log_path(entry: dict, device_id: str, task_id: str) -> str | None:
    dev = canonical_device_id(device_id)
    tmpl = entry.get("log_template", {}) or {}
    raw = tmpl.get(dev) or tmpl.get(device_id)
    safe_id = hashlib.sha256(task_id.encode("utf-8")).hexdigest()
    return raw.replace("{task_id}", safe_id) if raw else None


def resolve_adapters(state_dir: str, device_id: str) -> dict[str, dict]:
    """已启用的 adapter 行 + 本机解析结果（二进制绝对路径、文件夹）。
    新装一个 Agent 只要注册表里有它的行，hello 里自动出现。"""
    out: dict[str, dict] = {}
    for ext_id, entry in registry_all(state_dir).get("adapter", {}).items():
        if not entry.get("enabled", True):
            continue
        execution = entry.get("execution", "cli")
        binary_path = resolve_binary(entry) if execution == "cli" else None
        argv = list(entry.get("argv", []) or [])
        if argv and binary_path:
            argv = [binary_path] + argv[1:]
        out[ext_id] = {
            "id": ext_id,
            "display_name": entry.get("display_name", ext_id),
            "kind": "agent",
            "execution": execution,
            "executor": entry.get("executor", ""),
            "capabilities": entry.get("capabilities", []),
            "folder": adapter_folder(entry, device_id),
            "command": argv,
            "env": entry.get("env", {}) or {},
            "binary_path": binary_path,
            "discovered": binary_path is not None if execution == "cli" else False,
            "source": entry.get("source", "builtin"),
            "login_check": entry.get("login_check", {}) or {},
            "log_template": entry.get("log_template", {}) or {},
            "verified": entry.get("verified", False),
        }
    return out


def check_login(entry: dict, timeout: float = 10.0) -> bool | None:
    """按注册行声明的方式确认登录。返回 True/False；smoke 型返回 None
    （只有一次真实运行才能证明，不在 hello 里跑模型调用）。"""
    lc = entry.get("login_check", {}) or {}
    kind = lc.get("type")
    if kind == "file":
        path = os.path.expanduser(lc.get("path", ""))
        try:
            return os.path.isfile(path) and os.path.getsize(path) > 0
        except OSError:
            return False
    if kind == "command":
        argv = lc.get("argv", []) or []
        if not argv:
            return None
        try:
            result = subprocess.run(
                argv, stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=timeout)
            return result.returncode == 0
        except Exception:
            return False
    return None




def _write_log_copy(log_path: str, cwd: str, output: str, task_id: str) -> None:
    """Configured, task-bound copy inside cwd; reuse Hand path and artifact fd guards.

    No directory creation, symlink traversal, or overwrite. Unsupported fd guards
    fail closed; the caller reports a non-fatal internal warning.
    """
    if not isinstance(log_path, str) or not os.path.isabs(log_path):
        raise ValueError("log path must be absolute")
    if ".." in log_path.replace("\\", "/").split("/"):
        raise ValueError("log path traversal")
    task_ref = hashlib.sha256(task_id.encode("utf-8")).hexdigest()
    if task_ref not in os.path.basename(log_path):
        raise ValueError("log filename must bind the task")
    base = os.path.abspath(cwd or os.getcwd())
    rel = os.path.relpath(log_path, base)
    if HandServer._resolve_path(None, base, rel) is None:
        raise ValueError("log outside execution folder")
    # Canonicalize only the already-authorized base. Open every component with
    # the same no-follow directory-fd primitive used by delivery.bin handling.
    canonical_base = os.path.realpath(base)
    if canonical_base == os.path.abspath(os.sep):
        raise ValueError("filesystem root is not a log scope")
    parts = [p for p in (canonical_base + os.sep + rel).split(os.sep) if p not in ("", ".")]
    directory = os.open(os.sep, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(parts[-1], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=directory)
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as stream:
            stream.write(output)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(directory)


def _log_copy_warning(task_id: str, error: Exception, on_warning=None) -> None:
    # Internal only: do not invent T6 wire fields or log output/path/credentials.
    event = {"event": "hand_log_copy_failed",
             "task_ref": hashlib.sha256(task_id.encode("utf-8")).hexdigest(),
             "error_type": type(error).__name__}
    logging.warning("hand_log_copy_failed task_ref=%s error_type=%s",
                    event["task_ref"], event["error_type"])
    if on_warning is not None:
        try:
            on_warning(event)
        except Exception:
            logging.warning("hand_log_copy_warning_sink_failed task_ref=%s", event["task_ref"])


def run_cli_agent(binary: str, argv_template: list[str], prompt: str,
                  task_id: str, cwd: str, env_extra: dict | None = None,
                  timeout_s: float = 600.0,
                  on_line=None, log_path: str | None = None,
                  register_proc=None, connection=None, on_log_warning=None,
                  ) -> tuple[int | None, str, bool]:
    """通用 runner：输入（二进制、argv 模板含 {prompt}/{task_id}、
    工作目录、env），跑任意 CLI Agent，stdout 直接抓回。
    不经 shell，不把输出重定向到日志文件再倒读（run-grok.sh 的
    `> logfile 2>&1` 是反模式，是 Grok 空响应的根因之一）；
    log_path 给出时，把抓到的完整输出另外写一份日志副本（可选）。
    返回 (returncode, 完整输出, 是否超时)。超时时进程组已杀掉。
    on_line 抛错（客户端已断开）时杀掉进程组再原样抛出。"""
    import select as _select

    argv = [part.replace("{prompt}", prompt).replace("{task_id}", task_id)
            for part in argv_template]
    if argv:
        argv[0] = binary
    env = dict(os.environ)
    env.update(env_extra or {})
    proc, lease_fd = spawn_agent(
        argv,
        cwd=cwd or None,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        bufsize=0,  # 二进制无缓冲：下文用 os.read + select 直接读 fd
        env=env,
    )
    try:
        if register_proc is not None:
            register_proc(proc)
        output_lines: list[str] = []
        timed_out = False
        try:
            deadline = time.time() + timeout_s
            assert proc.stdout is not None
            out_fd = proc.stdout.fileno()
            buf = ""
            while True:
                if time.time() > deadline:
                    timed_out = True
                    break
                sources = [out_fd] if connection is None else [out_fd, connection]
                exited = proc.poll() is not None
                ready, _, _ = _select.select(sources, [], [], 0 if exited else 0.2)
                if exited and out_fd not in ready:
                    break
                if connection in ready:
                    raise ConnectionError("Hand run connection closed")
                if out_fd in ready:
                    chunk = os.read(out_fd, 65536).decode("utf-8", errors="replace")
                    if not chunk:
                        break
                    buf += chunk
                    while "\n" in buf:
                        line, buf = buf.split("\n", 1)
                        line += "\n"
                        output_lines.append(line)
                        if on_line is not None:
                            on_line(line)
            if buf:
                output_lines.append(buf)
                if on_line is not None:
                    on_line(buf)
        except BaseException:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.wait(timeout=5)
            proc.stdout.close()
            raise
        if not timed_out:
            try:
                proc.wait(timeout=max(0.0, deadline - time.time()))
            except subprocess.TimeoutExpired:
                timed_out = True
        if timed_out:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.wait(timeout=5)
        output = "".join(output_lines)
        try:
            if proc.stdout is not None:
                proc.stdout.close()
        except OSError:
            pass
        if log_path is not None:
            try:
                _write_log_copy(log_path, cwd, output, task_id)
            except Exception as error:
                _log_copy_warning(task_id, error, on_log_warning)
        return proc.returncode, output, timed_out

    finally:
        os.close(lease_fd)
        if proc.stdout is not None:
            proc.stdout.close()


class InternalAgentExecution(Exception):
    """internal 执行行的调用：须在 App 内走任务引擎，本 Hand 不执行。"""


def run_agent_entry(resolved: dict, prompt: str, task_id: str, cwd: str,
                    env_extra: dict | None = None,
                    timeout_s: float = 600.0,
                    on_line=None, log_path: str | None = None,
                    register_proc=None, connection=None, on_log_warning=None,
                    ) -> tuple[int | None, str, bool]:
    """统一分派：按注册行的 execution 分派执行方式，两条都是一等路径。
    - cli：子进程跑 CLI（run_cli_agent）。
    - internal：App 自身（BrainEngine + TaskScheduler +
      ChatTaskDispatcher + 泵）。Hand（本进程）没有 App 的任务引擎，
      调它抛 InternalAgentExecution——绝不假装执行成功；App 侧
      （Kotlin）实现 internal 执行器后，同名分派走 App 内引擎。"""
    if resolved.get("execution", "cli") == "internal":
        raise InternalAgentExecution(
            "该 Agent 在 App 内执行（BrainEngine + TaskScheduler + "
            "ChatTaskDispatcher），不经过设备 Hand；请在 App 内直接指派。")
    command = resolved.get("command", []) or []
    if not command:
        raise ValueError(f"Agent {resolved.get('id', '?')} 没有可执行命令")
    return run_cli_agent(command[0], command, prompt, task_id, cwd,
                         env_extra=env_extra, timeout_s=timeout_s,
                         on_line=on_line, log_path=log_path,
                         register_proc=register_proc, connection=connection,
                         on_log_warning=on_log_warning)




MCP_PROTOCOL_VERSION = "2024-11-05"
MCP_SERVER_NAME = "wherevercode-hand-mcp"
MCP_SERVER_VERSION = "1"


def mcp_tool_defs() -> list[dict]:
    return [
        {"name": "list",
         "description": "列出项目所在设备上文件夹里的文件和子文件夹。"
                        "这些操作发生在项目所在设备上，不在本机执行。",
         "inputSchema": {"type": "object",
                         "properties": {"folder": {"type": "string"},
                                        "path": {"type": "string"}},
                         "required": ["folder"],
                         "additionalProperties": False}},
        {"name": "read",
         "description": "读项目所在设备上一个文本文件的内容。",
         "inputSchema": {"type": "object",
                         "properties": {"folder": {"type": "string"},
                                        "path": {"type": "string"}},
                         "required": ["folder", "path"],
                         "additionalProperties": False}},
        {"name": "write",
         "description": "在项目所在设备上新建或覆盖一个文本文件。",
         "inputSchema": {"type": "object",
                         "properties": {"folder": {"type": "string"},
                                        "path": {"type": "string"},
                                        "content": {"type": "string"}},
                         "required": ["folder", "path", "content"],
                         "additionalProperties": False}},
        {"name": "run",
         "description": "在项目所在设备上经已注册的 Agent 执行一条提示。"
                        "Agent 由注册表解析，不接受任意 shell 命令。",
         "inputSchema": {"type": "object",
                         "properties": {"agent_id": {"type": "string"},
                                        "folder": {"type": "string"},
                                        "prompt": {"type": "string"},
                                        "timeout": {"type": "number"}},
                         "required": ["agent_id", "folder", "prompt"],
                         "additionalProperties": False}},
    ]


def _mcp_text(text: str, is_error: bool = False) -> dict:
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


def _mcp_error_text(message: str, code: str) -> dict:
    return _mcp_text(f"{message}（{code}）", is_error=True)


def _servable_folders(hand: "HandServer") -> list[str]:
    """Hand 能服务的文件夹全集：auth.json 全部 + 注册表声明的全部。"""
    folders: list[str] = []
    auth = hand.state.load_json("auth.json", {})
    if isinstance(auth, dict):
        for items in auth.values():
            if isinstance(items, list):
                folders.extend(items)
            elif isinstance(items, dict):
                vals = items.get("folders", [])
                if isinstance(vals, list):
                    folders.extend(vals)
    for agent_id in resolve_adapters(hand.state.dir, hand.device_id):
        folders.extend(hand.authorized_folders(agent_id))
    seen: set[str] = set()
    out: list[str] = []
    for folder in folders:
        canon = canonical_folder(folder)
        if canon not in seen:
            seen.add(canon)
            out.append(canon)
    return out


def _mcp_call_tool(hand: "HandServer", name: str, args: dict) -> dict:
    if name == "list":
        folder = str(args.get("folder", ""))
        if canonical_folder(folder) not in _servable_folders(hand):
            return _mcp_error_text("该文件夹不在授权列表里", "wrong_folder")
        target = hand._resolve_path(folder, str(args.get("path", "")))
        if target is None or not os.path.isdir(target):
            return _mcp_error_text("不允许的路径：该路径不在授权文件夹内。",
                                   "path_not_allowed")
        try:
            entries = sorted(os.listdir(target))
        except OSError as e:
            return _mcp_error_text(f"无法列出文件夹：{e.strerror or e}",
                                   "path_not_allowed")
        out = [{"name": n, "is_dir": os.path.isdir(os.path.join(target, n))}
               for n in entries]
        return _mcp_text(json.dumps(out, ensure_ascii=False))
    if name == "read":
        folder = str(args.get("folder", ""))
        if canonical_folder(folder) not in _servable_folders(hand):
            return _mcp_error_text("该文件夹不在授权列表里", "wrong_folder")
        target = hand._resolve_path(folder, str(args.get("path", "")))
        if target is None or not os.path.isfile(target):
            return _mcp_error_text("不允许的路径：该路径不在授权文件夹内。",
                                   "path_not_allowed")
        try:
            if os.path.getsize(target) > MAX_TEXT_BYTES:
                return _mcp_error_text("文件太大，请分段读取。", "path_not_allowed")
            with open(target, "r", encoding="utf-8", errors="strict") as f:
                return _mcp_text(f.read())
        except (OSError, UnicodeDecodeError) as e:
            return _mcp_error_text(f"无法读取文件：{e}", "path_not_allowed")
    if name == "write":
        folder = str(args.get("folder", ""))
        if canonical_folder(folder) not in _servable_folders(hand):
            return _mcp_error_text("该文件夹不在授权列表里", "wrong_folder")
        rel, content = str(args.get("path", "")), str(args.get("content", ""))
        if not rel:
            return _mcp_error_text("写入需要指定 path。", "path_not_allowed")
        if len(content.encode("utf-8")) > MAX_TEXT_BYTES:
            return _mcp_error_text("内容太大。", "path_not_allowed")
        target = hand._resolve_path(folder, rel)
        if target is None:
            return _mcp_error_text("不允许的路径：该路径不在授权文件夹内。",
                                   "path_not_allowed")
        try:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8") as f:
                f.write(content)
        except OSError as e:
            return _mcp_error_text(f"无法写入文件：{e}", "path_not_allowed")
        return _mcp_text(f"已写入 {rel}")
    if name == "run":
        agent_id = str(args.get("agent_id", ""))
        folder = str(args.get("folder", ""))
        prompt = str(args.get("prompt", ""))
        if not agent_id or not prompt:
            return _mcp_error_text("run 需要 agent_id 与 prompt。", "invalid")
        cfg = hand.agent_config(agent_id)
        if cfg is None:
            return _mcp_error_text(f"该设备上没有这个 Agent（{agent_id}）。",
                                   "agent_not_found")
        reg = resolve_adapters(hand.state.dir, hand.device_id).get(agent_id, {})
        if reg.get("execution", "cli") == "internal":
            # App 自身：先报“App 内执行”，不要让文件夹门禁抢先报 wrong_folder
            #（internal 行本来就没有设备文件夹）。
            return _mcp_error_text(
                "该 Agent 在 App 内执行（BrainEngine + TaskScheduler + "
                "ChatTaskDispatcher），不经过设备 Hand；请在 App 内直接指派。",
                "agent_not_found")
        if canonical_folder(folder) not in [
                canonical_folder(f) for f in hand.authorized_folders(agent_id)]:
            return _mcp_error_text("连接凭证不允许访问该项目文件夹。",
                                   "wrong_folder")
        command = cfg.get("command", [])
        if not command or not os.path.exists(command[0]):
            return _mcp_error_text(f"Agent 入口不存在（{agent_id}）。",
                                   "agent_not_found")
        task_id = f"mcp-{uuid.uuid4().hex[:12]}"
        timeout_s = args.get("timeout", "") or DEFAULT_TIMEOUT_SECONDS
        try:
            timeout_s = float(timeout_s)
        except (TypeError, ValueError):
            timeout_s = DEFAULT_TIMEOUT_SECONDS
        timeout_s = max(1.0, min(timeout_s, 3600.0))
        log_path = adapter_log_path(reg, hand.device_id, task_id) if reg else None
        try:
            entry = dict(reg)
            entry["command"] = command
            returncode, output, timed_out = run_agent_entry(
                entry, prompt, task_id, folder,
                env_extra=cfg.get("env", {}) or {}, timeout_s=timeout_s,
                log_path=log_path, on_log_warning=hand.state.audit)
        except InternalAgentExecution as e:
            return _mcp_error_text(str(e), "agent_not_found")
        except OSError as e:
            return _mcp_error_text(f"无法启动 Agent：{e}", "agent_not_found")
        if timed_out:
            return _mcp_error_text(f"Agent 执行超时（{int(timeout_s)} 秒）：已终止。",
                                   "agent_timeout")
        if returncode != 0:
            return _mcp_error_text(
                f"Agent 执行失败（退出码 {returncode}）：" + output[-2000:],
                "agent_not_found")
        return _mcp_text(output[-MAX_OUTPUT_CHARS:])
    return _mcp_error_text(f"未知工具：{name}", "invalid")


def mcp_serve(hand: "HandServer") -> int:
    """stdio MCP 服务：stdin 读一行一条 JSON-RPC，stdout 回一行一条。
    方法：initialize / ping / tools/list / tools/call；notifications/* 无回复；
    未知方法 -32601；解析失败 -32700；非法请求 -32600。"""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            sys.stdout.write(json.dumps(
                {"jsonrpc": "2.0", "id": None,
                 "error": {"code": -32700, "message": "Parse error"}},
                ensure_ascii=False) + "\n")
            sys.stdout.flush()
            continue
        if not isinstance(req, dict) or "method" not in req:
            sys.stdout.write(json.dumps(
                {"jsonrpc": "2.0", "id": req.get("id") if isinstance(req, dict) else None,
                 "error": {"code": -32600, "message": "Invalid Request"}},
                ensure_ascii=False) + "\n")
            sys.stdout.flush()
            continue
        req_id = req.get("id")
        method = req.get("method", "")
        if method.startswith("notifications/"):
            continue
        if method == "initialize":
            result = {"protocolVersion": MCP_PROTOCOL_VERSION,
                      "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": MCP_SERVER_NAME,
                                     "version": MCP_SERVER_VERSION}}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": mcp_tool_defs()}
        elif method == "tools/call":
            params = req.get("params", {}) or {}
            result = _mcp_call_tool(hand, str(params.get("name", "")),
                                    params.get("arguments", {}) or {})
        else:
            sys.stdout.write(json.dumps(
                {"jsonrpc": "2.0", "id": req_id,
                 "error": {"code": -32601, "message": f"Method not found: {method}"}},
                ensure_ascii=False) + "\n")
            sys.stdout.flush()
            continue
        sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": req_id,
                                     "result": result}, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    return 0




if __name__ == "__main__":
    raise SystemExit(main())
