# 独立 MCP 派工模块（feat/mcp-dispatch-20261113）

状态：服务端核心、HTTP 传输、JVM 测试、本机 HTTP 端到端（Kotlin 客户端 + curl 脚本）已完成。
**未验证**：真实 ChatGPT 云端连入、真机（K70）运行、公网隧道/中继。见文末“未验证 / 剩余缺口”。

## 1. 目标与边界

- 新项目自己的 MCP 服务端，**不依赖老 V5 平台服务**（`154.9.26.46` 那套）。
- 派工方（owner，或被授予 `dispatch` 能力的 Agent，如 ChatGPT）创建 / 指派 / 查询 / 暂停 / 恢复 / 停止 / 改目标；
  执行方（agent / device）领单 / 报进度 / 交结果。
- **复用 App 现有任务系统，不另建任务库**：
  - 任务行：`TaskStore`（生产是 `RoomTaskStore`，`tasks` 表，与聊天任务、派活同一张表、同一状态机 `TaskStates`）。
  - 认领：`TaskScheduler.claimTaskForMember` / `startTask`（每成员一次一单、冻结与非 RUNNING 不可认领、attempt+1、600 秒租约）。
  - 控制：`ControlPlane.pause/resume/stop/retarget/confirmRemotePaused/confirmRemoteStopped`（operationId 去重、待确认语义、goalVersion 计数器、控制审计 `control_audit`）。
  - 撤销：`RevocationStore.isTaskRevoked`。
  - 审计：`core/security/AuditLog`（`FileAuditLog`，追加式 JSONL）。
  - 脱敏：`SecretRedactor`（目标、进度、结果写入前脱敏）。
  - 限流 / 体积：`McpGuard.RateLimiter`（每主体每分钟 60 次）、`McpGuard.MAX_BODY_BYTES`。
- 没有修改 `TaskStore` / `TaskContract` / `ControlPlane` / `TaskScheduler` 的任何接口或实现；没有碰 Codex
  （Brain/Hand/LocalTaskRunner/PipelineRunner）与 Muse（ChannelAgentRelay/TeamChannel/TeamChannelController/PlatformMcpClient）的文件。

### 基线选择

基于 `origin/main` `f6d24d6`。远端集成分支（`integration/solo-muse-grok-20261113` dc7253e、`fix/agent-mcp-wiring-20261113`、
`fix/v5-channel-mcp-*`）只读看过：它们做的是 App 作为 **MCP 客户端**（PlatformMcpClient、通用 stdio Hand MCP 桥、Hand 任务围栏、
频道能力）以及给 `tasks` 表加 `handDeviceId/handAuthorizationId/handFolder`（DB v11）。本模块是 **MCP 服务端**，不需要那些改动；
为避免和 v11 迁移冲突，本模块**不改 `tasks` 表**。合并时只有新增文件，无文本冲突。

## 2. 代码结构

`android/app/src/main/java/wherevercode/agent/core/dispatch/`

| 文件 | 作用 |
|---|---|
| `DispatchModels.kt` | 角色 `OWNER/AGENT/DEVICE`、能力 `DISPATCH/EXECUTE`、`DispatchPrincipal`、错误码 |
| `DispatchTokenRegistry.kt` | `wcd_` 令牌签发/校验/吊销；只存 SHA-256；`MessageDigest.isEqual` 常量时间、全表扫描不提前退出 |
| `DispatchStateStore.kt` | 内存版 / 文件版（原子改名）：令牌哈希、任务归属（谁经 MCP 建的）、幂等记录（24h TTL）、最近 20 条进度备注 |
| `DispatchTools.kt` | 11 个工具定义与 JSON Schema |
| `McpDispatchService.kt` | 工具逻辑、权限、幂等、围栏、审计（纯 JVM，可单测） |
| `McpDispatchProtocol.kt` | MCP JSON-RPC：`initialize` / `ping` / `tools/list` / `tools/call`，通知无响应 |
| `McpDispatchHttpServer.kt` | Streamable HTTP 最小实现（`java.net.ServerSocket`，Android/JVM 通用） |
| `McpDispatchHost.kt` | App 内托管：用 App 的 `TaskDatabase` 组装（Room 任务库 + 控制库 + 调度器） |

测试：`android/app/src/test/java/wherevercode/agent/core/dispatch/`（30 个用例），脚本：`scripts/mcp-dispatch-e2e.sh`。

## 3. 工具

派工方（需要 `dispatch`）：

| 工具 | 必填 | 可选 | 说明 |
|---|---|---|---|
| `create_task` | `title` `goal` `idempotency_key` | `project_id` `priority` `assignee` | 建 `queued` 任务，id `mcp-…`；**必须**带幂等键 |
| `assign_task` | `task_id` `agent_id` | `expected_version` `idempotency_key` | 只能指派给已登记、有 execute 能力的执行方；执行中任务不能改派（`task_in_flight`） |
| `get_task_status` | `task_id` | | 任务快照 + 控制状态 + 最近进度；执行方也可查指派给自己的任务 |
| `pause_task` / `resume_task` / `stop_task` | `task_id` | `reason` `idempotency_key` | 经 `ControlPlane`；执行中的任务先进入 `PAUSING` / `STOPPING`（`confirmed:false`），等执行方 ack |
| `change_goal` | `task_id` `goal` | `expected_version` `reason` `idempotency_key` | `ControlPlane.retarget`，`goal_version+1` |

执行方（需要 `execute`）：

| 工具 | 必填 | 可选 | 说明 |
|---|---|---|---|
| `list_my_tasks` | | | 指派给我、未结束的任务 |
| `claim_task` | `task_id` | `idempotency_key` | 只能认领**明确指派给我**的任务；返回 `attempt_id`、`goal`、`goal_version`。已由我持有的任务再调一次返回当前 attempt（`resumed:true`，断线恢复） |
| `report_progress` | `task_id` `attempt_id` | `message` `percent` `goal_version` `ack` `idempotency_key` | 续租 600 秒并记进度（TaskStore 事件 `mcp_progress`）；`ack=paused/stopped` 确认已按指令停下 |
| `submit_result` | `task_id` `attempt_id` `goal_version` `result` | `status`(completed/failed) `expected_version` `idempotency_key` | `working → review → completed`（或 `→ failed`） |

工具成功：`result.structuredContent` 为结果对象，`isError:false`；工具失败：`isError:true`，
`structuredContent = {ok:false, error_code, message, data?}`。错误码：`forbidden`、`task_not_found`、`invalid_argument`、
`version_conflict`、`idempotency_conflict`、`task_stopped`、`task_paused`、`task_terminal`、`task_revoked`、`task_in_flight`、
`stale_attempt`、`goal_changed`、`claim_rejected`、`unknown_agent`、`invalid_transition`、`control_*`（ControlPlane 原样透传）、`internal_error`。
未知工具 → JSON-RPC 错误 `-32602`。

暂停 / 停止时执行方收到的 `data.directive` 为 `pause` / `stop`，执行方停下后用
`report_progress {ack:"paused"|"stopped"}` 确认；停止确认后任务落 `cancelled`。

## 4. 鉴权与权限模型

- 传输：每个请求 `Authorization: Bearer wcd_<43 位 base64url>`。缺失/错误 → HTTP 401 + `WWW-Authenticate: Bearer`，记审计（主体 `anonymous`，不记任何令牌片段）。
- 存储：只存 SHA-256 哈希（与平台 `v5_` 令牌同口径）；明文只在 `DispatchTokenRegistry.issue()` 返回时出现一次。哈希文件放 App 私有目录。
- 角色：`OWNER` 全部能力；`AGENT` 按令牌授予 `dispatch` 和/或 `execute`；`DEVICE` 只能 `execute`（签发时强制）。`tools/list` 按能力过滤。
- 任务级范围：
  - 派工 Agent 只能查/控/改派**自己经 MCP 建的**任务（归属记在 `DispatchStateStore`）；App 本地任务（聊天任务等）只有 owner 可见。
  - 执行方只能认领/汇报/交付**指派给自己的**任务；看不到别人任务的 `attempt_id`。
- 围栏（执行方每次写入前）：撤销（`task_revoked`）→ `attempt_id` 必须是当前认领（`stale_attempt`，租约过期重派后旧 attempt 一律拒）→
  终态（`cancelled` 报 `task_stopped`，其他 `task_terminal`）→ 停止待确认/`STOPPING`（`task_stopped`）→ `PAUSING/PAUSED`（`task_paused`）→
  `goal_version` 过期（`goal_changed`，交付时必须带）。
- 幂等：作用域 = 主体 + 工具 + `idempotency_key`；参数指纹（键排序 JSON 的 SHA-256）不同 → `idempotency_conflict`；只记成功结果，
  失败可同键重试；重放结果带 `replayed:true`。控制命令的 operationId = `mcp:<主体>:<动作>:<键>`，`ControlPlane` 再去重一次。
- 乐观并发：`expected_version` 与 TaskStore `version` 不符 → `version_conflict`（带 `current_version`），不写入；TaskStore 自身的
  `StaleTaskUpdate` / `ControlVersionStale` 也映射为 `version_conflict`。
- 审计：每次调用一行（时间、`role:id`、`mcp_dispatch.<tool>`、任务 id、成败、错误码），**参数与正文不进审计**；控制命令另有 `ControlPlane` 的 `control_audit`。
- HTTP 层：带 `Origin` 且不在白名单 → 403（防 DNS rebinding）；`GET`/`DELETE` → 405；JSON-RPC 批量请求 → `-32600`；
  超过 64 KiB → 413；chunked → 411；每主体每分钟 60 次 → 429；socket 读超时 10 秒丢弃慢连接；默认只绑 `127.0.0.1`。

## 5. 频道消息不会变成正式任务

- 派工模块不依赖频道代码（`core.team`），没有任何频道消息 → `create_task` 的路径（测试扫源码断言）。
- 频道工具（如 `agent_channel_post`）不在本 MCP 上：送进来只得到“未知工具”，任务数不变。
- 无 id 的通知（频道式推送）不产生任何动作。
- 只有具备 `dispatch` 能力的调用方显式调用 `create_task` 才建任务；只有 execute 能力的成员照着频道消息调 `create_task` 返回 `forbidden`。
- 测试：`McpDispatchServiceTest.channel messages never become formal tasks`。

## 6. 访问路径（NAT / 公网）

手机在运营商 NAT 后面，**ChatGPT 云端不能 P2P 直连手机**；本模块不宣称纯 P2P 可用。可行路径：

1. **本机回环（已实现、已测）**：`McpDispatchHost.create(...).start()` 只绑 `127.0.0.1`。同机进程（Termux、本机 Agent CLI）可直接用。
2. **出站反向隧道（推荐，未实现接线）**：手机主动向外建立出站连接，由外部 HTTPS 入口转发到手机的 `127.0.0.1:<port>`：
   - Cloudflare Tunnel / Tailscale Funnel / ngrok 一类：在手机（Termux）或同局域网常开的 Mac 上跑隧道客户端，公网得到 `https://…/mcp`；
   - 或 SSH 反向转发（App 已带 JSch，`Session.setPortForwardingR`）到一台自有 HTTPS 反代主机。
   - 入口须是 HTTPS；令牌仍在 `Authorization` 头里逐请求校验，隧道本身不等于授权。
3. **Mac/服务器托管（未实现）**：在常开的 Mac/服务器上用同一套代码（纯 JVM）托管，但那样任务库就不在手机上了，需要另外设计与手机
   TaskStore 的同步，本分支不做。
4. **复用平台转发**：老 V5 平台可以做中继，但这违背“独立于老平台”的目标，本分支不做。

`CloudAgentMcpEndpoint.publicUrl()` 仍返回 `null`：在真实的隧道地址出现之前，不能拿回环地址或平台地址冒充。

## 7. 如何运行

```bash
cd android
# 只跑派工模块（30 个用例，含真实 HTTP 端到端与 curl 脚本）
MCP_DISPATCH_E2E_LOG_DIR=/tmp/mcp-e2e gradle --no-daemon testDebugUnitTest --tests 'wherevercode.agent.core.dispatch.*'
ls /tmp/mcp-e2e/mcp-dispatch-e2e-*.log   # 证据：每个请求/响应，令牌已打码
```

对一个已在运行的服务端单独跑 curl 脚本：

```bash
MCP_URL=http://127.0.0.1:<port>/mcp DISPATCHER_TOKEN=… EXECUTOR_TOKEN=… EXECUTOR_ID=grok-bot \
  bash scripts/mcp-dispatch-e2e.sh      # 令牌只从环境读、写进 0600 临时头文件，不进命令行、不打印
```

App 内托管（后台线程）：

```kotlin
val host = McpDispatchHost.create(appContext, taskDb, File(appContext.filesDir, "dispatch"), port = 8765).start()
val owner = host.service.tokens.issue("owner", DispatchRole.OWNER, emptySet())          // 明文只显示一次
val chatgpt = host.service.tokens.issue("chatgpt", DispatchRole.AGENT, setOf(DispatchCapability.DISPATCH))
val grok = host.service.tokens.issue("grok-bot", DispatchRole.AGENT, setOf(DispatchCapability.EXECUTE))
```

## 8. 测试覆盖

| 测试类 | 内容 |
|---|---|
| `McpDispatchServiceTest`（19） | 正常流程 create→assign→list_my_tasks→claim→report_progress→submit_result→get_task_status；越权（角色、他人任务、App 本地任务、未指派任务、未登记执行方）；DEVICE 不能拿 dispatch；吊销；幂等（重复创建、键冲突、跨主体、claim/progress 去重）；expected_version；暂停/恢复/改目标与 ack；停止围栏（停止前后、ack 后、重复 ack）；排队任务直接停止；撤销围栏；租约过期重派后旧 attempt 拒写；脱敏；频道消息不建任务 |
| `McpDispatchRestartTest`（2） | 文件版 Room 库 + 状态文件：重启后令牌、归属、幂等、进度、审计都在；停止围栏、暂停状态重启后仍生效；重启后 claim 拿回同一 attempt |
| `McpDispatchHttpE2ETest`（5） | 真实 HTTP JSON-RPC 全流程；401/403/405/400/413/429 与批量拒绝；客户端请求体中途断开不落库且服务继续；客户端超时后同键重试只建 1 个任务、1 个 attempt；慢客户端被读超时丢弃不影响他人 |
| `McpDispatchCurlE2ETest`（1） | 外部进程 `bash scripts/mcp-dispatch-e2e.sh`（curl）走全流程，结尾 `E2E_OK` |
| `DispatchTokenRegistryTest`（3，纯 JVM） | 只存哈希、明文不落盘、重载、吊销、畸形令牌、令牌唯一性 |

## 9. 交叉问题（记录，不在本分支改）

给 Codex（调度/执行主链）：

1. **锁顺序死锁隐患**：`ControlPlane.execute` 先开 `ControlStore.runTransaction`（Room 事务、占连接）再调 `RoomTaskStore.getTask/updateTask`（取 `synchronized(lock)`）；
   而 `RoomTaskStore` 自己是先取锁再开事务。两个线程交错（一个走控制命令、一个走普通任务读写）可能互相等待。本模块用一把服务锁把自己的调用串行化，
   但 App 其他线程与本模块之间仍可能交错。建议统一取锁顺序（例如 `RoomControlStore.runTransaction` 先取 TaskStore 的锁）。
2. **全局冻结**：`TaskScheduler.canClaimForMember` 对“任何任务被冻结”做全局扫描——一个任务处于暂停（`dispatchFrozen=true` 一直保持到 resume）
   或停止待确认，**所有成员**都领不到新任务。对多 Agent 并行派工过严，建议改为只看本任务（或本成员）冻结。本模块按现状遵守，并在 `claim_rejected` 文案里说明。
3. **租约过期清空 `assignedAgentId`**：`RoomTaskStore.expireLeases` 回队时把指派清空，经 MCP 派的任务过期后需要派工方重新 `assign_task`。如果希望“回队但保留指派”，需要在调度层决定。
4. `ControlPlane` 的 `actor` 是构造期常量；`McpDispatchHost` 默认新建一个 `actor="mcp-dispatch"` 的 ControlPlane。若改为共享 `BrainBootstrap` 里的 ControlPlane，
   控制审计里的 actor 会显示 `user`——建议给控制命令加按调用传 actor 的重载（附加式接口改动）。

给 Muse（频道 / MCP）：

5. 本模块与 `PlatformMcpClient`（App 作为客户端连平台）互不依赖；ChatGPT 若同时连平台 MCP 和本 MCP，两边的 `list_my_tasks/claim_task/...` 同名不同库，
   需要在连接器命名/说明上区分（本服务 `serverInfo.name = wherevercode-dispatch`）。
6. 频道“建议-only”由结构保证（本模块不依赖 `core.team`）。若以后要“频道里一键转任务”，必须走 owner 确认后显式 `create_task`，不能在 relay 里直接建。

其他：

7. 安全分支 `security/permissions-recovery-tests-20261113` 的 `TerminalWriteGuard` 不在 main 上；本模块没有依赖它，用 ControlPlane 状态 + attempt_id + 撤销表自行实现了停止围栏。两者合并后可以考虑统一。

## 10. 未验证 / 剩余缺口

- **未验证**：真实 ChatGPT 云端连接（需要公网 HTTPS 入口）、真机运行（K70）、隧道/中继。所有 E2E 都是本机 `127.0.0.1` 上的 JVM（Robolectric 提供 Context）。
- **未接线**：`McpDispatchHost` 没有接进 `BrainBootstrap` / 设置页（那是共享热点文件）；需要一个设置开关（启停服务、签发/吊销令牌并一次性显示、显示真实入口地址）。
- **只支持单请求 JSON 响应**：不开 SSE（`GET` 405），不做服务端推送；暂停/停止指令靠执行方的 `report_progress` 轮询拿到。对 ChatGPT 这类工具调用型客户端足够。
- 没有 OAuth（MCP 授权规范）——只有 Bearer 令牌；若 ChatGPT 连接器要求 OAuth，需要另加授权端点。
- 没有 `release_task`（执行方主动放弃）、没有任务列表查询给派工方（只能按 id 查），按需再加。
- 进度备注只保留最近 20 条（完整进度事件在 TaskStore `task_events` 里，`RoomTaskStore.recordTaskEvent`）。
