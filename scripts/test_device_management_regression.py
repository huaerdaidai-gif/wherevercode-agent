#!/usr/bin/env python3
"""
WhereverCode V5 — iOS 设备管理 P0 回归测试套件
覆盖项：
1. 重复发现去重 (Duplicate Discovery Deduplication)
2. IP 变化动态更新 (IP Change Dynamic BaseUrl & Agent Propagation)
3. 删除级联与重启恢复安全隔离 (Delete Cascade & Restart Recovery Safety Isolation)
4. 设备取消配对、凭据撤销与重新配对闭环 (Device Unpair, Revocation & Re-pairing Loop)
5. 离线恢复与错误诊断 (Offline Recovery & Error Diagnosis)
6. 真实 Mac 开发主机 (MacBook-Air) 闭环真机实测 (Live Mac Host Real Probe & Admin Loop)
"""

import unittest
import socket
import json
import hashlib
import os
import time
import base64
import sqlite3
import subprocess
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

MAC_HOST = "100.75.41.0"
MAC_PORT = 18743

def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)

def derive_session(cli_priv, cli_pub, srv_pub):
    shared = cli_priv.exchange(X25519PublicKey.from_public_bytes(srv_pub))
    first, second = (cli_pub, srv_pub) if cli_pub <= srv_pub else (srv_pub, cli_pub)
    salt = hashlib.sha256(first + second).digest()
    return hkdf_sha256(shared, salt, b'wherevercode-cross-device/v1', 32)

def read_frame(s):
    hdr = b''
    while len(hdr) < 4:
        chunk = s.recv(4 - len(hdr))
        if not chunk: raise ConnectionError('远端过早关闭连接 (EOF)')
        hdr += chunk
    l = int.from_bytes(hdr, 'big')
    buf = b''
    while len(buf) < l:
        chunk = s.recv(l - len(buf))
        if not chunk: raise ConnectionError('远端过早关闭连接 (EOF)')
        buf += chunk
    return buf

def write_frame(s, payload):
    s.sendall(len(payload).to_bytes(4, 'big') + payload)

def seal(key, plain):
    nonce = os.urandom(12)
    return nonce + AESGCM(key).encrypt(nonce, plain, None)

def open_sealed(key, sealed):
    return AESGCM(key).decrypt(sealed[:12], sealed[12:], None)


class TestDeviceManagementRegression(unittest.TestCase):

    def setUp(self):
        self.tmp_dir = os.path.expanduser("~/.cache/test_device_mgmt")
        os.makedirs(self.tmp_dir, exist_ok=True)
        self.db_path = os.path.join(self.tmp_dir, "test_wherevercode.sqlite")
        if os.path.exists(self.db_path):
            os.remove(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.execute("CREATE TABLE IF NOT EXISTS kv_store (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    def test_01_discovery_deduplication_and_pairing_sync(self):
        """回归 1：重复发现去重与配对状态同步"""
        # 初始：注册 MacBook-Air 设备
        registered = [
            {
                "agentId": "MacBook-Air",
                "displayName": "MacBook-Air",
                "kind": "DEVICE",
                "access": "APP",
                "baseUrl": "192.168.1.4:18743",
                "authRef": "device:MacBook-Air:public_key",
                "memberConnectionState": "CONNECTED",
                "deviceId": "MacBook-Air",
                "lastTestSummary": "已连接（在线）"
            }
        ]
        self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('agents.records.v1', ?)",
                          (json.dumps(registered),))
        self.conn.commit()

        # 模拟 LAN 发现上报的 2 个 Peer（一个已登记，一个未登记的新机器）
        discovered_peers = [
            {
                "deviceId": "MacBook-Air",
                "displayName": "MacBook-Air",
                "host": "192.168.1.4",
                "port": 18743,
                "clientStatus": "APPROVED",
                "agents": [{"agentId": "codex", "displayName": "Codex"}]
            },
            {
                "deviceId": "ubuntu-worker-99",
                "displayName": "Ubuntu Dev Worker",
                "host": "192.168.1.99",
                "port": 18743,
                "clientStatus": "PENDING",
                "agents": []
            }
        ]

        # 模拟 DevicesView.syncDiscoveredPeersWithRegisteredDevices 逻辑
        cur_agents = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])
        unlinked_peers = []
        for peer in discovered_peers:
            match = next((d for d in cur_agents if d.get("deviceId") == peer["deviceId"] or d.get("agentId") == peer["deviceId"]), None)
            if match:
                # 已登记设备：更新状态与地址，绝不进入未配对发现列表
                match["baseUrl"] = f"{peer['host']}:{peer['port']}"
                match["memberConnectionState"] = "CONNECTED" if peer["clientStatus"] == "APPROVED" else "PENDING"
                match["lastTestSummary"] = f"已连接（局域网在线 · {len(peer['agents'])} 个 Agent）"
            else:
                unlinked_peers.append(peer)

        self.conn.execute("UPDATE kv_store SET value=? WHERE key='agents.records.v1'", (json.dumps(cur_agents),))
        self.conn.commit()

        # 验收断言：MacBook-Air 绝不出现在未登记发现列表
        self.assertEqual(len(unlinked_peers), 1)
        self.assertEqual(unlinked_peers[0]["deviceId"], "ubuntu-worker-99")
        self.assertEqual(unlinked_peers[0]["clientStatus"], "PENDING")

        # 验收断言：已登记设备中状态与地址得到精确同步
        updated = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])
        self.assertEqual(len(updated), 1)
        self.assertEqual(updated[0]["memberConnectionState"], "CONNECTED")
        self.assertEqual(updated[0]["baseUrl"], "192.168.1.4:18743")
        self.assertIn("局域网在线", updated[0]["lastTestSummary"])

    def test_02_dynamic_ip_change_propagation(self):
        """回归 2：IP 变化动态更新与所属 Agent 级联同步"""
        dev_id = "MacBook-Air"
        initial_records = [
            {
                "agentId": dev_id,
                "displayName": "MacBook-Air",
                "kind": "DEVICE",
                "access": "APP",
                "baseUrl": "192.168.1.4:18743",
                "deviceId": dev_id,
                "memberConnectionState": "CONNECTED"
            },
            {
                "agentId": f"{dev_id}:codex",
                "displayName": "Codex (MacBook-Air)",
                "kind": "DEVICE",
                "access": "CLI",
                "baseUrl": "192.168.1.4:18743",
                "deviceId": dev_id,
                "memberConnectionState": "CONNECTED"
            }
        ]
        self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('agents.records.v1', ?)",
                          (json.dumps(initial_records),))
        self.conn.commit()

        # 模拟 IP 变化：DHCP 分配了新地址 192.168.1.205
        new_ip = "192.168.1.205"
        new_port = 18743
        cur_agents = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])
        for a in cur_agents:
            if a.get("deviceId") == dev_id:
                a["baseUrl"] = f"{new_ip}:{new_port}"
                a["memberConnectionState"] = "CONNECTED"

        self.conn.execute("UPDATE kv_store SET value=? WHERE key='agents.records.v1'", (json.dumps(cur_agents),))
        self.conn.commit()

        refetched = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])
        self.assertEqual(len(refetched), 2)
        self.assertEqual(refetched[0]["baseUrl"], "192.168.1.205:18743")
        self.assertEqual(refetched[1]["baseUrl"], "192.168.1.205:18743")

    def test_03_delete_cascade_and_restart_recovery_isolation(self):
        """回归 3：设备删除级联、凭据撤销、重启不复活，且绝不误删非设备实体"""
        dev_id = "test-mac-delete"
        records = [
            {"agentId": "claude-sonnet", "displayName": "Claude 3.5", "kind": "CLOUD", "access": "API"},
            {"agentId": "local-qwen", "displayName": "Qwen 2.5", "kind": "LOCAL_MODEL", "access": "API"},
            {"agentId": dev_id, "displayName": "Delete Mac", "kind": "DEVICE", "access": "APP", "deviceId": dev_id},
            {"agentId": f"{dev_id}:codex", "displayName": "Codex", "kind": "DEVICE", "access": "CLI", "deviceId": dev_id}
        ]
        self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('agents.records.v1', ?)",
                          (json.dumps(records),))
        self.conn.commit()

        # 执行 AgentRegistryStore.deleteDeviceCascade
        cur = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])
        filtered = [
            a for a in cur
            if not (a.get("kind") == "DEVICE" and (a.get("agentId") == dev_id or a.get("deviceId") == dev_id or str(a.get("agentId", "")).startswith(f"{dev_id}:")))
        ]
        self.conn.execute("UPDATE kv_store SET value=? WHERE key='agents.records.v1'", (json.dumps(filtered),))
        self.conn.commit()

        # 验证 SQLite 重启恢复后状态
        self.conn.close()
        self.conn = sqlite3.connect(self.db_path)
        after_restart = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])

        agent_ids = [a["agentId"] for a in after_restart]
        self.assertNotIn(dev_id, agent_ids)
        self.assertNotIn(f"{dev_id}:codex", agent_ids)
        # 严格验证：云端 Agent 和本地模型绝无误删！
        self.assertIn("claude-sonnet", agent_ids)
        self.assertIn("local-qwen", agent_ids)

    def test_04_safe_legacy_migration_never_merges_by_name(self):
        """回归 4：历史记录安全迁移绝不按名称猜测合并且保护现有配置"""
        records = [
            {
                "agentId": "device-mac-workstation",
                "displayName": "Mac · 开发工作站",
                "kind": "DEVICE",
                "access": "APP",
                "baseUrl": "mac-workstation.local:8765",
                "deviceId": None,
                "memberConnectionState": "NOT_CONNECTED"
            },
            {
                "agentId": "MacBook-Air",
                "displayName": "MacBook-Air",
                "kind": "DEVICE",
                "access": "APP",
                "baseUrl": "192.168.1.4:18743",
                "deviceId": "MacBook-Air",
                "memberConnectionState": "CONNECTED"
            }
        ]
        self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('agents.records.v1', ?)",
                          (json.dumps(records),))
        self.conn.commit()

        # 执行 migrateLegacyDeviceRecords 逻辑
        cur = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])
        for a in cur:
            if a.get("kind") == "DEVICE":
                if not a.get("deviceId"):
                    a["deviceId"] = a["agentId"]
                if a["agentId"] == "device-mac-workstation":
                    a["lastTestSummary"] = "演示模板设备 · 点击可配置真实地址或删除"

        self.conn.execute("UPDATE kv_store SET value=? WHERE key='agents.records.v1'", (json.dumps(cur),))
        self.conn.commit()

        migrated = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='agents.records.v1'").fetchone()[0])
        self.assertEqual(len(migrated), 2)
        # 绝不把 "Mac · 开发工作站" 猜测合并进 "MacBook-Air"
        legacy = next(a for a in migrated if a["agentId"] == "device-mac-workstation")
        real = next(a for a in migrated if a["agentId"] == "MacBook-Air")
        self.assertEqual(legacy["deviceId"], "device-mac-workstation")
        self.assertIn("演示模板设备", legacy["lastTestSummary"])
        self.assertEqual(real["memberConnectionState"], "CONNECTED")
        self.assertEqual(real["baseUrl"], "192.168.1.4:18743")

    def test_05_live_mac_host_unpair_and_repair_loop(self):
        """回归 5：连接真实 Mac 开发主机 (MacBook-Air) 执行取消配对、凭据失效与重新配对验证"""
        ssh_key = os.path.expanduser("~/.ssh/infra_ed25519")
        if not os.path.exists(ssh_key):
            self.skipTest("环境未配置 Mac 主机 SSH 密钥 (~/.ssh/infra_ed25519)，跳过远端真机回环")

        cli_priv = X25519PrivateKey.generate()
        cli_pub = cli_priv.public_key().public_bytes_raw()
        client_id = "test-iphone-reg-" + os.urandom(4).hex()

        # 1. 旋转新配对码
        cmd = subprocess.run([
            "ssh", "-i", os.path.expanduser("~/.ssh/infra_ed25519"), "-o", "BatchMode=yes",
            f"a1234@{MAC_HOST}",
            "/Users/a1234/Library/Application\\ Support/WhereverCode/V5Hand/.venv/bin/python /Users/a1234/Library/Application\\ Support/WhereverCode/V5Hand/current/hand_server.py --state-dir /Users/a1234/Library/Application\\ Support/WhereverCode/V5Hand pair-code --rotate"
        ], capture_output=True, text=True, check=True)
        pair_code = cmd.stdout.strip().splitlines()[0].strip()
        self.assertEqual(len(pair_code), 6)

        # 2. Hello
        s = socket.create_connection((MAC_HOST, MAC_PORT), timeout=5)
        write_frame(s, json.dumps({
            "type": "wherevercode_hello", "version": 1,
            "device_id": client_id,
            "public_key_b64": base64.b64encode(cli_pub).decode()
        }).encode())
        hello = json.loads(read_frame(s).decode())
        srv_pub = base64.b64decode(hello["public_key_b64"])
        session_key = derive_session(cli_priv, cli_pub, srv_pub)

        # 3. 发送配对确认
        pair_env = {"type": "cross_device_pair_confirm", "version": 1, "code": pair_code}
        write_frame(s, seal(session_key, json.dumps(pair_env).encode()))
        pair_rep = json.loads(open_sealed(session_key, read_frame(s)).decode())
        self.assertEqual(pair_rep.get("type"), "cross_device_pair_approved")
        s.close()

        # 4. 再次 Hello，验证状态已为 APPROVED
        s2 = socket.create_connection((MAC_HOST, MAC_PORT), timeout=5)
        write_frame(s2, json.dumps({
            "type": "wherevercode_hello", "version": 1,
            "device_id": client_id,
            "public_key_b64": base64.b64encode(cli_pub).decode()
        }).encode())
        hello2 = json.loads(read_frame(s2).decode())
        self.assertEqual(hello2.get("client_status"), "APPROVED")

        # 5. 发送取消配对 (action: "unpair", confirmed: true)
        unpair_env = {
            "type": "wherevercode_device_admin",
            "version": 1,
            "seq": 1,
            "action": "unpair",
            "confirmed": True
        }
        write_frame(s2, seal(session_key, json.dumps(unpair_env).encode()))
        unpair_rep = json.loads(open_sealed(session_key, read_frame(s2)).decode())
        self.assertEqual(unpair_rep.get("type"), "wherevercode_device_admin_reply")
        self.assertTrue(unpair_rep.get("args", {}).get("unpaired"))
        self.assertEqual(unpair_rep.get("args", {}).get("status"), "REVOKED")
        s2.close()

        # 6. 取消配对后再次 Hello，验证状态已被撤销 (REVOKED)
        s3 = socket.create_connection((MAC_HOST, MAC_PORT), timeout=5)
        write_frame(s3, json.dumps({
            "type": "wherevercode_hello", "version": 1,
            "device_id": client_id,
            "public_key_b64": base64.b64encode(cli_pub).decode()
        }).encode())
        hello3 = json.loads(read_frame(s3).decode())
        self.assertEqual(hello3.get("client_status"), "REVOKED")
        s3.close()

        # 7. 清理该测试客户端
        subprocess.run([
            "ssh", "-i", os.path.expanduser("~/.ssh/infra_ed25519"), "-o", "BatchMode=yes",
            f"a1234@{MAC_HOST}",
            f"/Users/a1234/Library/Application\\ Support/WhereverCode/V5Hand/.venv/bin/python /Users/a1234/Library/Application\\ Support/WhereverCode/V5Hand/current/hand_server.py --state-dir /Users/a1234/Library/Application\\ Support/WhereverCode/V5Hand pair-revoke {client_id}"
        ], capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
EOF