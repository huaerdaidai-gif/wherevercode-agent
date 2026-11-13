#!/usr/bin/env python3
"""hand_server.py 的单元/集成测试（unittest，本地 fake 环境，不连真实 VPS）。

覆盖：
- RFC 7748 §6.1 X25519 测试向量（与 App 侧 RemoteCryptoInteropTest 同款锚点）；
- seal/open 往返与篡改失败；
- 凭证签名密钥推导 + legacy v2 凭证签发/校验；
- hello 往返（未配对 → PENDING，密封调用被拒；批准后可调用）；
- fake agent 运行（行级进度 + 终态结果）；
- 控制 pause/resume/stop（SIGSTOP/SIGCONT/SIGTERM，stop 终态为
  remote_controlled_stop）；
- 路径穿越拒绝；
- 改目标后旧快照被拒（STALE_GOAL_VERSION）。
"""

import base64
import hashlib
import hmac
import importlib.util
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)

import hand_server as hs


def load_bundled_hand_server():
    """Load the exact Hand implementation packaged and installed by Android."""
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    path = os.path.join(
        repo_root, "android", "app", "src", "main", "assets", "install",
        "hand_server.py",
    )
    spec = importlib.util.spec_from_file_location("bundled_hand_server", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def b64e(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def make_credential(signing_key: bytes, task_id: str, target: str, folder: str,
                    agent_id: str = "", now: int | None = None, source: str = "client") -> str:
    """Sign a v2 credential using the Android wire format."""
    now = now if now is not None else int(time.time())
    cred = {
        "user_id": "", "task_id": task_id, "attempt": "",
        "source_device_id": source, "target_device_id": target,
        "agent_id": agent_id, "folder": folder,
        "iat": now, "exp": now + 900, "jti": str(uuid.uuid4()),
        "scope": {"agent_id": agent_id, "folder": folder},
        "epoch": 1, "goal_version": 1,
    }
    mac_key = hashlib.sha256(signing_key).digest()
    sig = hmac.new(mac_key, hs.canonical_payload(cred), hashlib.sha256).digest()
    obj = dict(cred)
    obj["v"] = 2
    obj["sig_b64"] = base64.b64encode(sig).decode("ascii")
    return hs.b64url_e(json.dumps(obj).encode("utf-8"))


class Client:
    """测试用最小客户端：hello + 密封调用，与 App 侧 HandClient 同协议。"""

    def __init__(self, host: str, port: int, server_device_id: str):
        self.host = host
        self.port = port
        self.server_device_id = server_device_id
        self.device_id = "test-phone-" + uuid.uuid4().hex[:8]
        self.priv = X25519PrivateKey.generate()
        self.priv_bytes = self.priv.private_bytes_raw()
        self.pub_bytes = self.priv.public_key().public_bytes_raw()
        self.server_pub: bytes | None = None
        self.session_key: bytes | None = None
        self.signing_key: bytes | None = None

    def hello(self) -> dict:
        conn = socket.create_connection((self.host, self.port), timeout=10)
        try:
            payload = json.dumps({
                "type": "wherevercode_hello", "version": 1,
                "device_id": self.device_id,
                "public_key_b64": b64e(self.pub_bytes),
            }).encode("utf-8")
            hs.write_frame(conn, payload)
            reply = json.loads(hs.read_frame(conn, timeout=10).decode("utf-8"))
        finally:
            conn.close()
        assert reply["type"] == "wherevercode_hello"
        self.server_pub = hs.b64d(reply["public_key_b64"])
        self.session_key = hs.derive_session_key(
            self.priv_bytes, self.pub_bytes, self.server_pub)
        self.signing_key = hs.derive_signing_key(self.session_key)
        return reply

    def call(self, envelope: dict, timeout: float = 30) -> list[dict]:
        """一次调用：新连接，hello + 密封帧，收齐流式回包后返回。"""
        assert self.session_key is not None and self.signing_key is not None
        conn = socket.create_connection((self.host, self.port), timeout=timeout)
        try:
            hs.write_frame(conn, json.dumps({
                "type": "wherevercode_hello", "version": 1,
                "device_id": self.device_id,
                "public_key_b64": b64e(self.pub_bytes),
            }).encode("utf-8"))
            hs.read_frame(conn, timeout=10)
            hs.write_frame(
                conn,
                hs.seal(self.session_key, json.dumps(envelope).encode("utf-8")),
            )
            events = []
            while True:
                try:
                    frame = hs.read_frame(conn, timeout=timeout)
                except (ConnectionError, socket.timeout):
                    break
                opened = hs.open_sealed(self.session_key, frame)
                events.append(json.loads(opened.decode("utf-8")))
                if events[-1]["type"] in ("cross_device_result", "cross_device_error"):
                    break
            return events
        finally:
            conn.close()

    def tool_call(self, task_id: str, agent_id: str, folder: str,
                  action: str, args: dict, timeout: float = 30) -> list[dict]:
        assert self.signing_key is not None
        args = dict(args)
        args.setdefault("credential", make_credential(
            self.signing_key, task_id, self.server_device_id, folder, agent_id, source=self.device_id))
        envelope = {
            "type": "cross_device_tool_call", "version": 1, "seq": 1,
            "task_id": task_id, "agent_id": agent_id,
            "source_device_id": self.device_id,
            "target_device_id": self.server_device_id,
            "folder": folder, "action": action, "args": args,
        }
        return self.call(envelope, timeout=timeout)


class BundledHandAuthorizationTest(unittest.TestCase):
    def test_source_and_bundled_dispatch_authorization_accept_and_reject_matrix(self):
        """Exercise the same authenticated _dispatch path shipped by both copies."""
        modules = (hs, load_bundled_hand_server())
        for module in modules:
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                base = os.path.join(temp, "agent-home")
                project = os.path.join(temp, "project")
                outside = os.path.join(temp, "outside")
                for path in (base, project, outside):
                    os.mkdir(path)
                server = module.HandServer(os.path.join(temp, "state"), "device", "test")
                server.state.save_json("auth.json", {"agent": [base], "other": [base]})
                events = []
                server._send = lambda conn, key, envelope, kind, args, seq: events.append((kind, args))
                signing_key = b"r" * 32

                def dispatch(*, task, envelope_agent="agent", credential_agent="agent",
                             envelope_folder=base, credential_folder=base, requested=project):
                    env = {
                        "type": "cross_device_tool_call", "version": 1, "seq": 1,
                        "task_id": task, "agent_id": envelope_agent,
                        "source_device_id": "client", "target_device_id": "device",
                        "folder": envelope_folder, "action": "authorize_project_folder",
                        "args": {
                            "credential": make_credential(signing_key, task, "device",
                                                           credential_folder, credential_agent),
                            "folder": requested,
                        },
                    }
                    server._dispatch(None, b"k" * 32, signing_key, "client", env)
                    return events[-1]

                kind, args = dispatch(task="ok")
                self.assertEqual("cross_device_result", kind)
                ack = json.loads(args["output"])
                self.assertEqual(project, ack["requested_folder"])
                self.assertEqual("agent", ack["agent_id"])
                self.assertEqual(os.path.realpath(project), ack["folder"])

                kind, args = dispatch(task="identity", envelope_agent="unknown-agent")
                self.assertEqual("cross_device_error", kind)
                self.assertEqual(module.ERR_WRONG_TASK, args["code"])

                kind, args = dispatch(task="wrong-agent-same-folder", envelope_agent="other")
                self.assertEqual("cross_device_error", kind)
                self.assertEqual(module.ERR_WRONG_TASK, args["code"])

                kind, args = dispatch(task="folder", envelope_folder=project,
                                      credential_folder=base, requested=outside)
                self.assertEqual("cross_device_error", kind)
                self.assertEqual(module.ERR_WRONG_FOLDER, args["code"])

                kind, args = dispatch(task="rejected", requested=os.path.join(temp, "missing"))
                self.assertEqual("cross_device_error", kind)
                self.assertEqual(module.ERR_PATH_NOT_ALLOWED, args["code"])

    def test_authorization_receipts_match_android_kotlin_fixtures(self):
        """Pin the exact receipt bytes both Hands send to the fixtures in
        android/.../core/project/ProjectFolderAuthorizationPathTest.kt, so the
        App-side verification test cannot drift from what Hands really emit."""
        expected = {
            "hand_server": (
                '{"authorized": true, "requested_folder": "/srv/project-link", "agent_id": "agent", "already": false, "folder": "/srv/real-project"}',
                '{"authorized": true, "requested_folder": "/srv/project-link", "agent_id": "agent", "already": true, "folder": "/srv/real-project"}',
            ),
            "bundled_hand_server": (
                '{"authorized": true, "agent_id": "agent", "requested_folder": "/srv/project-link", "folder": "/srv/real-project", "already": false}',
                '{"authorized": true, "agent_id": "agent", "requested_folder": "/srv/project-link", "folder": "/srv/real-project", "already": true}',
            ),
        }
        for module in (hs, load_bundled_hand_server()):
            with self.subTest(module=module.__name__), tempfile.TemporaryDirectory() as temp:
                base = os.path.join(temp, "agent-home")
                real = os.path.join(temp, "real-project")
                link = os.path.join(temp, "project-link")
                os.mkdir(base)
                os.mkdir(real)
                os.symlink(real, link)
                server = module.HandServer(os.path.join(temp, "state"), "device", "test")
                server.state.save_json("auth.json", {"agent": [base]})
                events = []
                server._send = lambda conn, key, envelope, kind, args, seq: events.append((kind, args))
                signing_key = b"r" * 32
                outputs = []
                for task in ("first", "repeat"):
                    env = {
                        "type": "cross_device_tool_call", "version": 1, "seq": 1,
                        "task_id": task, "agent_id": "agent",
                        "source_device_id": "client", "target_device_id": "device",
                        "folder": base, "action": "authorize_project_folder",
                        "args": {
                            "credential": make_credential(signing_key, task, "device", base, "agent"),
                            "folder": link,
                        },
                    }
                    server._dispatch(None, b"k" * 32, signing_key, "client", env)
                    kind, args = events[-1]
                    self.assertEqual("cross_device_result", kind)
                    output = args["output"]
                    for root in (os.path.realpath(temp), temp):
                        output = output.replace(root, "/srv")
                    outputs.append(output)
                self.assertEqual(expected[module.__name__], tuple(outputs))

    def test_console_http_upgrade_requires_a_live_paired_client_ticket(self):
        import http.client
        bundled = load_bundled_hand_server()
        with tempfile.TemporaryDirectory() as temp:
            hand = bundled.HandServer(temp, "test-hand", "test")
            hand.state.save_json("paired_clients.json", {"owner": {"status": "APPROVED"}})
            with bundled.ThreadedHandServer(("127.0.0.1", 0), bundled.HandConnectionHandler, hand) as tcp:
                thread = threading.Thread(target=tcp.serve_forever, daemon=True)
                thread.start()
                try:
                    ticket, _ = hand.issue_ws_token("owner")
                    revoked, _ = hand.issue_ws_token("other")
                    for token, status in ((revoked, 403), (ticket, 101), (ticket, 403)):
                        conn = http.client.HTTPConnection(*tcp.server_address, timeout=5)
                        try:
                            conn.request("GET", "/events?token=" + token, headers={
                                "Upgrade": "websocket", "Connection": "Upgrade",
                                "Sec-WebSocket-Version": "13",
                                "Sec-WebSocket-Key": b64e(os.urandom(16)),
                            })
                            with conn.getresponse() as response:
                                self.assertEqual(status, response.status)
                        finally:
                            conn.close()
                    owner = hand._ws_subscribe("owner")
                    other = hand._ws_subscribe("other")
                    hand.publish_event("owner", "agent.run.progress", {"message": "private"})
                    self.assertIn(b"private", owner.queue.get_nowait())
                    self.assertTrue(other.queue.empty())
                finally:
                    tcp.shutdown()
                    thread.join(timeout=5)

    def test_dispatch_does_not_treat_unapproved_envelope_folder_as_authorized(self):
        bundled = load_bundled_hand_server()
        with tempfile.TemporaryDirectory() as temp:
            base = os.path.join(temp, "agent-home")
            project = os.path.join(temp, "project")
            os.mkdir(base)
            os.mkdir(project)
            server = bundled.HandServer(os.path.join(temp, "state"), "device", "test")
            server.state.save_json("auth.json", {
                "agent": {"folders": [base], "authorized_project_folders": []}
            })
            signing_key = b"s" * 32
            task_id = "authorize-test"
            events = []
            server._send = lambda conn, key, envelope, kind, args, seq: events.append((kind, args))
            envelope = {
                "type": "cross_device_tool_call", "version": 1, "seq": 1,
                "task_id": task_id, "agent_id": "agent",
                "source_device_id": "client", "target_device_id": "device",
                "folder": project, "action": "authorize_project_folder",
                "args": {
                    "credential": make_credential(signing_key, task_id, "device", project, "agent"),
                    "folder": project,
                },
            }

            server._dispatch(None, b"k" * 32, signing_key, "client", envelope)

            self.assertEqual("cross_device_error", events[-1][0])
            self.assertEqual(bundled.ERR_WRONG_FOLDER, events[-1][1]["code"])
            auth = server.state.load_json("auth.json", {})
            self.assertEqual([], auth["agent"]["authorized_project_folders"])

    def test_authorization_uses_requested_args_folder_and_echoes_it(self):
        bundled = load_bundled_hand_server()
        with tempfile.TemporaryDirectory() as temp:
            base = os.path.join(temp, "agent-home")
            requested = os.path.join(temp, "project")
            os.mkdir(base)
            os.mkdir(requested)
            server = bundled.HandServer(os.path.join(temp, "state"), "device", "test")
            server.state.save_json("auth.json", {
                "agent": {"folders": [base], "authorized_project_folders": []}
            })
            events = []
            server._send = lambda conn, key, envelope, kind, args, seq: events.append(args)

            server._do_authorize_project_folder(
                None, b"", {"task_id": "task"}, 1, "task", "agent",
                {"folder": requested}, "client",
            )

            result = json.loads(events[-1]["output"])
            self.assertTrue(result["authorized"])
            self.assertEqual(requested, result["requested_folder"])
            self.assertEqual(os.path.realpath(requested), result["folder"])
            grants = server.state.load_json("project_grants.json", {})
            self.assertEqual({"client": {"agent": [os.path.realpath(requested)]}}, grants)


class HandServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = os.path.join(self.tmp.name, "work")
        os.makedirs(self.work)
        # fake agent：逐行输出三行后打印结果并退出。
        self.fake_agent = os.path.join(self.tmp.name, "fake-agent.sh")
        with open(self.fake_agent, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\n"
                    "echo 'step one'\n"
                    "echo 'step two'\n"
                    "echo 'RESULT: ok'\n")
        os.chmod(self.fake_agent, 0o755)
        # slow agent：用于暂停/停止测试。
        self.slow_agent = os.path.join(self.tmp.name, "slow-agent.sh")
        with open(self.slow_agent, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\n"
                    "i=0\n"
                    "while [ $i -lt 60 ]; do echo \"tick $i\"; sleep 1; i=$((i+1)); done\n"
                    "echo 'RESULT: slow done'\n")
        os.chmod(self.slow_agent, 0o755)
        state_dir = os.path.join(self.tmp.name, "state")
        self.server = hs.HandServer(state_dir, "test-vps", "Test VPS")
        self.server.state.save_json("agents.json", {
            "fake-agent": {
                "display_name": "Fake Agent", "kind": "agent",
                "capabilities": ["dev"], "folder": self.work,
                "command": [self.fake_agent, "{prompt}"], "env": {},
            },
            "slow-agent": {
                "display_name": "Slow Agent", "kind": "agent",
                "capabilities": ["dev"], "folder": self.work,
                "command": [self.slow_agent, "{prompt}"], "env": {},
            },
        })
        self.server.state.save_json("auth.json", {
            "fake-agent": [self.work],
            "slow-agent": [self.work],
        })
        self.tcp = hs.ThreadedHandServer(
            ("127.0.0.1", 0), hs.HandConnectionHandler, self.server)
        self.port = self.tcp.server_address[1]
        self.thread = threading.Thread(target=self.tcp.serve_forever, daemon=True)
        self.thread.start()
        self.client = Client("127.0.0.1", self.port, "test-vps")

    def tearDown(self):
        self.tcp.shutdown()
        self.tcp.server_close()
        self.tmp.cleanup()

    def approve_client(self):
        self.client.hello()
        clients = self.server.state.load_json("paired_clients.json", {})
        self.assertIn(self.client.device_id, clients)
        self.assertEqual(clients[self.client.device_id]["status"], "PENDING")
        clients[self.client.device_id]["status"] = "APPROVED"
        self.server.state.save_json("paired_clients.json", clients)

    # -- 密码学 ----------------------------------------------------------

    def test_rfc7748_vector(self):
        # 与 App 侧 RemoteCryptoInteropTest 同款断言：原始共享密钥
        # 4a5d9d5b… 由 X25519 交换本身得出；会话密钥是 HKDF(shared)，
        # 与原始共享密钥不同（加盐派生），但双方算出的一致，且能互解。
        alice_priv = bytes.fromhex(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        alice_pub = bytes.fromhex(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        bob_priv = bytes.fromhex(
            "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        bob_pub = bytes.fromhex(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        expected_shared = bytes.fromhex(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        alice_raw = X25519PrivateKey.from_private_bytes(
            alice_priv).exchange(X25519PublicKey.from_public_bytes(bob_pub))
        self.assertEqual(alice_raw, expected_shared)
        bob_raw = X25519PrivateKey.from_private_bytes(
            bob_priv).exchange(X25519PublicKey.from_public_bytes(alice_pub))
        self.assertEqual(bob_raw, expected_shared)
        alice_session = hs.derive_session_key(alice_priv, alice_pub, bob_pub)
        bob_session = hs.derive_session_key(bob_priv, bob_pub, alice_pub)
        self.assertEqual(alice_session, bob_session)
        self.assertEqual(len(alice_session), 32)
        self.assertNotEqual(alice_session, expected_shared)
        sealed = hs.seal(alice_session, b"interop")
        self.assertEqual(hs.open_sealed(bob_session, sealed), b"interop")

    def test_seal_open_roundtrip_and_tamper(self):
        key = os.urandom(32)
        sealed = hs.seal(key, b"hello world")
        self.assertEqual(hs.open_sealed(key, sealed), b"hello world")
        tampered = bytearray(sealed)
        tampered[-1] ^= 1
        with self.assertRaises(Exception):
            hs.open_sealed(key, bytes(tampered))
        with self.assertRaises(Exception):
            hs.open_sealed(os.urandom(32), sealed)

    def test_signing_key_deterministic(self):
        session_key = os.urandom(32)
        self.assertEqual(hs.derive_signing_key(session_key),
                         hs.derive_signing_key(session_key))
        self.assertNotEqual(hs.derive_signing_key(session_key), session_key)

    def test_credential_verify_roundtrip(self):
        signing_key = os.urandom(32)
        wire = make_credential(signing_key, "t1", "test-vps", self.work, "fake-agent")
        cred = hs.parse_wire_credential(wire)
        self.assertIsNotNone(cred)
        err = hs.verify_credential(
            cred, signing_key, "t1", "test-vps", self.work, "fake-agent",
            [self.work], set(), set(), int(time.time()))
        self.assertIsNone(err)
        # 篡改任务 id → 签名不符。
        cred2 = dict(cred)
        cred2["task_id"] = "other"
        err2 = hs.verify_credential(
            cred2, signing_key, "other", "test-vps", self.work, "fake-agent",
            [self.work], set(), set(), int(time.time()))
        self.assertEqual(err2, hs.ERR_INVALID_CREDENTIAL)

    # -- hello / 配对 ------------------------------------------------------

    def test_hello_returns_agents_and_pending(self):
        reply = self.client.hello()
        self.assertEqual(reply["device_id"], "test-vps")
        agent_ids = [a["agent_id"] for a in reply["agents"]]
        self.assertIn("fake-agent", agent_ids)
        self.assertTrue(reply["agents"][0]["available"])

    # -- hello repos（P1 能力卡，2026-11-11）-------------------------------

    def test_hello_agents_include_git_repos(self):
        # 工作文件夹是个 git 仓库：hello 的 repos 里有分支 + 短 commit。
        import shutil
        import subprocess
        if shutil.which("git") is None:
            self.skipTest("git 不在测试环境里")
        repo = os.path.join(self.tmp.name, "repo")
        os.makedirs(repo)
        subprocess.run(["git", "init", "-q", repo], check=True, timeout=30)
        subprocess.run(["git", "-C", repo, "config", "user.email", "t@t"],
                       check=True, timeout=10)
        subprocess.run(["git", "-C", repo, "config", "user.name", "t"],
                       check=True, timeout=10)
        with open(os.path.join(repo, "a.txt"), "w", encoding="utf-8") as f:
            f.write("hello")
        subprocess.run(["git", "-C", repo, "add", "."], check=True, timeout=10)
        subprocess.run(["git", "-C", repo, "commit", "-qm", "init"],
                       check=True, timeout=30)
        branch = subprocess.run(
            ["git", "-C", repo, "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, check=True, timeout=10).stdout.strip()
        commit = subprocess.run(
            ["git", "-C", repo, "rev-parse", "--short=12", "HEAD"],
            capture_output=True, text=True, check=True, timeout=10).stdout.strip()
        agents = self.server.state.load_json("agents.json", {})
        agents["repo-agent"] = {
            "display_name": "Repo Agent", "kind": "agent",
            "capabilities": ["dev"], "folder": repo,
            "command": [self.fake_agent, "{prompt}"], "env": {},
        }
        self.server.state.save_json("agents.json", agents)
        reply = self.client.hello()
        entry = next(a for a in reply["agents"] if a["agent_id"] == "repo-agent")
        self.assertEqual(entry["repos"], [
            {"path": repo, "branch": branch, "commit": commit},
        ])

    def test_hello_agents_non_git_folder_repos_empty(self):
        # 工作文件夹不是 git 仓库：repos 为空数组，不编造。
        reply = self.client.hello()
        entry = next(a for a in reply["agents"] if a["agent_id"] == "fake-agent")
        self.assertEqual(entry["repos"], [])

    def test_git_repos_for_folder_missing_folder_empty(self):
        self.assertEqual(
            hs.git_repos_for_folder(os.path.join(self.tmp.name, "no-such-dir")), [])

    def test_unapproved_client_sealed_call_rejected(self):
        self.client.hello()  # 只记 PENDING，不批准
        events = self.client.tool_call("t1", "fake-agent", self.work, "list",
                                       {"path": ""}, timeout=5)
        self.assertEqual(events, [])  # 服务端直接关闭连接，无回包

    def test_fingerprint_matches_public_key(self):
        self.client.hello()
        expected = hashlib.sha256(self.server.public_key).hexdigest()
        self.assertEqual(self.server.fingerprint(), expected)
        self.assertEqual(len(expected), 64)

    # -- list/read/write ---------------------------------------------------

    def test_list_read_write(self):
        self.approve_client()
        with open(os.path.join(self.work, "a.txt"), "w", encoding="utf-8") as f:
            f.write("hello")
        events = self.client.tool_call("t1", "fake-agent", self.work, "list",
                                       {"path": ""})
        self.assertEqual(events[-1]["type"], "cross_device_result")
        names = [e["name"] for e in json.loads(events[-1]["args"]["output"])]
        self.assertIn("a.txt", names)
        events = self.client.tool_call("t2", "fake-agent", self.work, "read",
                                       {"path": "a.txt"})
        self.assertEqual(events[-1]["args"]["output"], "hello")
        events = self.client.tool_call("t3", "fake-agent", self.work, "write",
                                       {"path": "b.txt", "content": "bye"})
        self.assertEqual(events[-1]["type"], "cross_device_result")
        with open(os.path.join(self.work, "b.txt"), encoding="utf-8") as f:
            self.assertEqual(f.read(), "bye")

    def test_path_traversal_rejected(self):
        self.approve_client()
        events = self.client.tool_call("t1", "fake-agent", self.work, "read",
                                       {"path": "../../etc/passwd"})
        self.assertEqual(events[-1]["type"], "cross_device_error")
        self.assertEqual(events[-1]["args"]["code"], hs.ERR_PATH_NOT_ALLOWED)
        events = self.client.tool_call("t2", "fake-agent", self.work, "write",
                                       {"path": "../escape.txt", "content": "x"})
        self.assertEqual(events[-1]["args"]["code"], hs.ERR_PATH_NOT_ALLOWED)
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "escape.txt")))

    def test_unauthorized_folder_rejected(self):
        self.approve_client()
        events = self.client.tool_call("t1", "fake-agent", "/tmp", "list",
                                       {"path": ""})
        self.assertEqual(events[-1]["type"], "cross_device_error")
        self.assertEqual(events[-1]["args"]["code"], hs.ERR_WRONG_FOLDER)

    def test_authorize_project_folder_requires_existing_absolute_dir_and_is_idempotent(self):
        self.approve_client()
        extra = os.path.join(self.tmp.name, "project")
        os.makedirs(extra)
        events = self.client.tool_call(
            "t-grant", "fake-agent", self.work, "authorize_project_folder",
            {"folder": extra})
        self.assertEqual(events[-1]["type"], "cross_device_result")
        result = json.loads(events[-1]["args"]["output"])
        self.assertTrue(result["authorized"])
        self.assertFalse(result["already"])
        self.assertEqual(extra, result["requested_folder"])
        self.assertEqual("fake-agent", result["agent_id"])
        self.assertIn(os.path.realpath(extra), self.server.authorized_folders("fake-agent", self.client.device_id))

        repeated = self.client.tool_call(
            "t-grant-retry", "fake-agent", self.work, "authorize_project_folder",
            {"folder": extra})
        self.assertTrue(json.loads(repeated[-1]["args"]["output"])["already"])

        relative = self.client.tool_call(
            "t-grant-relative", "fake-agent", self.work, "authorize_project_folder",
            {"folder": "relative/project"})
        self.assertEqual(relative[-1]["type"], "cross_device_error")
        self.assertEqual(relative[-1]["args"]["code"], hs.ERR_PATH_NOT_ALLOWED)

    def test_project_grant_is_bound_to_authenticated_device_and_agent(self):
        self.approve_client()
        project = os.path.realpath(os.path.join(self.tmp.name, "private-project"))
        os.mkdir(project)
        self.client.tool_call("grant", "fake-agent", self.work,
                              "authorize_project_folder", {"folder": project})
        original = self.client
        events = original.tool_call("read", "fake-agent", project, "list", {})
        self.assertEqual("cross_device_result", events[-1]["type"])
        events = original.tool_call("read", "slow-agent", project, "list", {})
        self.assertEqual(hs.ERR_WRONG_FOLDER, events[-1]["args"]["code"])

        self.client = Client("127.0.0.1", self.port, "test-vps")
        self.approve_client()
        events = self.client.tool_call("read", "fake-agent", project, "list", {})
        self.assertEqual(hs.ERR_WRONG_FOLDER, events[-1]["args"]["code"])
        for source in ("", original.device_id):
            credential = make_credential(self.client.signing_key, "read", "test-vps",
                                         self.work, "fake-agent", source=source)
            events = self.client.tool_call("read", "fake-agent", self.work, "list",
                                           {"credential": credential})
            self.assertEqual(hs.ERR_WRONG_TASK, events[-1]["args"]["code"])

    def test_copied_device_id_with_new_key_cannot_use_pairing(self):
        self.approve_client()
        intruder = Client("127.0.0.1", self.port, "test-vps")
        intruder.device_id = self.client.device_id
        intruder.hello()
        self.assertEqual([], intruder.tool_call("read", "fake-agent", self.work, "list", {}))

    def test_delivery_retries_return_identical_bytes_without_executing_again(self):
        self.approve_client()
        with open(self.fake_agent, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\n"
                    "echo run >> invocations\n"
                    "p=${1##*：}\n"
                    'printf "sample" > "$p"\n'
                    "echo done\n")
        run_args = {"argv": json.dumps(["agent", "fake-agent", "deliver"]), "attempt": "attempt-1"}
        run = self.client.tool_call("deliver", "fake-agent", self.work, "run", run_args)[-1]
        self.assertEqual("cross_device_result", run["type"], run)
        record = run["args"]
        self.assertEqual(b"sample", base64.b64decode(record["artifact_b64"]))
        self.assertEqual(hashlib.sha256(b"sample").hexdigest(), record["artifact_sha256"])
        retry = self.client.tool_call("deliver", "fake-agent", self.work, "run", run_args)[-1]
        self.assertEqual(record, retry["args"])
        for attempt, expected in [("attempt-1", None), ("../attempt-1", "artifact_missing")]:
            fetched = self.client.tool_call("deliver", "fake-agent", self.work,
                                            "get_artifact", {"attempt": attempt})[-1]
            self.assertEqual(expected, fetched["args"].get("code"))
            if expected is None:
                self.assertEqual(record, fetched["args"])
        with open(os.path.join(self.work, "invocations"), encoding="utf-8") as f:
            self.assertEqual(["run"], f.read().splitlines())

    def test_hand_crash_kills_suspended_process_group(self):
        import signal
        import subprocess
        marker = os.path.join(self.tmp.name, "watchdog-ready")
        # Launch a separate Hand worker and suspend its actual agent. A
        # marker written after resume would expose an orphan surviving SIGKILL.
        program = """
import os, signal, sys, time
from server.hand_server import spawn_agent
child, lease = spawn_agent([sys.executable, "-c", sys.argv[1], sys.argv[2]])
child.wait()
"""
        agent = """
import os, signal, sys, time
with open(sys.argv[1], "w") as f:
    f.write(str(os.getpid()))
os.kill(os.getpid(), signal.SIGSTOP)
time.sleep(30)
"""
        parent = subprocess.Popen([sys.executable, "-c", program, agent, marker])
        try:
            for _ in range(100):
                try:
                    with open(marker, encoding="utf-8") as f:
                        pid = int(f.read())
                    break
                except (FileNotFoundError, ValueError):
                    time.sleep(0.05)
            else:
                self.fail("agent did not start")
            parent.kill()
            parent.wait(timeout=5)
            for _ in range(100):
                result = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)],
                                        capture_output=True, text=True)
                if result.returncode != 0 or result.stdout.strip().startswith("Z"):
                    break
                time.sleep(0.05)
            else:
                os.killpg(pid, signal.SIGKILL)
                self.fail("agent survived Hand crash")
        finally:
            if parent.poll() is None:
                parent.kill()
                parent.wait(timeout=5)

    def test_delivery_rejects_symlink_and_never_reads_target(self):
        self.approve_client()
        with open(self.fake_agent, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\n"
                    "p=${1##*：}\n"
                    'ln -s /etc/hosts "$p"\n'
                    "echo done\n")
        run = self.client.tool_call("deliver", "fake-agent", self.work, "run", {
            "argv": json.dumps(["agent", "fake-agent", "deliver"]), "attempt": "attempt-1",
        })[-1]
        self.assertEqual("artifact_invalid", run["args"]["code"], run)

    def test_authorize_project_folder_rejects_missing_directory(self):
        self.approve_client()
        missing = os.path.join(self.tmp.name, "missing")
        events = self.client.tool_call(
            "t-grant-missing", "fake-agent", self.work, "authorize_project_folder",
            {"folder": missing})
        self.assertEqual(events[-1]["type"], "cross_device_error")
        self.assertEqual(events[-1]["args"]["code"], hs.ERR_PATH_NOT_ALLOWED)
        self.assertNotIn(missing, self.server.authorized_folders("fake-agent", self.client.device_id))

    # -- run / 控制 ----------------------------------------------------------

    def test_fake_agent_run_streams_progress_and_result(self):
        self.approve_client()
        events = self.client.tool_call(
            "t1", "fake-agent", self.work, "run",
            {"argv": json.dumps(["agent", "fake-agent", "在吗"]),
             "timeout": "30",
             "control": json.dumps({"goal_version": 1, "epoch": 1})})
        kinds = [e["type"] for e in events]
        self.assertIn("cross_device_progress", kinds)
        self.assertEqual(events[-1]["type"], "cross_device_result")
        self.assertIn("RESULT: ok", events[-1]["args"]["output"])
        messages = [e["args"]["message"] for e in events
                    if e["type"] == "cross_device_progress"]
        self.assertIn("step one", messages)

    def test_control_pause_resume_stop(self):
        self.approve_client()
        results: list[list[dict]] = []
        errors: list[Exception] = []

        def run_slow():
            try:
                results.append(self.client.tool_call(
                    "tslow", "slow-agent", self.work, "run",
                    {"argv": json.dumps(["agent", "slow-agent", "慢慢跑"]),
                     "timeout": "120",
                     "control": json.dumps({"goal_version": 1, "epoch": 1})},
                    timeout=130))
            except Exception as e:  # noqa: BLE001 - 测试里记录后断言
                errors.append(e)

        t = threading.Thread(target=run_slow, daemon=True)
        t.start()
        # 等进程起来。
        for _ in range(50):
            if "tslow" in self.server.processes:
                break
            time.sleep(0.1)
        self.assertIn("tslow", self.server.processes)
        proc = self.server.processes["tslow"]
        # pause → 进程停住（SIGSTOP 后 poll 仍为 None，但不再出 tick）。
        ev = self.client.tool_call(
            "tslow", "slow-agent", self.work, "run",
            {"argv": json.dumps(["__control__", "pause", "op-1"]),
             "control": json.dumps({"goal_version": 1, "epoch": 1})},
            timeout=10)
        self.assertEqual(ev[-1]["type"], "cross_device_result")
        self.assertTrue(json.loads(ev[-1]["args"]["output"])["confirmed"])
        # resume → 继续。
        ev = self.client.tool_call(
            "tslow", "slow-agent", self.work, "run",
            {"argv": json.dumps(["__control__", "resume", "op-2"]),
             "control": json.dumps({"goal_version": 1, "epoch": 1})},
            timeout=10)
        self.assertTrue(json.loads(ev[-1]["args"]["output"])["confirmed"])
        # Stop must also work when the process is still suspended.
        ev = self.client.tool_call(
            "tslow", "slow-agent", self.work, "run",
            {"argv": json.dumps(["__control__", "pause", "op-2b"])})
        self.assertTrue(json.loads(ev[-1]["args"]["output"])["confirmed"])
        # stop → run 调用以 remote_controlled_stop 终态。
        ev = self.client.tool_call(
            "tslow", "slow-agent", self.work, "run",
            {"argv": json.dumps(["__control__", "stop", "op-3"]),
             "control": json.dumps({"goal_version": 1, "epoch": 1})},
            timeout=10)
        self.assertTrue(json.loads(ev[-1]["args"]["output"])["confirmed"])
        t.join(timeout=20)
        self.assertFalse(t.is_alive(), "run 调用应在 stop 后结束")
        self.assertEqual(errors, [])
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0][-1]["type"], "cross_device_error")
        self.assertEqual(results[0][-1]["args"]["code"],
                         hs.ERR_REMOTE_CONTROLLED_STOP)

        self.assertIsNotNone(proc.poll())
        retried_stop = self.client.tool_call(
            "tslow", "slow-agent", self.work, "run",
            {"argv": json.dumps(["__control__", "stop", "op-3"])})
        self.assertTrue(json.loads(retried_stop[-1]["args"]["output"])["confirmed"])
        restarted = hs.HandServer(self.server.state.dir, self.server.device_id, "restarted")
        self.assertIn("tslow", restarted.revoked_tasks)
        replay = self.client.tool_call(
            "tslow", "slow-agent", self.work, "run",
            {"argv": json.dumps(["agent", "slow-agent", "retry"])})
        self.assertEqual(hs.ERR_REVOKED_CREDENTIAL, replay[-1]["args"]["code"])

    def test_change_goal_bumps_version_and_stale_snapshot_rejected(self):
        self.approve_client()
        ev = self.client.tool_call(
            "t1", "fake-agent", self.work, "run",
            {"argv": json.dumps(["__control__", "change_goal", "op-9", "新目标"]),
             "control": json.dumps({"goal_version": 1, "epoch": 1})},
            timeout=10)
        out = json.loads(ev[-1]["args"]["output"])
        self.assertTrue(out["confirmed"])
        # 服务端 goal_version 已是 2；旧快照（1）再调 run 应被拒。
        events = self.client.tool_call(
            "t1", "fake-agent", self.work, "run",
            {"argv": json.dumps(["agent", "fake-agent", "再跑一次"]),
             "timeout": "30",
             "control": json.dumps({"goal_version": 1, "epoch": 1})})
        self.assertEqual(events[-1]["type"], "cross_device_error")
        self.assertEqual(events[-1]["args"]["code"], hs.ERR_STALE_GOAL_VERSION)
        # 新快照（2）可通过。
        events = self.client.tool_call(
            "t1", "fake-agent", self.work, "run",
            {"argv": json.dumps(["agent", "fake-agent", "再跑一次"]),
             "timeout": "30",
             "control": json.dumps({"goal_version": 2, "epoch": 1})})
        self.assertEqual(events[-1]["type"], "cross_device_result")


if __name__ == "__main__":
    unittest.main(verbosity=2)
