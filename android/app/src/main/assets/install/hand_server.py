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
  记为 PENDING（只存公钥，不执行）。两种方式批准：
  (1) App 内配对：用户在 App 里核对服务端指纹一致后，填入 6 位
      配对码（安装时已显示，或在服务端跑 `hand_server.py pair-code`
      查看），App 发密封 cross_device_pair_confirm，服务端验码通过
      即 APPROVED；码错 5 次锁定该设备 1 小时（防暴力破解）。
  (2) 服务端命令行：`hand_server.py pair-approve <device-id>`。
  之后该设备的密封调用才会被受理。未配对设备的密封调用
  一律以 cross_device_not_authorized 拒绝（配对确认帧除外）。
  hello 回包带 client_status（本机在该 Hand 上的批准状态），
  App 由此显示“对方待批准”，不再靠猜。

局域网发现（mDNS）：Hand 启动后以 stdlib 最小实现广播
  `_wherevercode._tcp`（PTR/SRV/TXT/A），App 的 NsdManager
  发现即列出；广播失败不影响主服务（记日志继续跑）。

授权（auth.json）：agent_id → 允许访问的文件夹列表；或字典形
  {"folders": […], "authorized_project_folders": […]}，后者是
  逐项授权的项目文件夹（任务/项目定工作目录，见
  authorize_project_folder 密封动作）。
  凭证的 folder 必须在该 agent 的授权列表里（词法归一化后比较），
  否则 wrong_folder。run 只允许注册在 agents.json 里的 agent
  命令（argv[0] == "agent"，argv[1] 为 agent_id），或控制命令
  （argv[0] == "__control__"）。不接受任意 shell 命令。

状态文件（--state-dir，默认 ~/.wherevercode/hand-server）：
  server_key.json  服务端 X25519 密钥对（0600 权限，首次启动生成）
  paired_clients.json  device_id → {public_key_b64, status, added_at}
  auth.json        agent_id → [folder, …] 或
                    {folders:[…], authorized_project_folders:[…]}
  agents.json      agent_id → {display_name, kind, capabilities,
                    folder, command:[…], env:{…}}
  tasks.json       task_id → {goal_version, epoch, agent_id, folder}
  audit.jsonl      审计日志（JSON 行，绝不写凭证、密钥与正文密钥）

依赖：Python 3.10+ 标准库 + cryptography（pip install cryptography）。
不依赖旧平台服务器/账号/数据库。

统一扩展注册表（2026-11-11，通用适配器）：
  七类扩展——adapter（Agent 适配器）、connector（连接器）、plugin
  （插件）、mcp（MCP 服务）、wakeup（唤醒机制）、protocol（通信协议）、
  brain（推理后端）——同走一套注册/发现/启用机制。核心代码不认识
  具体扩展，只认注册表：内置注册（本文件 BUILTIN_EXTENSIONS，纯数据）
  + 各设备 state-dir 下 extensions.json（用户自定义行，声明即注册，
  不改代码、不重启）。自定义行与内置行同等对待：注册、发现、启用
  三步全通；停用只改 enabled 标志，行保留；代码里没有任何自动清理
  自定义行的逻辑，部署也不会碰 extensions.json / agents.json / auth.json。
  Agent 通用适配器是注册表上第一个落地的扩展类型：已知 Agent 表
  就是 adapter 这一类的注册内容；hello 时自动发现（扫描已装 CLI）
  报出可用 Agent；通用 runner run_cli_agent() 一个函数跑任意 CLI，
  stdout 直接抓回，不再为每个 Agent 写 wrapper。
  App 自己也是注册表里一等的 Agent（adapter 行 wherevercode-agent，
  execution=internal）：id 取自 App 的 applicationId
  "wherevercode.agent"（AgentRecord id 形状不许点，点改横线，与仓库
  名同形），执行走 App 内任务引擎（BrainEngine + TaskScheduler +
  ChatTaskDispatcher + 泵），不是外部二进制；Hand 只实现 cli 执行器，
  internal 行在 Hand 上不执行、只登记（见 run_agent_entry）。
  App 作为 Agent 消费推理后端：brain 是独立一类扩展（使用者与后端
  的关系，不是同一回事）。大脑扫描 = 给 App 自己找推理后端，发现
  来源是 (a) App BrainConfig 里的 API Key 模型与会员、(b) Hand 发现
  的设备上已装已登录 CLI 的会员额度；绝不探沙箱目录——探沙箱目录找
  第三方 CLI 当成员是已删的错误方向（BrainBootstrap 的
  CLI_CANDIDATE_BINARIES + seedCliBinary），不是大脑扫描。
  MCP 是 protocol 的一种实现（与 direct-transport、WACF 并列），
  不是唯一协议；webhook 是 wakeup 的一种实现（与 polling 并列），
  按老项目两段式（webhook 只叫醒，执行走 MCP 领单）设计；A2A
  不引入（maintainer 已定），只是不把 MCP 写死成唯一协议。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import ipaddress
import json
import os
import queue
import secrets
import shutil
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
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
# WebSocket 事件流（2026-11-11 控制台 WebSocket，控制台实时推送的服务端一半）
# --------------------------------------------------------------------------
# 零新依赖：Python 标准库自己实现最小 WebSocket 服务端（握手 + 帧
# 编解码）。服务端只发 text 帧（JSON，不掩码）、ping/pong、close；
# 读客户端帧要处理掩码、分片、ping→pong；单条消息上限 1MB。
# 事件信封（定死，App 侧按这个接）：
#   {"v":1,"type":"event","event":"<名>","ts":<unix 秒>,
#    "device_id":"<Hand 设备 id>","data":{...}}

WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
WS_TOKEN_TTL_SECONDS = 120
WS_MAX_MESSAGE_BYTES = 1024 * 1024
WS_PING_INTERVAL_SECONDS = 30.0
WS_QUEUE_MAX = 100

WS_OP_CONTINUATION = 0x0
WS_OP_TEXT = 0x1
WS_OP_BINARY = 0x2
WS_OP_CLOSE = 0x8
WS_OP_PING = 0x9
WS_OP_PONG = 0xA


class WsClosed(Exception):
    """对端已关闭 WebSocket 连接（收到 close 帧或连接断开）。"""


class _WsSubscriber:
    """一个 WS 订阅者：有界队列，上限 WS_QUEUE_MAX 条；满了丢最旧的，
    慢订阅者绝不能堵住 Agent 执行（offer 永不阻塞）。"""

    def __init__(self) -> None:
        self.queue: queue.Queue = queue.Queue(maxsize=WS_QUEUE_MAX)

    def offer(self, payload: bytes) -> None:
        try:
            self.queue.put_nowait(payload)
        except queue.Full:
            try:
                self.queue.get_nowait()
            except queue.Empty:
                return
            try:
                self.queue.put_nowait(payload)
            except queue.Full:
                pass


def ws_compute_accept(key: str) -> str:
    digest = hashlib.sha1((key + WS_MAGIC_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def ws_encode_frame(payload: bytes, opcode: int = WS_OP_TEXT) -> bytes:
    header = bytes([0x80 | (opcode & 0x0F)])
    n = len(payload)
    if n < 126:
        header += bytes([n])
    elif n < 65536:
        header += bytes([126]) + n.to_bytes(2, "big")
    else:
        header += bytes([127]) + n.to_bytes(8, "big")
    return header + payload


def _ws_recv_exact(conn: socket.socket, n: int, timeout: float) -> bytes:
    conn.settimeout(timeout)
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise WsClosed("connection closed while reading websocket frame")
        buf += chunk
    return buf


def ws_read_message(conn: socket.socket, timeout: float = 5.0) -> tuple[int, bytes]:
    """读一条完整客户端消息：处理掩码、分片；ping 当场回 pong；
    收到 close 帧时回一个 close 帧再抛 WsClosed。返回 (opcode, payload)，
    opcode 只会是 TEXT 或 BINARY。单条消息超过上限抛 ValueError。"""
    fragments: list[bytes] = []
    message_opcode: int | None = None
    while True:
        head = _ws_recv_exact(conn, 2, timeout)
        b1, b2 = head[0], head[1]
        fin = bool(b1 & 0x80)
        opcode = b1 & 0x0F
        masked = bool(b2 & 0x80)
        length = b2 & 0x7F
        if length == 126:
            length = int.from_bytes(_ws_recv_exact(conn, 2, timeout), "big")
        elif length == 127:
            length = int.from_bytes(_ws_recv_exact(conn, 8, timeout), "big")
        if length > WS_MAX_MESSAGE_BYTES:
            raise ValueError("websocket message too large")
        mask = _ws_recv_exact(conn, 4, timeout) if masked else b""
        payload = _ws_recv_exact(conn, length, timeout) if length else b""
        if masked:
            payload = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
        if opcode == WS_OP_PING:
            conn.sendall(ws_encode_frame(payload, WS_OP_PONG))
            continue
        if opcode == WS_OP_PONG:
            continue
        if opcode == WS_OP_CLOSE:
            try:
                conn.sendall(ws_encode_frame(payload, WS_OP_CLOSE))
            except OSError:
                pass
            raise WsClosed("close frame received")
        if opcode in (WS_OP_TEXT, WS_OP_BINARY):
            if message_opcode is not None:
                raise ValueError("unexpected data frame during fragmentation")
            message_opcode = opcode
            fragments = [payload]
        elif opcode == WS_OP_CONTINUATION:
            if message_opcode is None:
                raise ValueError("continuation without start")
            fragments.append(payload)
        else:
            raise ValueError(f"unknown websocket opcode: {opcode}")
        if fin:
            total = sum(len(f) for f in fragments)
            if total > WS_MAX_MESSAGE_BYTES:
                raise ValueError("websocket message too large")
            assert message_opcode is not None
            return message_opcode, b"".join(fragments)



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
    if canonical_folder(cred["folder"]) != canonical_folder(expected_folder):
        return ERR_WRONG_FOLDER
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

class HandServer:
    def __init__(self, state_dir: str, device_id: str, display_name: str, port: int = DEFAULT_PORT):
        self.state = StateStore(state_dir)
        self.device_id = device_id
        self.display_name = display_name
        self.port = port
        self.private_key, self.public_key = self._load_or_create_key()
        self.processes: dict[str, subprocess.Popen] = {}  # task_id → 进程
        self.process_lock = threading.Lock()
        self.task_meta: dict[str, dict] = {}  # task_id → {goal_version, epoch}
        self.revoked_jtis: set[str] = set()
        self.revoked_tasks: set[str] = set()
        # 2026-11-11 控制台 WebSocket：事件订阅者集合与票据表。
        # 票据只放内存（token → {client_id, expires_at}），绝不落盘、
        # 绝不记日志；订阅者队列有界，慢订阅者不堵 Agent 执行。
        self.ws_subscribers: set[_WsSubscriber] = set()
        self.ws_subscribers_lock = threading.Lock()
        self.ws_tokens: dict[str, dict] = {}
        self.ws_tokens_lock = threading.Lock()

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

    # -- 配对码 ------------------------------------------------------------
    # 6 位数字短码：App 内配对的第二道验证（第一道是指纹核对）。
    # 码只存在服务端 state-dir（0600），安装时打印、pair-code 命令可查、
    # --rotate 可轮换；配对请求须在密封帧里带对码，否则不批准。
    # 机制照主流短码配对（KDE Connect 双屏同码 / Syncthing 互认）改到
    # 无屏服务端：码在服务端，用户把它抄到手机上，抄码这个动作本身
    # 就是“对方已确认”的证据。

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
                "source": "agents-json",
                "discovered": available,
            })
        return out

    def agent_config(self, agent_id: str) -> dict | None:
        """解析后的 Agent 配置：注册表是“怎么跑”的事实来源
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
            if reg is None:
                if legacy.get("command"):
                    cfg["command"] = legacy["command"]
            elif legacy.get("command") and legacy["command"] != reg["command"]:
                self.state.audit({
                    "event": "legacy_command_ignored", "agent": agent_id,
                    "note": "agents.json command ignored; registry argv wins"})
        return cfg or None

    def authorized_folders(self, agent_id: str) -> list[str]:
        """auth.json 授权 + 注册表声明的文件夹（表里声明即授权，
        解决自动发现的 Agent“hello 报出来也跑不了”的问题）+
        agents.json 的 folder + auth.json[agent_id] 下的
        authorized_project_folders（第四路：任务/项目定工作目录，
        项目文件夹逐项授权，精确相等才放行——子目录也不放行，
        见 verify_credential）。auth.json[agent_id] 可以是列表
        （旧形，只含授权文件夹）或字典 {"folders": […],
        "authorized_project_folders": […]}（新形，由密封动作
        authorize_project_folder 写入）。注册表编辑与 auth.json
        编辑同属设备管理员权限（须 SSH 到设备），信任级别相同。"""
        auth = self.state.load_json("auth.json", {})
        entry = auth.get(agent_id, []) if isinstance(auth, dict) else []
        raw_folders = entry if isinstance(entry, list) else (
            entry.get("folders", []) if isinstance(entry, dict) else [])
        folders = list(raw_folders) if isinstance(raw_folders, list) else []
        reg = resolve_adapters(self.state.dir, self.device_id).get(agent_id)
        if reg is not None and reg.get("folder"):
            folders.append(reg["folder"])
        cfg_folder = (self.agent_config(agent_id) or {}).get("folder", "")
        if cfg_folder:
            folders.append(cfg_folder)
        raw_project = entry.get("authorized_project_folders", []) \
            if isinstance(entry, dict) else []
        if isinstance(raw_project, list):
            folders.extend(raw_project)
        seen: set[str] = set()
        out: list[str] = []
        for folder in folders:
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
            # 2026-11-11 控制台 WebSocket：同端口复用，先偷看前 4 字节。
            # 是 "GET " 走 WebSocket 路径，否则走原有密封协议（一字不动）。
            peeked = self._peek_first_bytes(conn)
            if not peeked:
                return
            if peeked.startswith(b"GET"):
                self._handle_websocket(conn, addr)
            else:
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
            "update_supported": True,
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
        if envelope.get("type") == "wherevercode_ws_token" \
                and envelope.get("version") == VERSION:
            # 2026-11-11 控制台 WebSocket：签发 WS 票据。这条分支仍在
            # APPROVED 检查之后——只有已配对客户端能领票。票据不记日志。
            token, expires_in = self.issue_ws_token(client_id)
            self._send(conn, session_key, envelope,
                       "wherevercode_ws_token_reply",
                       {"token": token, "expires_in": expires_in},
                       int(envelope.get("seq", 1)) + 1)
            return
        if envelope.get("type") == "wherevercode_device_admin" \
                and envelope.get("version") == VERSION:
            # Hand 自更新（2026-11-12）：设备管理动作。这条分支同样在
            # APPROVED 检查之后——只有已配对客户端能调；另要求
            # confirmed（见 _handle_device_admin）。
            self._handle_device_admin(conn, session_key, client_id, envelope)
            return
        self._dispatch(conn, session_key, signing_key, client_id, envelope)

    def _reject_pending(self, conn: socket.socket, session_key: bytes,
                        client_id: str) -> None:
        """拒绝一个未批准（PENDING）客户端的密封调用。

        只做三件事：排空、回错、关连接。绝不解密执行、绝不泄露
        原因以外的任何细节。
        - 排空：客户端 hello 与密封帧常在一次 flush 里发出，密封帧
          很可能已在接收缓冲里；不读掉就关连接，内核会发 RST，
          客户端只看到 "Connection reset"。读掉帧只排空缓冲。
        - 回错：若密封帧能打开（会话密钥对得上），取里面的
          task_id/seq 回包对齐，让客户端把错误归到这次调用上；
          打不开就用空 task_id 与 seq=2（客户端首帧 seq 恒为 1，
          2 是未用过的新序号，防重放守卫不会丢弃）。
        - 关连接：由外层 handle_connection 的 finally 正常关闭
          （FIN），客户端能收到错误帧，不再是 reset。
        只有 hello、没有密封帧的探测连接：读帧会因对方已关闭而
        立即报 ConnectionError，或短超时后返回，直接 return。
        """
        task_id = ""
        seq = 2
        try:
            sealed = read_frame(conn, timeout=10)
        except Exception:
            return
        try:
            plaintext = open_sealed(session_key, sealed)
            envelope = json.loads(plaintext.decode("utf-8"))
            if isinstance(envelope, dict):
                task_id = str(envelope.get("task_id", "") or "")
                req_seq = envelope.get("seq", 1)
                seq = int(req_seq) + 1 if isinstance(req_seq, (int, float)) else 2
        except Exception:
            pass
        try:
            self._error(
                conn, session_key,
                {"task_id": task_id, "agent_id": "",
                 "source_device_id": client_id,
                 "target_device_id": self.device_id,
                 "folder": "", "action": ""},
                seq, ERR_NOT_AUTHORIZED,
                "远端设备尚未批准此手机（配对状态 PENDING）：请在远端设备上"
                "批准此手机后再试（在设备上运行 hand_server.py "
                "pair-approve " + client_id + "）。",
            )
        except Exception:
            pass


    # -- WebSocket 事件流（2026-11-11 控制台 WebSocket） ---------------------
    # 同端口 18743 复用：handle_connection 先偷看前 4 字节，是 "GET "
    # 才走 WebSocket，否则走原有密封协议。鉴权复用密封通道：已配对
    # 客户端经密封信封领一张 120 秒、单次使用的 token，WS 握手只认
    # 这张票，不另搞一套加密、不记 token 日志。事件只发真实事件，
    # 绝不造假；没有事件就不发（liveness 靠 WS ping 帧，不靠心跳事件）。

    def publish_event(self, event: str, data: dict) -> None:
        """往所有 WS 订阅者发一条事件（定死的事件信封格式）。"""
        payload = json.dumps({
            "v": 1, "type": "event", "event": event,
            "ts": int(time.time()), "device_id": self.device_id,
            "data": data,
        }, ensure_ascii=False).encode("utf-8")
        with self.ws_subscribers_lock:
            subscribers = list(self.ws_subscribers)
        for sub in subscribers:
            sub.offer(payload)

    def _ws_subscribe(self) -> _WsSubscriber:
        sub = _WsSubscriber()
        with self.ws_subscribers_lock:
            self.ws_subscribers.add(sub)
        return sub

    def _ws_unsubscribe(self, sub: _WsSubscriber) -> None:
        with self.ws_subscribers_lock:
            self.ws_subscribers.discard(sub)

    def issue_ws_token(self, client_id: str) -> tuple[str, int]:
        """签发一张 WS 票据：32 字节随机数 hex（64 字符），绑 client_id，
        120 秒过期，单次使用。返回值以外的地方绝不记录 token。"""
        token = secrets.token_hex(32)
        now = time.time()
        with self.ws_tokens_lock:
            expired = [t for t, rec in self.ws_tokens.items()
                       if rec["expires_at"] <= now]
            for t in expired:
                del self.ws_tokens[t]
            self.ws_tokens[token] = {"client_id": client_id,
                                     "expires_at": now + WS_TOKEN_TTL_SECONDS}
        return token, WS_TOKEN_TTL_SECONDS

    def consume_ws_token(self, token: str | None) -> str | None:
        """握手时核销票据：通过返回绑定的 client_id，否则返回 None。
        无论通过与否票据都只用一次（查到即删，过期也删）。"""
        if not isinstance(token, str) or len(token) != 64:
            return None
        try:
            int(token, 16)
        except ValueError:
            return None
        with self.ws_tokens_lock:
            rec = self.ws_tokens.pop(token, None)
        if rec is None or rec["expires_at"] <= time.time():
            return None
        return rec["client_id"]

    def _peek_first_bytes(self, conn: socket.socket, timeout: float = 30.0) -> bytes:
        """偷看连接前 4 字节（MSG_PEEK，不消费），区分 WebSocket/HTTP
        与密封帧协议。返回看到的前缀（可能短于 4 字节）；空表示对端
        已关闭或超时无数据。"""
        data = b""
        deadline = time.time() + timeout
        conn.settimeout(1.0)
        while time.time() < deadline:
            try:
                data = conn.recv(4, socket.MSG_PEEK)
            except socket.timeout:
                continue
            except OSError:
                return b""
            if not data:
                return b""
            if len(data) >= 4 or data.startswith(b"GET"):
                return data
            time.sleep(0.05)
        return data

    def _read_http_request(self, conn: socket.socket):
        """读一个 HTTP 请求头（上限 8KB，10 秒超时）。返回
        (method, target, headers)；headers 的键全小写。读不到/超限/
        格式不对返回 None。"""
        conn.settimeout(10.0)
        buf = b""
        while b"\r\n\r\n" not in buf:
            if len(buf) > 8192:
                return None
            try:
                chunk = conn.recv(4096)
            except (OSError, ValueError):
                return None
            if not chunk:
                return None
            buf += chunk
        head, _ = buf.split(b"\r\n\r\n", 1)
        try:
            lines = head.decode("iso-8859-1").split("\r\n")
            method, target, _version = lines[0].split(" ", 2)
            headers: dict[str, str] = {}
            for line in lines[1:]:
                if ":" not in line:
                    continue
                name, value = line.split(":", 1)
                headers[name.strip().lower()] = value.strip()
            return method, target, headers
        except (ValueError, IndexError):
            return None

    def _send_http_error(self, conn: socket.socket, status: int,
                         reason: str, message: str) -> None:
        body = message.encode("utf-8")
        resp = (f"HTTP/1.1 {status} {reason}\r\n"
                "Content-Type: text/plain; charset=utf-8\r\n"
                f"Content-Length: {len(body)}\r\n"
                "Connection: close\r\n\r\n").encode("ascii") + body
        try:
            conn.sendall(resp)
        except OSError:
            pass

    def _ws_send_json(self, conn: socket.socket, event: dict) -> None:
        payload = json.dumps(event, ensure_ascii=False).encode("utf-8")
        conn.sendall(ws_encode_frame(payload, WS_OP_TEXT))

    def _handle_websocket(self, conn: socket.socket, addr) -> None:
        """WebSocket 路径：握手 → 订阅事件流。外层 handle_connection
        负责关连接，这里只管握手与事件循环。"""
        from urllib.parse import parse_qs, urlparse

        request = self._read_http_request(conn)
        if request is None:
            return
        method, target, headers = request
        parsed = urlparse(target)
        if method != "GET" or parsed.path != "/events":
            self._send_http_error(conn, 400, "Bad Request",
                                  "Bad Request: expected GET /events")
            return
        if (headers.get("upgrade", "").lower() != "websocket"
                or "upgrade" not in headers.get("connection", "").lower()
                or headers.get("sec-websocket-version", "") != "13"
                or not headers.get("sec-websocket-key", "")):
            self._send_http_error(conn, 400, "Bad Request",
                                  "Bad Request: expected WebSocket upgrade")
            return
        token = (parse_qs(parsed.query).get("token") or [None])[0]
        client_id = self.consume_ws_token(token)
        if client_id is None:
            self._send_http_error(conn, 403, "Forbidden",
                                  "Forbidden: invalid or expired token")
            return
        accept = ws_compute_accept(headers["sec-websocket-key"])
        try:
            conn.sendall(
                ("HTTP/1.1 101 Switching Protocols\r\n"
                 "Upgrade: websocket\r\n"
                 "Connection: Upgrade\r\n"
                 f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode("ascii"))
        except OSError:
            return
        self.state.audit({"event": "ws_connected", "client": client_id,
                          "from": str(addr)})
        sub = self._ws_subscribe()
        try:
            # 连上先发一次 device.info，再补发当前正在跑的任务。
            agents = [{"agent_id": a.get("agent_id", ""),
                       "display_name": a.get("display_name", ""),
                       "kind": a.get("kind", "")}
                      for a in self.agents()]
            now = int(time.time())
            self._ws_send_json(conn, {
                "v": 1, "type": "event", "event": "device.info",
                "ts": now, "device_id": self.device_id,
                "data": {"device_id": self.device_id,
                         "display_name": self.display_name,
                         "agents": agents, "server_time": now}})
            with self.process_lock:
                running = [(tid, proc) for tid, proc in self.processes.items()
                           if proc.poll() is None]
            for task_id, _proc in running:
                meta = self.task_meta.get(task_id, {})
                self._ws_send_json(conn, {
                    "v": 1, "type": "event", "event": "agent.run.started",
                    "ts": int(time.time()), "device_id": self.device_id,
                    "data": {"task_id": task_id,
                             "agent_id": meta.get("agent_id", ""),
                             "folder": meta.get("folder", "")}})
            self._ws_loop(conn, sub)
        except OSError:
            pass
        finally:
            self._ws_unsubscribe(sub)
            self.state.audit({"event": "ws_disconnected", "client": client_id})

    def _ws_loop(self, conn: socket.socket, sub: _WsSubscriber) -> None:
        """事件循环：队列有事件就发；空闲 30 秒发一个 ping；客户端
        发来的消息一律忽略（控制台只订阅事件，不接受指令）。"""
        import select as _select

        last_send = time.time()
        while True:
            try:
                while True:
                    payload = sub.queue.get_nowait()
                    conn.sendall(ws_encode_frame(payload, WS_OP_TEXT))
                    last_send = time.time()
            except queue.Empty:
                pass
            if time.time() - last_send >= WS_PING_INTERVAL_SECONDS:
                conn.sendall(ws_encode_frame(b"", WS_OP_PING))
                last_send = time.time()
            try:
                readable, _, _ = _select.select([conn], [], [], 0.5)
            except (OSError, ValueError):
                return
            if not readable:
                continue
            try:
                ws_read_message(conn, timeout=5.0)
            except WsClosed:
                return
            except (ValueError, OSError):
                return

    def _handle_pending_pairing(
        self, conn: socket.socket, client_id: str, client_pub: bytes, status: str
    ) -> None:
        """未批准客户端的唯一入口：等一帧密封配对确认，别的不执行。

        流程（App 内配对）：App 发 hello → 本机记 PENDING → 用户在
        App 里核对指纹、填入 6 位配对码 → App 在同一连接上发密封
        cross_device_pair_confirm {code} → 码对即 APPROVED，回密封
        cross_device_pair_approved；码不对回密封 cross_device_error
       （code=pair_code_wrong，含中文原因与剩余次数）。
        不是配对确认帧（旧版 App 直接发的 tool_call 等）→ 回密封
        cross_device_not_authorized，与此前 live 版 _reject_pending
        的行为一致；解不开的帧静默关闭，不泄露原因细节。
        已撤销（REVOKED）的客户端不接受配对确认，须先在服务端
        pair-approve 解除撤销。只等 15 秒：App 的配对确认紧跟 hello
        发出，超时即关，不占线程。
        """
        if status == "REVOKED":
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
        if action not in ("list", "read", "write", "run", "authorize_project_folder"):
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
        # 凭证校验。authorize_project_folder 例外：它的作用就是把
        # folder 写进授权表，待授权的文件夹按定义还不在表里，所以
        # verify_credential 的“folder 已在授权表里”这一项对本动作
        # 不适用——把待授权文件夹临时并入传入的授权表，使该项检查
        # 按构造通过。verify_credential 本身一字不动：签名、过期、
        # 撤销、任务绑定、目标绑定、凭证 folder 与信封 folder 一致
        # 性，全部照常校验。凭证须由配对 App 为本次授权操作签发
        # （task_id 由 App 为这次指派/链检出操作新 mint，
        # folder 即待授权的项目文件夹）。
        authorized = self.authorized_folders(agent_id)
        if action == "authorize_project_folder":
            authorized = authorized + [folder]
        err = verify_credential(
            cred, signing_key,
            expected_task_id=task_id,
            expected_target_device_id=self.device_id,
            expected_folder=folder,
            expected_agent_id=agent_id,
            authorized_folders=authorized,
            revoked_jtis=self.revoked_jtis,
            revoked_tasks=self.revoked_tasks,
            now=int(time.time()),
        )
        if err is not None:
            self._error(conn, session_key, envelope, seq + 1, err,
                        _credential_error_message(err))
            return
        # 执行栅栏：args["control"] 快照必须不旧于服务端记录。
        # 先记下本次调用的凭证 jti：stop/change_goal 只撤销当次凭证。
        self.task_meta.setdefault(task_id, {})["credential_jti"] = cred["jti"]
        control_err = self._check_fence(task_id, args)
        if control_err is not None:
            code, message = control_err
            self._error(conn, session_key, envelope, seq + 1, code, message)
            return
        self.state.audit({"event": "call", "task": task_id, "agent": agent_id,
                          "action": action, "client": client_id})
        if action == "authorize_project_folder":
            self._do_authorize_project_folder(
                conn, session_key, envelope, seq, task_id, agent_id, folder)
        elif action == "list":
            self._do_list(conn, session_key, envelope, seq, folder, args)
        elif action == "read":
            self._do_read(conn, session_key, envelope, seq, folder, args)
        elif action == "write":
            self._do_write(conn, session_key, envelope, seq, folder, args)
        else:
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
        meta = self.task_meta.get(task_id)
        if meta is None:
            return None
        if int(snap.get("epoch", 1)) < int(meta.get("epoch", 1)):
            return (ERR_STALE_EPOCH, "凭证已过期（授权已变更）：授权版本已更新，请重新发起任务")
        if int(snap.get("goal_version", 1)) < int(meta.get("goal_version", 1)):
            return (ERR_STALE_GOAL_VERSION, "凭证已过期（目标已变更）：任务目标已更新，请重新发起任务")
        return None

    # -- 项目文件夹授权 --------------------------------------------------------

    def _do_authorize_project_folder(self, conn, session_key, envelope, seq,
                                     task_id, agent_id, folder) -> None:
        """密封动作 authorize_project_folder：把 folder 追加进
        auth.json[agent_id].authorized_project_folders。
        只允许已配对 App 的密封通道调用（_dispatch 已验签名、验凭证、
        验目标设备；_check_fence 已过）；不挂 MCP，Agent 调不到。
        授权粒度是 Agent→项目文件夹。成功回 cross_device_result，
        output 为 JSON：{"authorized": true, "agent_id", "folder",
        "already"}；already=true 表示重复授权，本次未写文件。"""
        canon = canonical_folder(folder)
        if not canon or canon == "/" or not os.path.isdir(canon):
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"文件夹不存在或不是目录：{folder}")
            return
        with self.state.lock:
            auth = self.state.load_json("auth.json", {})
            if not isinstance(auth, dict):
                auth = {}
            entry = auth.get(agent_id)
            if isinstance(entry, list):
                # 旧形转新形：原列表原样保留为 folders。
                entry = {"folders": entry, "authorized_project_folders": []}
                auth[agent_id] = entry
            elif not isinstance(entry, dict):
                entry = {"authorized_project_folders": []}
                auth[agent_id] = entry
            project = entry.get("authorized_project_folders", [])
            if not isinstance(project, list):
                project = []
            entry["authorized_project_folders"] = project
            already = canon in [canonical_folder(f) for f in project]
            if not already:
                project.append(canon)
                self.state.save_json("auth.json", auth)
        self.state.audit({"event": "authorize_project_folder", "agent": agent_id,
                          "folder": canon, "task": task_id, "already": already})
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": json.dumps(
                       {"authorized": True, "agent_id": agent_id,
                        "folder": canon, "already": already},
                       ensure_ascii=False)}, seq + 1)

    # -- Hand 自更新（App 更新后自动同步已连接设备，2026-11-12）--------------
    #
    # 管理消息 wherevercode_device_admin（_handle_connection 里与
    # wherevercode_ws_token 并列的分支，只在 APPROVED 之后）：设备级动作，
    # 没有 task，不验任务凭证。认证就是密封信封本身（只有配对 App 持有
    # 会话密钥）+ APPROVED 检查 + confirmed 标志（App 只在用户确认或
    # 自动同步开关开时才置 true；没它一律拒绝，防误触）。
    # 三段式：stage（暂存并校验）→ apply（备份→原子替换→重载）→
    # App poll hello 验版本；失败时 watchdog 自动回滚，App 也可发 rollback。
    def _handle_device_admin(self, conn, session_key, client_id, envelope) -> None:
        seq = int(envelope.get("seq", 1))
        action = str(envelope.get("action", ""))
        if action != "update_hand":
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "不支持的管理操作：只允许 update_hand")
            return
        if envelope.get("confirmed") is not True:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "更新 Hand 需要用户确认（confirmed 缺失）：请在 App 里确认后再试")
            return
        args = envelope.get("args", {}) or {}
        if not isinstance(args, dict):
            args = {}
        phase = str(args.get("phase", ""))
        if phase == "stage":
            self._update_stage(conn, session_key, envelope, seq, args)
        elif phase == "apply":
            self._update_apply(conn, session_key, envelope, seq)
        elif phase == "rollback":
            self._update_rollback(conn, session_key, envelope, seq)
        else:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "update_hand 需要 phase=stage|apply|rollback")

    def _live_hand_path(self) -> str:
        """本 Hand 文件的真实路径（解掉 releases/current 这类符号链接）。"""
        return os.path.realpath(__file__)

    def _update_stage(self, conn, session_key, envelope, seq, args) -> None:
        """暂存新版 Hand：写 <live>.new，验 sha256 + py_compile。

        只暂存、不替换、不重启；stage 失败不影响正在跑的版本。
        回 cross_device_result，output 为 JSON：{"staged": true,
        "version", "size"}。
        """
        content_b64 = str(args.get("content_b64", ""))
        expect_sha = str(args.get("sha256", "")).lower()
        version = str(args.get("version", ""))
        if not content_b64 or not expect_sha or not version:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "stage 参数不完整：需要 content_b64 + sha256 + version")
            return
        try:
            padded = content_b64 + "=" * (-len(content_b64) % 4)
            content = base64.urlsafe_b64decode(padded)
        except Exception:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "新 Hand 文件不是合法 base64")
            return
        if len(content) > MAX_HAND_UPDATE_BYTES:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        f"新 Hand 文件太大（{len(content)} 字节，上限 {MAX_HAND_UPDATE_BYTES} 字节）")
            return
        digest = hashlib.sha256(content).hexdigest()
        if digest != expect_sha or digest[:12] != version:
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "新 Hand 文件 sha256 与声明不一致（传输可能损坏），请重试")
            return
        staged = self._live_hand_path() + ".new"
        try:
            tmp = staged + ".tmp"
            with open(tmp, "wb") as f:
                f.write(content)
            os.chmod(tmp, 0o600)
            os.replace(tmp, staged)
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"暂存新版失败（写不进 {staged}）：{e}")
            return
        try:
            compile(content, staged, "exec")
        except SyntaxError as e:
            try:
                os.unlink(staged)
            except OSError:
                pass
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        f"新 Hand 文件语法不对（{e}），已丢弃暂存，未动正在跑的版本")
            return
        self.state.audit({"event": "hand_update_stage", "client": envelope.get("source_device_id", ""),
                          "version": version, "size": len(content)})
        self._send(conn, session_key, envelope, "wherevercode_device_admin_reply",
                   {"staged": True, "version": version, "size": len(content)}, seq + 1)

    def _write_update_watchdog(self, backup_path: str, new_version: str) -> str:
        """写自更新看门狗（state dir/update-watchdog.sh，detach 运行）。

        看门狗在 apply 后 25 秒开始检查：新版起来时会在 state dir 写
        update-health-<新版本>；文件在且是 2 分钟内写的 = 健康，退出。
        三次都看不到 = 新版没起来：把备份拷回 live，重启服务（先试
        launchd，再试 systemd，最后 nohup 原命令兜底），并留
        update-rollback-<新版本> 标记，App poll hello 时能看到回滚。
        """
        import shlex
        live = self._live_hand_path()
        state_dir = self.state.dir
        watchdog = os.path.join(state_dir, "update-watchdog.sh")
        log = os.path.join(state_dir, "update-watchdog.log")
        restart_cmd = " ".join(shlex.quote(a) for a in
                               [sys.executable, live, "--state-dir", state_dir,
                                "--device-id", self.device_id,
                                "--display-name", self.display_name,
                                "--host", "0.0.0.0", "--port", str(self.port), "serve"])
        sh = []
        sh.append('#!/bin/sh')
        sh.append('# V5 Hand 自更新看门狗（apply 时生成，检查完自删）。')
        sh.append('LIVE=' + shlex.quote(live))
        sh.append('BACKUP=' + shlex.quote(backup_path))
        sh.append('STATE=' + shlex.quote(state_dir))
        sh.append('PORT=%d' % self.port)
        sh.append('VERSION=' + shlex.quote(new_version))
        sh.append('RESTART=' + shlex.quote(restart_cmd))
        sh.append('sleep 25')
        sh.append('i=0')
        sh.append('while [ "$i" -lt 3 ]; do')
        sh.append('  if [ -f "$STATE/update-health-$VERSION" ] && [ -n "$(find "$STATE/update-health-$VERSION" -mmin -2 2>/dev/null)" ]; then')
        sh.append('    rm -f "$STATE/update-watchdog.sh"')
        sh.append('    exit 0')
        sh.append('  fi')
        sh.append('  sleep 5; i=$((i + 1))')
        sh.append('done')
        sh.append('# 新版没起来：恢复备份并重启。')
        sh.append('cp "$BACKUP" "$LIVE"')
        sh.append('rm -f "$STATE/update-health-$VERSION"')
        sh.append('date -u +%Y-%m-%dT%H:%M:%SZ > "$STATE/update-rollback-$VERSION"')
        sh.append('if python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect((\'127.0.0.1\', $PORT)); s.close()" 2>/dev/null; then')
        sh.append('  rm -f "$STATE/update-watchdog.sh"')
        sh.append('  exit 0')
        sh.append('fi')
        sh.append('for LABEL in com.wherevercode.hand com.wherevercode.v5-hand; do')
        sh.append('  if launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; then')
        sh.append('    launchctl kickstart -k "gui/$(id -u)/$LABEL" >/dev/null 2>&1')
        sh.append('    rm -f "$STATE/update-watchdog.sh"')
        sh.append('    exit 0')
        sh.append('  fi')
        sh.append('done')
        sh.append('if systemctl list-unit-files v5-hand.service >/dev/null 2>&1; then')
        sh.append('  systemctl restart v5-hand.service >/dev/null 2>&1')
        sh.append('  rm -f "$STATE/update-watchdog.sh"')
        sh.append('  exit 0')
        sh.append('fi')
        sh.append('if systemctl --user list-unit-files wherevercode-v5-hand.service >/dev/null 2>&1; then')
        sh.append('  systemctl --user restart wherevercode-v5-hand.service >/dev/null 2>&1')
        sh.append('  rm -f "$STATE/update-watchdog.sh"')
        sh.append('  exit 0')
        sh.append('fi')
        sh.append('nohup sh -c "$RESTART" >> "$STATE/v5-hand.log" 2>&1 &')
        sh.append('rm -f "$STATE/update-watchdog.sh"')
        sh.append('exit 0')
        script = chr(10).join(sh) + chr(10)
        with open(watchdog, 'w', encoding='utf-8') as f:
            f.write(script)
        os.chmod(watchdog, 0o700)
        with open(log, 'ab') as lf:
            subprocess.Popen(['/bin/sh', watchdog], start_new_session=True,
                             stdin=subprocess.DEVNULL, stdout=lf, stderr=subprocess.STDOUT)
        return watchdog
    def mark_healthy_start(self) -> None:
        """启动成功后在 state dir 写 update-health-<版本>。

        serve() 在监听起来后调一次；看门狗凭它判断新版是不是真的
        起来了（只看文件存在不够，还要看 mtime 在 2 分钟内）。
        """
        try:
            path = os.path.join(self.state.dir, "update-health-" + hand_code_version())
            with open(path, "w", encoding="utf-8") as f:
                f.write(str(int(time.time())))
            os.chmod(path, 0o600)
        except OSError:
            pass

    def _update_apply(self, conn, session_key, envelope, seq) -> None:
        """应用已暂存的新版：备份 → 原子替换 → 看门狗 → 回包 → 退出重载。

        退出后由 supervisor（launchd KeepAlive / systemd Restart=always）
        或看门狗（nohup 设备）拉起新版；新版起不来时看门狗恢复备份。
        回包先发再退出：App 收到 applied 后去 poll hello 验版本。
        """
        live = self._live_hand_path()
        staged = live + ".new"
        if not os.path.isfile(staged):
            self._error(conn, session_key, envelope, seq + 1, ERR_COMMAND_NOT_ALLOWED,
                        "没有已暂存的新版本，请先 stage")
            return
        old_version = hand_code_version()
        backup = live + ".bak-" + (old_version or "unknown")
        try:
            shutil.copy2(live, backup)
            os.chmod(backup, 0o600)
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"备份正在跑的版本失败：{e}")
            return
        try:
            with open(staged, "rb") as f:
                new_digest = hashlib.sha256(f.read()).hexdigest()
            new_version = new_digest[:12]
            os.replace(staged, live)
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"替换 Hand 文件失败：{e}")
            return
        # 删掉同版本旧的健康标记，看门狗只认这次更新后新写的。
        try:
            os.unlink(os.path.join(self.state.dir, "update-health-" + new_version))
        except OSError:
            pass
        try:
            self._write_update_watchdog(backup, new_version)
        except OSError as e:
            # 看门狗写不进也不拦更新：App poll hello 仍能发现新版没起来，
            # 再发 rollback；如实记一条审计，不报假成功。
            self.state.audit({"event": "hand_update_watchdog_failed",
                              "version": new_version, "error": str(e)})
        self.state.audit({"event": "hand_update_apply", "client": envelope.get("source_device_id", ""),
                          "from": old_version, "to": new_version, "backup": backup})
        self._send(conn, session_key, envelope, "wherevercode_device_admin_reply",
                   {"applied": True, "version": new_version, "backup": backup}, seq + 1)
        # 1 秒后退出进程（回包先走完）：supervisor 或看门狗拉起新版。
        # 注意：这里是连接处理线程，sys.exit 只会退出本线程，必须用
        # os._exit 才能结束整个进程。
        threading.Timer(1.0, lambda: os._exit(0)).start()

    def _update_rollback(self, conn, session_key, envelope, seq) -> None:
        """从备份恢复：<live>.bak-<版本> 拷回 live，退出重载。

        备份不存在 = 没东西可回滚，如实报错，不碰正在跑的版本。
        """
        live = self._live_hand_path()
        args = envelope.get("args", {}) or {}
        version = str(args.get("version", "")) if isinstance(args, dict) else ""
        candidates = []
        if version:
            candidates.append(live + ".bak-" + version)
        import glob
        candidates += sorted(glob.glob(live + ".bak-*"), reverse=True)
        backup = next((c for c in candidates if os.path.isfile(c)), None)
        if backup is None:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        "没有可用的备份（未找到 .bak-* 文件），无法回滚")
            return
        try:
            shutil.copy2(backup, live)
        except OSError as e:
            self._error(conn, session_key, envelope, seq + 1, ERR_PATH_NOT_ALLOWED,
                        f"恢复备份失败：{e}")
            return
        self.state.audit({"event": "hand_update_rollback", "client": envelope.get("source_device_id", ""),
                          "backup": backup})
        self._send(conn, session_key, envelope, "wherevercode_device_admin_reply",
                   {"rolled_back": True, "backup": backup}, seq + 1)
        threading.Timer(1.0, lambda: os._exit(0)).start()

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
        self.task_meta[task_id] = {
            "goal_version": self.task_meta.get(task_id, {}).get("goal_version", 1),
            "epoch": self.task_meta.get(task_id, {}).get("epoch", 1),
            "agent_id": run_agent_id,
            "folder": folder,
        }
        next_seq = seq + 1
        # 2026-11-11 控制台 WebSocket：run 真正开始（只发真实事件）。
        # finished 用守卫保证所有出口只发一次。
        self.publish_event("agent.run.started",
                           {"task_id": task_id, "agent_id": run_agent_id,
                            "folder": folder})
        finished_sent = False

        def _finish_run(status: str, detail: str) -> None:
            nonlocal finished_sent
            if finished_sent:
                return
            finished_sent = True
            self.publish_event("agent.run.finished",
                               {"task_id": task_id, "agent_id": run_agent_id,
                                "status": status, "detail": detail})

        def _on_line(line: str) -> None:
            nonlocal next_seq
            message = line.rstrip("\n")[-1000:]
            # 先发 WS 事件再回密封 progress：密封连接断了 WS 侧也不丢行。
            self.publish_event("agent.run.progress",
                               {"task_id": task_id, "agent_id": run_agent_id,
                                "message": message})
            self._send(conn, session_key, envelope, "cross_device_progress",
                       {"message": message}, next_seq)
            next_seq += 1

        def _register_proc(proc) -> None:
            with self.process_lock:
                self.processes[task_id] = proc

        try:
            entry = dict(reg)
            entry["command"] = command
            returncode, output, timed_out = run_agent_entry(
                entry, prompt, task_id, folder,
                env_extra=cfg.get("env", {}) or {}, timeout_s=timeout_s,
                on_line=_on_line, log_path=log_path,
                register_proc=_register_proc)
        except InternalAgentExecution as e:
            _finish_run("error", str(e)[:200])
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        str(e))
            return
        except (BrokenPipeError, ConnectionError):
            # 客户端已断开：杀掉子进程，不再回包。
            # 注意顺序：BrokenPipeError/ConnectionError 都是 OSError 的
            # 子类，必须放在 except OSError 之前，否则这个分支永远是
            # 死代码（旧版就是这个 bug：断开时 _kill_process 没跑到，
            # _error 里再抛一次才由外层关连接）。
            _finish_run("stopped", "客户端已断开，执行已终止。")
            self._kill_process(task_id)
            return
        except OSError as e:
            _finish_run("error", f"无法启动 Agent：{e}"[:200])
            self._error(conn, session_key, envelope, seq + 1, ERR_AGENT_NOT_FOUND,
                        f"无法启动 Agent：{e}")
            return
        finally:
            with self.process_lock:
                self.processes.pop(task_id, None)
        if timed_out:
            _finish_run("timeout", f"Agent 执行超时（{int(timeout_s)} 秒）：已终止。")
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_TIMEOUT,
                        f"Agent 执行超时（{int(timeout_s)} 秒）：已终止。")
            return
        if returncode is not None and returncode < 0:
            # 负退出码 = 被信号杀死。SIGTERM/SIGKILL 且是控制停掉的，
            # 走 remote_controlled_stop；否则按失败。
            if self._was_control_stopped(task_id):
                _finish_run("stopped", "已按控制指令停止。")
                self._error(conn, session_key, envelope, next_seq,
                            ERR_REMOTE_CONTROLLED_STOP, "已按控制指令停止。")
                return
            _finish_run("error", f"Agent 被信号终止（退出码 {returncode}）。")
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_NOT_FOUND,
                        f"Agent 被信号终止（退出码 {returncode}）。")
            return
        if returncode != 0:
            _finish_run("error", f"Agent 执行失败（退出码 {returncode}）。")
            self._error(conn, session_key, envelope, next_seq, ERR_AGENT_NOT_FOUND,
                        f"Agent 执行失败（退出码 {returncode}）："
                        + output[-2000:])
            return
        _finish_run("ok", "执行完成。")
        self._send(conn, session_key, envelope, "cross_device_result",
                   {"output": output[-MAX_OUTPUT_CHARS:]}, next_seq)

    def _was_control_stopped(self, task_id: str) -> bool:
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
                self.task_meta.setdefault(task_id, {})["control_stopped"] = True
                message = "远端执行已停止。"
            else:
                message = "任务未在运行，无需停止。"
        else:  # change_goal
            if running:
                self._kill_process(task_id)
                self.task_meta.setdefault(task_id, {})["control_stopped"] = True
            meta = self.task_meta.setdefault(task_id, {})
            meta["goal_version"] = int(meta.get("goal_version", 1)) + 1
            if new_goal:
                meta["new_goal"] = new_goal
            message = f"目标已更新（goal_version={meta['goal_version']}），旧执行已停止，请按新目标重新派发。"
            self.state.audit({"event": "change_goal", "task": task_id,
                              "operation": operation_id,
                              "goal_version": meta["goal_version"]})
        if action in ("stop", "change_goal"):
            # 只撤销当次运行的凭证（jti），不撤销整个任务：改目标后
            # 任务要能凭新凭证、新 goal_version 快照重新派发；整任务
            # 撤销会让重派永远失败。
            jti = self.task_meta.get(task_id, {}).get("credential_jti")
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


def serve(state_dir: str, device_id: str, display_name: str, host: str, port: int) -> None:
    hand = HandServer(state_dir, device_id, display_name, port)
    with ThreadedHandServer((host, port), HandConnectionHandler, hand) as server:
        print(f"hand server listening on {host}:{port} device={device_id}", flush=True)
        print(f"fingerprint: {hand.fingerprint()}", flush=True)
        print(f"pair code: {hand.get_or_create_pair_code()}（App 配对时填入；轮换：hand_server.py pair-code --rotate）", flush=True)
        print(f"state dir: {hand.state.dir}", flush=True)
        # 自更新看门狗凭它判断新版是不是真的起来了。
        hand.mark_healthy_start()
        # 局域网发现：mDNS 广播（best-effort，失败不影响主服务）。
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
    plug = sub.add_parser("plugin", help="运行一个插件")
    plug_sub = plug.add_subparsers(dest="plugin_action")
    plug_run = plug_sub.add_parser("run", help="运行插件")
    plug_run.add_argument("name")
    wh = sub.add_parser("webhook", help="webhook 唤醒（出站叫醒）")
    wh_sub = wh.add_subparsers(dest="webhook_action")
    wh_probe = wh_sub.add_parser("probe", help="发一条 test 唤醒事件探针")
    wh_probe.add_argument("url")
    wh_probe.add_argument("--event", default="task.assigned")
    sub.add_parser("mcp", help="启动 stdio MCP 服务（list/read/write/run）")

    args = parser.parse_args(argv)
    hand = HandServer(args.state_dir, args.device_id, args.display_name, args.port)

    if args.command in (None, "serve"):
        serve(args.state_dir, args.device_id, args.display_name, args.host, args.port)
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
                # 直接调 grok 二进制（stdout 直抓）；旧的 run-grok.sh
                # wrapper（输出重定向到日志文件）已废弃，见通用 runner。
                "command": ["/usr/local/bin/grok", "-p", "{prompt}",
                            "--always-approve", "--sandbox", "workspace",
                            "--output-format", "plain"],
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
    if args.command == "plugin":
        if args.plugin_action == "run" and args.name == "healthcheck":
            print(json.dumps(run_healthcheck(hand), ensure_ascii=False, indent=2))
            return 0
        print(f"未知插件：{args.name}", file=sys.stderr)
        return 1
    if args.command == "webhook":
        if args.webhook_action == "probe":
            secret = os.environ.get("WHSEC", "")
            result = webhook_deliver(args.url, secret, "test", {},
                                     "这是一条测试通知，说明通知地址可用。")
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result["ok"] else 1
        print("用法：webhook probe <url>（secret 走环境变量 WHSEC）",
              file=sys.stderr)
        return 1
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
    return raw.replace("{task_id}", task_id) if raw else None


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


# --------------------------------------------------------------------------
# 通用 runner：一个函数跑任意 CLI Agent
# --------------------------------------------------------------------------

def run_cli_agent(binary: str, argv_template: list[str], prompt: str,
                  task_id: str, cwd: str, env_extra: dict | None = None,
                  timeout_s: float = 600.0,
                  on_line=None, log_path: str | None = None,
                  register_proc=None,
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
    proc = subprocess.Popen(
        argv,
        cwd=cwd or None,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        # 2026-11-11 回复杂音修复：stderr 必须单独抓，绝不能合进
        # stdout。实测各 CLI 的启动横幅/日志/对话 transcript/
        # token 统计全走 stderr（agy 的 glog 行、codex 的横幅+
        # 配置+transcript+token 数、claude 的模型告警都在 stderr；
        # 正文只在 stdout）。旧版 stderr=STDOUT 把两者合并，
        # 杂音原样进了回复正文。codex 不用改走 --json：plain
        # stdout 本来就是干净的最后一条消息（实测）。
        stderr=subprocess.PIPE,
        bufsize=0,  # 二进制无缓冲：下文用 os.read + select 直接读 fd
        env=env,
        start_new_session=True,
    )
    if register_proc is not None:
        register_proc(proc)
    # stderr 后台线程持续排空：不读会堵满 64KB 管道导致子进程挂起。
    # 内容只记服务端日志（保留 session id / token 数等诊断信息），
    # 不进 on_line/output。stdout 为空时也不用 stderr 兜底——那会
    # 把杂音重新带回正文。
    stderr_lines: list[str] = []
    stderr_lock = threading.Lock()
    stderr_done = threading.Event()

    def _drain_stderr() -> None:
        try:
            assert proc.stderr is not None
            err_fd = proc.stderr.fileno()
            while True:
                chunk = os.read(err_fd, 65536)
                if not chunk:
                    break
                text = chunk.decode("utf-8", errors="replace")
                with stderr_lock:
                    stderr_lines.append(text)
                    # 只留最后 ~64KB 诊断用，不让长日志无限涨内存。
                    total = sum(len(t) for t in stderr_lines)
                    while total > 65536 and len(stderr_lines) > 1:
                        total -= len(stderr_lines.pop(0))
        except (OSError, ValueError):
            pass
        finally:
            stderr_done.set()

    err_thread = threading.Thread(target=_drain_stderr, daemon=True)
    err_thread.start()
    output_lines: list[str] = []
    timed_out = False
    try:
        deadline = time.time() + timeout_s
        assert proc.stdout is not None
        out_fd = proc.stdout.fileno()
        buf = ""
        while True:
            if proc.poll() is not None and not buf:
                # 2026-11-11 控制台 WebSocket 顺带修：进程已退出时先看
                # 管道里还有没有残留输出。旧版这里直接 break，快退出的
                # 进程（输出已进管道、父进程还没读）会整段丢输出：
                # progress 全丢、result 为空（实测 fake-agent 3/5 次
                # 空输出）。残留数据读完后，下面的 os.read 读到 EOF
                #（b""）再退出；管道已空才直接 break。
                ready_now, _, _ = _select.select([out_fd], [], [], 0)
                if not ready_now:
                    break
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
                    if on_line is not None:
                        on_line(line)
            elif proc.poll() is not None:
                break
        if buf:
            output_lines.append(buf)
            if on_line is not None:
                on_line(buf)
    except (BrokenPipeError, ConnectionError, OSError):
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except (OSError, ProcessLookupError):
            pass
        raise
    if timed_out:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except (OSError, ProcessLookupError):
            try:
                proc.terminate()
            except OSError:
                pass
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                proc.kill()
            except OSError:
                pass
            proc.wait(timeout=5)
    else:
        try:
            proc.wait(timeout=max(0.0, deadline - time.time()))
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except (OSError, ProcessLookupError):
                pass
            proc.wait(timeout=5)
    output = "".join(output_lines)
    try:
        if proc.stdout is not None:
            proc.stdout.close()
    except OSError:
        pass
    try:
        if proc.stderr is not None:
            proc.stderr.close()
    except OSError:
        pass
    # 等 stderr 排空线程收尾（最多 2 秒，不阻塞服务主循环）。
    stderr_done.wait(timeout=2.0)
    with stderr_lock:
        stderr_text = "".join(stderr_lines)
    if stderr_text.strip():
        print(f"[hand] run_cli_agent task {task_id} stderr "
              f"({len(stderr_text)} bytes, tail):\n"
              f"{stderr_text[-4000:]}", file=sys.stderr, flush=True)
    elif not output_lines:
        print(f"[hand] run_cli_agent task {task_id}：stdout 为空且 stderr 也为空",
              file=sys.stderr, flush=True)
    if log_path:
        try:
            os.makedirs(os.path.dirname(log_path), exist_ok=True)
            with open(log_path, "w", encoding="utf-8") as f:
                f.write(output)
        except OSError:
            pass  # 日志副本写失败不影响主结果
    return proc.returncode, output, timed_out


class InternalAgentExecution(Exception):
    """internal 执行行的调用：须在 App 内走任务引擎，本 Hand 不执行。"""


def run_agent_entry(resolved: dict, prompt: str, task_id: str, cwd: str,
                    env_extra: dict | None = None,
                    timeout_s: float = 600.0,
                    on_line=None, log_path: str | None = None,
                    register_proc=None,
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
                         register_proc=register_proc)


# --------------------------------------------------------------------------
# Webhook 唤醒（wakeup 扩展点的一种实现）
# --------------------------------------------------------------------------
# 两段式（老项目 C3 审计 §3.2，只学思路、源码自主写）：webhook 只叫醒，
# 不带执行载荷、不触发执行；Agent 收到后经 MCP 工具领单执行。
# 事件只有 task.assigned / review.assigned / channel.message 三种，
# 不发暂停/停止/取消（控制走 MCP 控制工具）。
# 发送规则（照抄老项目 outbound_notifier 的安全形状）：只许 https，
# 每次发送重解析 DNS、只连公网 IP、不跟随重定向、单次超时 10 秒、
# Standard Webhooks HMAC 签名、重试 (0, 5, 30, 120) 秒最多 4 次、
# 410 订阅失效、413 不重试；日志只记 host/状态码/错误类型，
# 不记完整 URL、正文、secret。

WEBHOOK_RETRY_DELAYS = (0, 5, 30, 120)
WEBHOOK_TIMEOUT_SECONDS = 10


def webhook_event_id(event_name: str, data: dict) -> str:
    task_id = str(data.get("task_id", ""))
    round_no = data.get("round", "")
    if task_id and round_no != "":
        prefix = "rev" if event_name == "review.assigned" else "task"
        return f"{prefix}_{task_id}_{round_no}"
    return f"{event_name}_{uuid.uuid4().hex[:12]}"


def webhook_deliver(url: str, secret: str, event_name: str, data: dict,
                    message: str, timeout: float = WEBHOOK_TIMEOUT_SECONDS,
                    allow_private: bool = False,
                    max_attempts: int = 4) -> dict:
    """出站叫醒：POST 一个事件通知。返回 {ok, status, attempts, event_id, error?}。
    注意：这只是“叫醒”，2xx 不等于执行完成；执行完成以 MCP 侧的认领 +
    回传为准。allow_private 只给本地测试用，生产路径必须 https + 公网 IP。"""
    from urllib.parse import urlparse

    event_id = webhook_event_id(event_name, data)
    body = json.dumps({
        "eventId": event_id, "name": event_name,
        "timestamp": int(time.time()), "data": data,
        "message": message, "cursor": None,
    }, ensure_ascii=False).encode("utf-8")
    if len(body) > 256 * 1024:
        return {"ok": False, "status": None, "attempts": 0,
                "event_id": event_id, "error": "body_too_large"}
    parsed = urlparse(url)
    is_loopback_http = (parsed.scheme == "http" and allow_private
                        and parsed.hostname in ("localhost", "127.0.0.1", "::1"))
    if (parsed.scheme != "https" and not is_loopback_http) or not parsed.hostname \
            or parsed.username:
        return {"ok": False, "status": None, "attempts": 0,
                "event_id": event_id, "error": "url_must_be_public_https"}
    try:
        infos = socket.getaddrinfo(parsed.hostname, parsed.port or 443,
                                   proto=socket.IPPROTO_TCP)
    except OSError as e:
        return {"ok": False, "status": None, "attempts": 0,
                "event_id": event_id, "error": f"dns_failed: {e}"}
    if not allow_private:
        for info in infos:
            try:
                ip = ipaddress.ip_address(info[4][0])
            except ValueError:
                continue
            if not ip.is_global:
                return {"ok": False, "status": None, "attempts": 0,
                        "event_id": event_id, "error": "url_must_be_public_ip"}
    ts = str(int(time.time()))
    headers = {
        "Content-Type": "application/json",
        "webhook-id": event_id,
        "webhook-timestamp": ts,
    }
    if secret:
        sig = hmac.new(secret.encode("utf-8"),
                       f"{event_id}.{ts}.".encode("utf-8") + body,
                       hashlib.sha256).digest()
        headers["webhook-signature"] = "v1," + base64.b64encode(sig).decode("ascii")
    attempts = 0
    last_status: int | None = None
    last_error = ""
    for delay in WEBHOOK_RETRY_DELAYS[:max_attempts]:
        if delay:
            time.sleep(delay)
        attempts += 1
        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                last_status = resp.status
                if 200 <= resp.status < 300:
                    return {"ok": True, "status": resp.status,
                            "attempts": attempts, "event_id": event_id}
                if resp.status in (410, 413):
                    return {"ok": False, "status": resp.status,
                            "attempts": attempts, "event_id": event_id,
                            "error": "no_retry_status"}
        except urllib.error.HTTPError as e:
            last_status = e.code
            last_error = f"http_{e.code}"
            if e.code in (410, 413):
                break
        except (urllib.error.URLError, OSError, ValueError) as e:
            last_error = str(e)[:200]
    return {"ok": False, "status": last_status, "attempts": attempts,
            "event_id": event_id, "error": last_error or "delivery_failed"}


# --------------------------------------------------------------------------
# 健康检查插件（plugin 扩展点的一个例子）
# --------------------------------------------------------------------------

def run_healthcheck(hand: "HandServer") -> dict:
    checks: list[dict] = []
    state_dir = hand.state.dir
    checks.append({"name": "state_dir_writable",
                   "ok": os.access(state_dir, os.W_OK),
                   "detail": state_dir})
    key_path = os.path.join(state_dir, "server_key.json")
    checks.append({"name": "server_key_present",
                   "ok": os.path.isfile(key_path), "detail": key_path})
    try:
        probe = socket.create_connection(("127.0.0.1", hand.port), timeout=2)
        probe.close()
        checks.append({"name": "port_listening", "ok": True,
                       "detail": f"127.0.0.1:{hand.port}"})
    except OSError as e:
        checks.append({"name": "port_listening", "ok": False, "detail": str(e)})
    # Agent 行：internal 行（App 自身）没有二进制是设计如此，只核对
    # 声明；已装的 CLI 核对二进制；未装的已知行不是故障，单列在
    # not_installed 里，不拉低整体 ok（Hand 健康 ≠ 全量 Agent 在场）。
    not_installed: list[str] = []
    for agent_id, reg in resolve_adapters(state_dir, hand.device_id).items():
        if reg.get("execution", "cli") == "internal":
            checks.append({"name": f"agent_internal:{agent_id}", "ok": True,
                           "detail": reg.get("executor", "") or "internal (App)"})
        elif reg["binary_path"] is not None:
            checks.append({"name": f"agent_binary:{agent_id}", "ok": True,
                           "detail": reg["binary_path"]})
        else:
            not_installed.append(agent_id)
    try:
        free = shutil.disk_usage(state_dir).free
        checks.append({"name": "disk_free_mb", "ok": free > 100 * 1024 * 1024,
                       "detail": f"{free // (1024 * 1024)} MB"})
    except OSError as e:
        checks.append({"name": "disk_free_mb", "ok": False, "detail": str(e)})
    return {"ok": all(c["ok"] for c in checks),
            "device_id": hand.device_id, "checks": checks,
            "not_installed": not_installed}


# --------------------------------------------------------------------------
# MCP 服务（mcp 扩展点；同时是 protocol/mcp 的一个实现）
# --------------------------------------------------------------------------
# 只学老项目 cross_device_mcp.py 的协议思路（stdio JSON-RPC 2.0、
# protocolVersion 2024-11-05、四工具 list/read/write/run），源码自主写。
# 工具语义与 Hand 的 list/read/write/run 同款；run 走通用 runner；
# 文件夹门禁与 Hand 一致（须在授权表里），绝不本地回落、绝不执行
# 白名单外的命令。

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
                # 字典形：folders 与 authorized_project_folders 都算。
                for key in ("folders", "authorized_project_folders"):
                    vals = items.get(key, [])
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
                log_path=log_path)
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
