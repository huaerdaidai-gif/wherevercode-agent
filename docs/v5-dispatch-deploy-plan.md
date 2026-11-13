# V5 正式派工（ChatGPT → V5 → Agent）根因、部署计划与回滚

日期：2026-11-14（北京时间）。分支：`fix/agent-control-dispatch-closeout-20261113`（PR #3）。
本文只是计划，**没有部署，没有登录或改动任何服务器，没有动生产密钥**。下面标“需要所有者授权”的步骤，都要所有者本人批准后才能做。

## 1. 验收标准（所有者原话）

所有者本人创建任务 → 指派 Grok Bot（`grok-bot`）或 Codex → 对方领取并执行 → 所有者查询最终结果。
频道 @ 消息不算派工；只在本机跑通的 `E2E_OK` 也不算完成。

## 2. 根因

**结论：生产 V5 服务端从来没有实现 `create_task` / `assign_task` / `get_task_status`。这三个是本仓库 App 原生派工服务的工具名，只在手机回环地址上提供。另外，交给连接器的凭证是执行身份，所以连 V5 平台已有的派工工具也看不到。** 问题不在于 PR #3 没部署：PR #3 本身就不包含 V5 服务端代码。

证据：

1. **生产端点实测**（2026-11-14 约 04:45 北京时间，grok-bot 的 V5 连接器）：`tools/list` 一共 13 个工具，全部是执行方工具：
   `agent_channel_join/list/messages/post`、`list_my_tasks`、`claim_task`、`heartbeat`、`report_progress`、`release_task`、`submit_result`、`report_status`、`attach_delivery_diff`、`get_delivery_diff`。
   `list_my_tasks` 返回 `{"tasks": []}`。
2. **这三个工具只在 App 原生 MCP 里存在**：
   - 工具名定义在 `android/app/src/main/java/wherevercode/agent/core/dispatch/DispatchTools.kt:19-21`，并在 `:154` 的 `visibleTo` 里按能力过滤可见性。
   - 实现在 `core/dispatch/McpDispatchService.kt`：`createTask` 在 `:218`，`assignTask` 在 `:249` 起，`getTaskStatus` 在 `:266`，`requireDispatchScope` 在 `:536`，`requireExecutorScope` 在 `:544`，`resolveExecutor` 在 `:548`。
   - 服务的运行方式在 `core/dispatch/McpDispatchRuntime.kt`：`:17` 固定端口 18744，`:138` 只绑定 `127.0.0.1`，`:45` 开关默认关闭。
   - `core/agent/CloudAgentMcpEndpoint.kt:54` 的 `publicUrl()` 固定返回 `null`，也就是没有公网入口。
   - 相关提交：`693a760`（派工核心）、`acf8837`（接入 App）、`a4c42a5`（权限收紧，以及认领和交付审计）。
3. **V5 服务端代码不在本仓库**：
   - 对所有 `origin/*` 分支执行 `git ls-tree`，非 Android 的服务端文件只有 `server/hand_server.py`（Hand）、`server/hand_mcp_stdio.py` 和 `scripts/mcp-dispatch-e2e.sh`。
   - 唯一的工作流 `.github/workflows/android.yml` 只做构建、测试和签名 APK，没有部署作业。
   - V5 的管理命令 `v5adm.py` 在服务器上，见 `core/agent/PlatformTokenIssuer.kt:10-13` 的说明。
4. **V5 平台按 Agent 身份控制工具可见性**：
   - `PlatformTokenIssuer.kt:14-19` 说明：owner 工具对非 owner 调用者一律回“未知工具”；令牌继承目标 Agent 的权限，权限范围记在 Agent 上，不在令牌上。
   - 同一平台家族的另外两个连接器只读对比：
     - `WhereverCode` 有 23 个工具，包含派工工具 `start_task`（带 `agent`/`reviewer` 参数，能把任务派进云端 Agent 的待领队列），以及 `get_task` 和 `list_agents`。
     - `WhereverCode-xai` 有 22 个工具，正好少了 `start_task`。
   - 由此可知，平台已有的派工能力叫 `start_task` / `get_task`，并且按权限隐藏；154.9.26.46 上 ChatGPT 和 grok-bot 用的凭证都看不到它。

## 3. 本分支已交付（可验证）

| 提交 | 内容 |
|---|---|
| `a4c42a5` | App 原生派工的权限链：App 内任务不能经 MCP 改派；执行方只能看到自己的进度；新增 `assignee_active`；新增 `mcp_claimed` 和 `mcp_delivered` 审计事件 |
| `ecd6ac9` | `scripts/dispatch_acceptance.py`：不绑定具体服务端的验收脚本。CI 用 `McpDispatchAcceptanceScriptTest` 把它跑在 App 原生派工服务上；部署后用**同一个脚本**验收 V5 |

验收脚本检查以下内容：

- 匿名请求被拒。
- 派工凭证能发现三个工具；执行凭证看不到 create/assign。
- 执行方建任务和改派都被拒。
- 指派给不存在的 Agent 被拒。
- 非指派方在列表里看不到任务，也领不到。
- 别的执行方不能重复认领。
- 非认领方不能交付。
- 无关执行方查不到状态。
- 完整链路：create（幂等）→ assign → list → claim → progress → submit → `get_task_status=completed`。
- 审计事件：created、assigned、claimed、progress、delivered。

反向用例复现了生产现状：把执行身份当派工凭证时，脚本在发现阶段失败，并且不会创建任何任务。

脚本会按服务端公布的 `inputSchema` 自动适配参数名：App 用 `message/result`，V5 用 `text/summary`。令牌只从环境变量读取，输出时会打码。自签证书通过 `MCP_CA_FILE` 固定，脚本不会关闭 TLS 校验。

## 4. 需要在 V5 服务端做的改动（在 V5 服务端仓库里做，不在本仓库）

V5 服务端源码不在本仓库，也不在任何允许访问的分支上；旧仓库按规定不碰。所以服务端改动要由所有者指定服务端代码所在位置和负责人，交付后用第 3 节的脚本验收。改动内容：

1. **注册三个工具，只对派工身份可见。** 工具定义和语义直接沿用 `docs/mcp-dispatch-20261113.md` 第 3、4 节（App 侧已实现并测试）：
   - `create_task {title, goal, idempotency_key, project_id?, assignee?}`：必须带幂等键；同一个键加不同参数时返回 `idempotency_conflict`。
   - `assign_task {task_id, agent_id, expected_version?}`：只能指派给已登记、有执行能力、属于同一项目的 Agent，否则返回 `unknown_agent`；执行中的任务不能改派，返回 `task_in_flight`；终态任务返回 `task_terminal`。
   - `get_task_status {task_id}`：创建者或派工方能看全部；被指派的执行方只能看自己的任务，只能看到自己的进度；其他人返回 `forbidden` 或 `task_not_found`。
   - `tools/list` 按 Agent 的 scope 过滤：没有 dispatch scope 的身份看不到 create/assign。即使直接调用，也要在服务端再校验一次并返回 `forbidden`。
2. **执行方工具接上同一张任务表**：`list_my_tasks` 只列出 `assignee=调用方` 的任务；`claim_task` 只能由被指派人认领，同一任务只能有一个持有者，重复认领被拒；`report_progress` 和 `submit_result` 必须由当前认领人带当前 attempt 调用。
3. **App 原生任务防劫持**：MCP 只能指派和查看经 MCP 创建的任务（任务 id 前缀，或 origin 字段）。
4. **审计**：每次调用记录时间、调用身份、工具、任务 id、成败和错误码；**不记录参数、正文或 Authorization**。任务事件包括 created、assigned、claimed、progress、delivered。
5. **功能开关**：新增服务端配置，例如 `V5_MCP_DISPATCH_TOOLS_ENABLED`，**默认关闭**。关闭时 `tools/list` 不列出这三个工具，直接调用返回“未知工具”，与现在完全一样。回滚只需要关掉开关。
6. 可选的过渡方案：在 V5 上把 `create_task`/`assign_task`/`get_task_status` 薄封装到平台已有的 `start_task`/`get_task` 上，复用已有的派工队列和审查流程。

## 5. 给所有者或 ChatGPT 签发派工凭证（需要所有者授权）

- V5 的权限范围记在 Agent 上：先给 Agent `chatgpt` 加上 **dispatch scope，只限验收项目**，再签发令牌。签发可以用 App 设置页的“平台令牌”（owner 工具 `issue_agent_token`），也可以在服务器上运行 `v5adm.py issue-token`。
- 给 `grok-bot` 和 `codex` 只保留执行 scope。
- 令牌明文只显示一次，由所有者本人填进 ChatGPT 连接器。**不要贴到频道、PR 或聊天里**。
- 验收结束后，可以撤销测试令牌，也可以保留给正式使用，由所有者决定。

## 6. 部署后验证

1. **只验发现**（不建任务）：所有者在自己的终端设置环境变量，令牌不进命令行历史，然后运行：
   `MCP_URL=https://<V5 主机>/api/open/v1/mcp MCP_CA_FILE=<固定的证书> DISCOVERY_ONLY=1 python3 scripts/dispatch_acceptance.py`。
   预期输出 `ACCEPTANCE_OK`。
2. **完整验收**：再提供 `EXECUTOR_TOKEN`（grok-bot）和 `OTHER_EXECUTOR_TOKEN`（codex），预期输出 `ACCEPTANCE_OK`。如果 V5 交付后自动进入审查，可以设置 `ACCEPT_FINAL_STATES=completed,review`。
3. **ChatGPT 重新发现工具**：在 ChatGPT 里断开并重连 V5 连接器（或者开新对话），让它重新发送 `initialize` 和 `tools/list`。应该能看到这三个工具。
4. **所有者亲测**：所有者在 ChatGPT 里创建任务并指派给 `grok-bot`。Grok Bot 依次调用 `list_my_tasks`、`claim_task`、`report_progress` 和 `submit_result`（只读任务：复核 PR #3 HEAD 与 CI），最后所有者在 ChatGPT 里用 `get_task_status` 看到 `completed`。

## 7. 回滚

1. 关闭服务端开关 `V5_MCP_DISPATCH_TOOLS_ENABLED=false` 并重载服务。工具列表会恢复成现在的 13 个。
2. 撤销验收用的派工令牌，并把 `chatgpt` 的 dispatch scope 收回。
3. 已创建的验收任务停止或取消，不要删除；数据库和审计保留用于复盘。
4. 如果服务端版本本身有问题，按 `docs/v5-release-runbook.md` 回滚到上一版（部署前先备份数据库）。
5. Android App 不需要回滚：本次改动只新增脚本和测试，App 原生派工开关仍然默认关闭。

## 8. 需要所有者本人明确授权的事项

1. 指定 V5 服务端代码的位置和负责人，并批准在那里实现第 4 节的改动。
2. 批准把该版本部署到 154.9.26.46，并在验收时打开 `V5_MCP_DISPATCH_TOOLS_ENABLED`。
3. 批准给 `chatgpt` 加 dispatch scope（只限验收项目）并签发令牌，由所有者本人填进 ChatGPT 连接器。
4. 可选：如果想先走不改 V5 的路线，需要所有者本人在手机上打开 App 的“MCP 派工”开关，并批准一条公网 HTTPS 隧道。手机 App 没有公网地址，见 `docs/mcp-dispatch-20261113.md` 第 6 节。
