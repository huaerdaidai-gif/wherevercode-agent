# Hand 服务端协议（阶段 B，V5 远程设备 Agent 通信）

本文档是 App 侧 `core/mcp/HandClient` 与 VPS 侧 `server/hand_server.py`
之间的线协议。实现见 `hand_server.py`；测试见 `test_hand_server.py`。
协议思路参照老项目 `cross_device_mcp.py` / `cross_device_crypto.py` /
`cross_device_credentials.py`，源码全部自主写，不复用老代码。

## 传输与成帧

- TCP（Tailnet 内明文 TCP；认证/加密边界是密封信封本身，不另做 TLS）。
- 每帧 = 4 字节大端长度 + 负载；单帧上限 4MB。
- 一次调用一个新连接；一次 run 调用在连接上流式回 progress/result，
  终态后服务端关闭连接。

## 握手（明文 JSON，一帧来回，不执行任何操作）

App → VPS：

```json
{"type": "wherevercode_hello", "version": 1,
 "device_id": "<app 设备 id>", "public_key_b64": "<X25519 公钥 base64>"}
```

VPS → App（同 type/version，另附设备名、公钥、agent 列表）：

```json
{"type": "wherevercode_hello", "version": 1,
 "device_id": "<vps 设备 id>", "display_name": "<显示名>",
 "public_key_b64": "<X25519 公钥 base64>",
 "agents": [{"agent_id": "example-agent", "display_name": "Example Agent",
   "kind": "agent", "capabilities": ["dev"],
   "folder": "/workspace/example-project", "available": true,
   "repos": [{"path": "/workspace/example-project", "branch": "main",
     "commit": "a1b2c3d4e5f6"}]}]}
```

- `available` = 服务端检查过该 agent 的入口命令存在。
- `repos`（P1 能力卡，2026-11-11）= 该 agent 工作文件夹的 git 检出
  信息（`[{path, branch, commit}]`，commit 为 12 位短哈希，只读
  `git rev-parse` 得来）；不是 git 仓库时为空数组；旧版服务端不发
  此字段，App 按未知处理，绝不编造分支/commit。
- hello 只交换公钥与 agent 列表；agent 列表不是秘密（执行仍需配对 +
  凭证），但绝不能仅凭 hello 就执行任何操作。

## 密钥推导（双方各自算出同一份密钥，不交换秘密）

- 会话密钥：X25519 + HKDF-SHA256，salt = SHA-256(两公钥排序后拼接)，
  info = `"wherevercode-cross-device/v1"`，输出 32 字节。
  互操作锚点：RFC 7748 §6.1 向量（Alice 私钥
  `77076d0a…92c2a`、Bob 私钥 `5dab087e…88e0eb`、共享密钥
  `4a5d9d5b…61742`）；Kotlin 侧 `RemoteCryptoInteropTest` 与 Python 侧
  `test_hand_server.py::test_rfc7748_vector` 双向锁定。
- 凭证签名密钥：HKDF-SHA256(sessionKey,
  salt = SHA-256(`"wherevercode-credential-signing/v1"`),
  info = `"wherevercode-credential-signing/v1"`, 32)。
- 密封：AES-256-GCM，输出 = 12 字节随机 nonce + 密文 + 16 字节 tag。

## 任务凭证（v2，与 App 侧 TaskCredential 同款）

- 线格式：Base64url(JSON)，其中 `v=2`，字段 `user_id / task_id /
  attempt / source_device_id / target_device_id / agent_id / folder /
  iat / exp / jti / scope{agent_id, folder} / epoch / goal_version /
  sig_b64`；`sig_b64` 是标准 Base64（去填充）的 HMAC-SHA256。
- 签名：HMAC-SHA256，密钥 = SHA-256(signingKey)，签名字节 =
  以下字段以 `|` 连接的 UTF-8：
  `user_id|task_id|attempt|source_device_id|target_device_id|agent_id|
  folder|iat|exp|jti|scope_agent_id|scope_folder|epoch|goal_version`。
- App 侧 HandClient 目前签发的是 legacy v2 凭证（user_id / attempt /
  source_device_id / agent_id 为空串）；服务端按 legacy 绑定校验
  （签名 → 过期 → 撤销 → 任务/目标/文件夹），agent 归属由信封
  agentId + auth.json 授权表约束，epoch / goal_version 由下述
  control 快照约束。legacy 校验是完整 v2 校验的子集，未来客户端
  改签完整凭证时同样通过。

## 调用信封（密封帧内 JSON）

```json
{"type": "cross_device_tool_call", "version": 1, "seq": 1,
 "task_id": "…", "agent_id": "example-agent",
 "source_device_id": "<app 设备 id>", "target_device_id": "<vps 设备 id>",
 "folder": "/workspace/example-project", "action": "list|read|write|run",
 "args": {"credential": "<v2 凭证>", "argv": "[\"agent\",\"example-agent\",\"在吗\"]",
          "timeout": "600",
          "control": "{\"goal_version\": 1, \"epoch\": 1}"}}
```

- `args` 全是字符串；`argv` 是 JSON 数组字符串；`timeout` 是秒数字符串。
- `control` 是执行栅栏快照（JSON 字符串）：改目标/撤销后服务端
  goal_version / epoch 已涨，旧快照的调用以 `stale_goal_version` /
  `stale_epoch` 拒绝，旧执行不复活。
- run 的 argv 只接受两种：`["agent", agentId, 文本]`（调注册的 agent
  命令）与 `["__control__", pause|resume|stop|change_goal,
  operationId, 新目标?]`（控制在途进程）。不接受任意 shell 命令。

## 回包（密封帧内 JSON，type 三种，终态后服务端关闭连接）

```json
{"type": "cross_device_progress", "version": 1, "seq": 2,
 "task_id": "…", "agent_id": "example-agent",
 "source_device_id": "<vps 设备 id>", "target_device_id": "<app 设备 id>",
 "folder": "…", "action": "run",
 "args": {"message": "一行输出", "percent": "…"}}
{"type": "cross_device_result", "version": 1, "seq": 9, …,
 "args": {"output": "完整输出（截断至 20000 字符）"}}
{"type": "cross_device_error", "version": 1, "seq": 9, …,
 "args": {"code": "remote_controlled_stop", "message": "已按控制指令停止。"}}
```

- progress：run 的每行 stdout 各一条（App 侧记为任务步骤）。
- result：run 正常结束（退出码 0）或控制回执。控制回执的 output
  是 JSON：`{"control": "pause", "confirmed": true, "message": "…"}`；
  未在运行的控制回执 `confirmed: true`（no-op，不当错误）。
- error：code 与 message 见下表；服务端随后关闭连接。

## 错误码

| code | 含义 |
| --- | --- |
| `cross_device_not_authorized` | 未配对（PENDING）或配对已撤销 |
| `invalid_credential` | 凭证格式/签名不对 |
| `expired_credential` | 凭证已过期（TTL 900 秒） |
| `revoked_credential` | 凭证已被收回（当次运行被 stop/change_goal 撤销） |
| `wrong_task` | 凭证不属于本任务 |
| `wrong_target` | 凭证不属于本设备 |
| `wrong_folder` | 文件夹未授权给该 agent |
| `command_not_allowed` | 不支持的操作 / argv 格式不对 |
| `path_not_allowed` | 路径越界、文件太大或不可读 |
| `stale_goal_version` | 目标已变更，旧快照作废 |
| `stale_epoch` | 授权已变更，旧快照作废 |
| `agent_not_found` | 该设备上没有这个 Agent 或入口不存在 |
| `agent_timeout` | Agent 执行超时（已终止） |
| `remote_controlled_stop` | 运行被控制指令停掉（App 侧落 cancelled） |

## 执行语义（服务端）

- list/read/write：路径一律约束在凭证 folder 内（词法归一化后比较，
  `..` 逃出拒绝）；read/write 单文件上限 2MB。
- run：按 agents.json 的 command 模板启动子进程（`{prompt}` /
  `{task_id}` 占位替换），cwd = folder，stdout/stderr 合并流式回传；
  进程组启动（`start_new_session=True`），暂停 = SIGSTOP 进程组，
  继续 = SIGCONT，停止 = SIGTERM 进程组；change_goal = 停止旧进程 +
  服务端 goal_version +1，并撤销当次凭证（jti），新目标凭新凭证、
  新快照重新派发。
- 客户端断开：杀掉子进程，不再回包。
- 审计：`audit.jsonl` 只记元数据（task/agent/action/结果），绝不记
  凭证、密钥与命令正文密钥。
- 状态文件（`--state-dir`，默认 `~/.wherevercode/hand-server`）：
  `server_key.json`（0600）、`paired_clients.json`、
  `auth.json`、`agents.json`、`tasks.json`、`audit.jsonl`。

## 配对流程

1. App 发 hello → 服务端把该设备记为 PENDING（只存公钥）。
2. maintainer 在 VPS 上跑 `hand_server.py pair-approve <device-id>`。
3. App 用户在 App 内核对服务端指纹（`hand_server.py fingerprint`
   输出的 SHA-256）无误后确认配对。
4. 之后该设备的密封调用才会被受理；未配对设备的密封调用直接断开，
   不回包、不泄露原因细节。
