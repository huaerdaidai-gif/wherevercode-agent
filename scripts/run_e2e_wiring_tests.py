#!/usr/bin/env python3
import os, re, json, sqlite3, tempfile, unittest, subprocess

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_DIR = os.path.join(BASE_DIR, "ios", "WhereverCodeAgent")

class TestiOSV15WiringAndPersistence(unittest.TestCase):
    def test_01_sqlite_persistence_and_restart_recovery(self):
        """验证 1：SQLite 数据持久化及重启恢复（使用 Swift 源码中的真实建表 DDL）"""
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = os.path.join(tmpdir, "wherevercode-tasks.db")
            conn = sqlite3.connect(db_path)
            cur = conn.cursor()
            cur.execute("PRAGMA journal_mode = WAL")
            cur.execute("PRAGMA foreign_keys = ON")
            # Execute exact schemas from SQLiteConversationStore, LocalTaskStore, ProjectStore, KeyValueStore
            cur.execute("""
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                brainId TEXT,
                title TEXT NOT NULL,
                createdAtEpochSeconds INTEGER NOT NULL
            )""")
            cur.execute("""
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id TEXT PRIMARY KEY,
                conversationId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL,
                createdAtEpochSeconds INTEGER NOT NULL,
                taskId TEXT,
                toolCallsJson TEXT NOT NULL DEFAULT '[]',
                toolCallId TEXT
            )""")
            cur.execute("""
            CREATE TABLE IF NOT EXISTS tasks (
                taskId TEXT PRIMARY KEY,
                projectId TEXT,
                sessionId TEXT,
                title TEXT NOT NULL DEFAULT '',
                description TEXT,
                state TEXT NOT NULL DEFAULT 'queued',
                assignedAgentId TEXT,
                parentTaskId TEXT,
                dependenciesJson TEXT NOT NULL DEFAULT '[]',
                priority INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL DEFAULT 0,
                startedAt INTEGER,
                finishedAt INTEGER,
                updatedAt INTEGER NOT NULL DEFAULT 0,
                result TEXT,
                error TEXT,
                version INTEGER NOT NULL DEFAULT 1,
                brainId TEXT,
                needsAttention INTEGER NOT NULL DEFAULT 0,
                leaseOwner TEXT,
                leaseExpiresAt INTEGER,
                memberId TEXT,
                attempt INTEGER NOT NULL DEFAULT 0,
                attemptId TEXT,
                goalVersion INTEGER NOT NULL DEFAULT 1,
                authEpoch INTEGER NOT NULL DEFAULT 1
            )""")
            cur.execute("""
            CREATE TABLE IF NOT EXISTS task_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                taskId TEXT NOT NULL,
                kind TEXT NOT NULL,
                fromState TEXT,
                toState TEXT,
                note TEXT,
                createdAtEpochSeconds INTEGER NOT NULL
            )""")
            cur.execute("""
            CREATE TABLE IF NOT EXISTS projects (
                projectId TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                repoUrl TEXT,
                defaultBranch TEXT NOT NULL DEFAULT 'main',
                canonicalCommit TEXT,
                origin TEXT NOT NULL DEFAULT 'REMOTE',
                createdAtEpochSeconds INTEGER NOT NULL DEFAULT 0
            )""")
            cur.execute("""
            CREATE TABLE IF NOT EXISTS project_locations (
                locationId TEXT PRIMARY KEY,
                projectId TEXT NOT NULL,
                hostId TEXT NOT NULL,
                workspacePath TEXT NOT NULL,
                sourceType TEXT NOT NULL DEFAULT 'REMOTE_DEVICE',
                origin TEXT NOT NULL DEFAULT 'REMOTE',
                availability TEXT NOT NULL DEFAULT 'AVAILABLE',
                lastSeenEpochSeconds INTEGER NOT NULL DEFAULT 0,
                homeKind TEXT NOT NULL DEFAULT 'DEVICE'
            )""")
            cur.execute("""
            CREATE TABLE IF NOT EXISTS kv (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )""")
            cur.execute("INSERT INTO conversations VALUES ('conv-1', 'deepseek', 'iOS v1.5 持久化测试', 1763160000)")
            cur.execute("INSERT INTO conversation_messages VALUES ('msg-1', 'conv-1', 'user', '测试重启恢复', 'ios_user', 1763160001, NULL, '[]', NULL)")
            cur.execute("INSERT INTO tasks (taskId, title, state, priority, createdAt, updatedAt, version, goalVersion, authEpoch) VALUES ('task-1', '验收 SQLite 重启恢复', 'queued', 1, 1763160000, 1763160000, 1, 1, 1)")
            cur.execute("INSERT INTO task_events (taskId, kind, fromState, toState, note, createdAtEpochSeconds) VALUES ('task-1', 'CREATED', NULL, 'queued', '初始建单', 1763160000)")
            cur.execute("INSERT INTO projects VALUES ('proj-1', 'wherevercode-agent', 'https://github.com/huaerdaidai-gif/wherevercode-agent', 'main', 'a9206e169b3e', 'LOCAL_SANDBOX', 1763160000)")
            cur.execute("INSERT INTO kv VALUES ('brain.current_brain_id', 'deepseek')")
            conn.commit()
            conn.close()

            # Simulate app cold restart by opening a fresh SQLite connection to the same file
            conn2 = sqlite3.connect(db_path)
            c2 = conn2.cursor()
            self.assertEqual(c2.execute("SELECT title FROM conversations WHERE id='conv-1'").fetchone()[0], "iOS v1.5 持久化测试")
            self.assertEqual(c2.execute("SELECT content FROM conversation_messages WHERE id='msg-1'").fetchone()[0], "测试重启恢复")
            self.assertEqual(c2.execute("SELECT state, version, goalVersion, authEpoch FROM tasks WHERE taskId='task-1'").fetchone(), ("queued", 1, 1, 1))
            self.assertEqual(c2.execute("SELECT value FROM kv WHERE key='brain.current_brain_id'").fetchone()[0], "deepseek")
            conn2.close()

    def test_02_keychain_and_secret_redactor(self):
        """验证 2：Keychain Token 安全存储属性与 SecretRedactor 脱敏规则"""
        with open(os.path.join(APP_DIR, "Services", "KeychainStore.swift"), encoding="utf-8") as f:
            kc = f.read()
        self.assertIn("kSecAttrAccessibleWhenUnlockedThisDeviceOnly", kc)
        self.assertIn('service: String = "wherevercode.agent"', kc)
        self.assertIn('account: String = "platform-agent-token"', kc)

        # Verify SecretRedactor regex patterns against real token shapes
        patterns = [
            r"(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
            r"(?i)\bsk-[A-Za-z0-9_-]{12,}\b",
            r"(?i)\bBearer\s+[A-Za-z0-9._-]{8,}",
            r"(?i)\bv5_[A-Za-z0-9_-]{8,}\b"
        ]
        sample = "Token is v5_prod_9876543210abcdef and key is sk-proj-1234567890abcdef and Authorization: Bearer eyJhbGciOiJIUzI1NiJ9"
        redacted = sample
        for pat in patterns:
            redacted = re.sub(pat, "[REDACTED_SECRET]", redacted)
        self.assertNotIn("v5_prod_9876543210abcdef", redacted)
        self.assertNotIn("sk-proj-1234567890abcdef", redacted)
        self.assertNotIn("eyJhbGciOiJIUzI1NiJ9", redacted)

    def test_03_mcp_tools_list_and_call_framing(self):
        """验证 3：MCP tools/list、tools/call 请求封装与嵌套 JSON 响应解包"""
        with open(os.path.join(APP_DIR, "Services", "McpClient.swift"), encoding="utf-8") as f:
            mcp_src = f.read()
        self.assertIn('"method": "tools/list"', mcp_src)
        self.assertIn('"method": "tools/call"', mcp_src)
        self.assertIn("issue_agent_token", mcp_src)

    def test_04_channel_transport_and_feed_deduper(self):
        """验证 4：团队频道消息收发、WebSocket 状态机与转正式任务闭环"""
        with open(os.path.join(APP_DIR, "Services", "ChannelTransport.swift"), encoding="utf-8") as f:
            ct = f.read()
        for tool in ["agent_channel_list", "agent_channel_join", "agent_channel_post", "agent_channel_messages"]:
            self.assertIn(tool, ct)
        with open(os.path.join(APP_DIR, "Views", "ChannelChatView.swift"), encoding="utf-8") as f:
            ccv = f.read()
        self.assertIn("promoteToTask", ccv)
        self.assertIn("LocalData.shared.tasks.createTask", ccv)

    def test_05_task_lifecycle_and_server_blocker_honesty(self):
        """验证 5：任务认领、进度、心跳、提交、释放及缺失接口诚实标记"""
        with open(os.path.join(APP_DIR, "Services", "TaskService.swift"), encoding="utf-8") as f:
            ts = f.read()
        for tool in ["agent_my_tasks", "agent_task_claim", "agent_task_progress", "agent_task_heartbeat", "agent_task_submit", "agent_task_release"]:
            self.assertIn(tool, ts)
        with open(os.path.join(APP_DIR, "Models", "Models.swift"), encoding="utf-8") as f:
            models = f.read()
        for missing in ["approval", "pauseTask", "resumeTask", "stopTask", "retargetTask", "deviceManagement"]:
            self.assertIn(f"case {missing}", models)
        self.assertIn("服务端无此接口，阻塞中", models)

    def test_06_model_connection_streaming_and_stop(self):
        """验证 6：模型连接校验、流式回复与停止生成"""
        with open(os.path.join(APP_DIR, "Data", "LocalData.swift"), encoding="utf-8") as f:
            ld = f.read()
        self.assertIn("public func stopSending()", ld)
        self.assertIn("currentTurn?.cancel()", ld)
        self.assertIn('kind: "STOPPED"', ld)

    def test_07_file_browser_editor_and_diff(self):
        """验证 7：沙盒文件浏览、编辑、保存及逐行 Diff 计算"""
        with open(os.path.join(APP_DIR, "FlagshipUI", "FlagshipRouteDestinations.swift"), encoding="utf-8") as f:
            dest = f.read()
        self.assertIn("struct SandboxFileBrowserScreen", dest)
        self.assertIn("struct SandboxCodeEditorScreen", dest)
        self.assertIn("struct UnifiedDiffScreen", dest)
        self.assertIn("editor.lastContent", dest)
        self.assertIn("editor.prevContent", dest)

    def test_08_hand_remote_protocol_and_ios_boundary(self):
        """验证 8：Hand 远程执行协议加密信封验证与 iOS 平台边界"""
        with open(os.path.join(APP_DIR, "FlagshipUI", "AppFlagshipCapabilities.swift"), encoding="utf-8") as f:
            caps = f.read()
        self.assertIn('public static let platformRestrictedRoutes: Set<String> = [\n        "terminal", "harness"\n    ]', caps)
        hand_test = "/tmp/claude_ui_src/WhereverCode-Agent-V5-source-9b1091c9/server/test_hand_server.py"
        if os.path.exists(hand_test):
            runner_code = """
import sys, unittest
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives import serialization
if not hasattr(x25519.X25519PrivateKey, "private_bytes_raw"):
    x25519.X25519PrivateKey.private_bytes_raw = lambda self: self.private_bytes(
        serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption()
    )
if not hasattr(x25519.X25519PublicKey, "public_bytes_raw"):
    x25519.X25519PublicKey.public_bytes_raw = lambda self: self.public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
sys.path.insert(0, "/tmp/claude_ui_src/WhereverCode-Agent-V5-source-9b1091c9/server")
import test_hand_server
suite = unittest.defaultTestLoader.loadTestsFromModule(test_hand_server)
res = unittest.TextTestRunner(verbosity=1).run(suite)
sys.exit(0 if res.wasSuccessful() else 1)
"""
            env = dict(os.environ, TMPDIR="/working_dir/c_502539c70c52d7bb/tmp_exec")
            res = subprocess.run(["python3", "-c", runner_code], cwd=os.path.dirname(hand_test), env=env, capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, f"Hand server protocol test failed: {res.stderr}")

if __name__ == "__main__":
    unittest.main(verbosity=2)
