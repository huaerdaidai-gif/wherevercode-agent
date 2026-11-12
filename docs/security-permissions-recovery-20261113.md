# 安全权限 / 异常恢复加固（2026-11-13）

分支：`security/permissions-recovery-tests-20261113`，基线 `main` @ `f6d24d6`。
只做最小的安全守卫与回归测试，不改单兵发送/轮次（Codex）与频道、MCP 传输（Muse）的核心逻辑。

## 修复

| 范围 | 问题 | 修法 | 文件 |
|---|---|---|---|
| 文件越权 | 叶子是指向工作区外的**悬空符号链接**、或不存在的子路径挂在指向外部的目录链接下时，解析结果看似在根内，写入会跟随链接落到外面 | 新增 `SafePaths`（真实路径 + 悬空链接拒绝 + 逐级解析不存在部分），在 `FileWorkspaceAdapter.resolveWorkspacePath`、`ToolRegistry.resolveInWorkspace`、`WorkspaceBrowser.resolve`、`BaseDeviceAdapter.isPathAllowed` 末尾复核 | `core/security/SafePaths.kt` 等 |
| 命令权限 | `find -exec/-delete`、`git diff --output=…`、`--ext-diff/--textconv`、`python -cprint()`/`-uc`、`node -pe` 绕过白名单 | `dangerousArgumentReason`，接进 `CommandLimits.checkCommand`/`validateCommand` 与 `WorkspaceGuard` | `core/security/CommandLimits.kt`、`core/workspace/WorkspaceGuard.kt` |
| 免审批执行 | `isReadOnlyRun` 只看 argv[0]：`find -delete`、`git push`、`cat /data/data/<包>/shared_prefs/…` 免审批 | 危险参数 / 非只读 git 子命令 / 绝对路径、`~`、`..` 参数不再算只读，回落到用户审批 | `brain/ToolRegistry.kt` |
| Hand run | 远端调用方的 run 参数不查路径 | 参数指向授权文件夹外即拒绝（`hand_path_not_allowed`） | `core/mcp/HandServer.kt` |
| MCP 令牌 | MCP 行的 Authorization / X-Api-Key / `*_TOKEN` 明文存未加密 SharedPreferences，导出也带明文；App 无 `keystore:` 解析器 | 落盘前封存进 Keystore（EncryptedSharedPreferences），行里只留 `keystore:` 引用；旧明文首次读取时迁移；接上解析器 | `core/extension/mcp/McpSecrets.kt`、`brain/siwc/BrainBootstrap.kt` |
| 日志泄露 | 裸 `v5_`/`wco_`/`ghp_`/`github_pat_` 令牌不被脱敏；WS/Manus 诊断日志未脱敏 | `SecretRedactor` 增加规则；两处 `Log.d` 先脱敏 | `core/security/SecretRedactor.kt`、`MainActivity.kt`（2 行） |
| 频道权限 | 频道里任何人 `@Codex` 都会被中转到用户设备执行；正文署名可冒充别的 Agent | `ChannelSenderPolicy`：只有本机 App 自己的帖与登记且启用的平台 Agent 才能触发中转；署名只在 App 中转回帖或与真实发送者一致时采信，否则显示“自称 X” | `core/team/ChannelSenderPolicy.kt`、`ChannelAgentRelay.kt`、`TeamChannel.kt`、`TeamChannelController.kt`、`MainActivity.kt`（接线） |
| 停止后写入 | 审批等待期间用户停止，扩展（MCP）工具仍执行；终态任务仍接受迟到的 result/error/续租 | `ToolRegistry.execute` 执行前再查停止门；`TerminalWriteGuardTaskStore` 装饰器（不改 `RoomTaskStore`） | `brain/ToolRegistry.kt`、`core/task/TerminalWriteGuard.kt`、`brain/siwc/BrainBootstrap.kt` |

## 新增测试（JVM / Robolectric）

`SafePathsTest`、`CommandHardeningTest`、`WorkspaceSymlinkEscapeTest`、`ToolRegistrySecurityRegressionTest`（含工具超时、运行中取消、审批期间停止）、`McpTokenProtectionTest`、`ChannelPermissionTest`（含网络断开、设备 Agent 超时）、`TaskStopAndRestartRecoveryTest`（含文件型 Room 库关库重开的 App 重启恢复）。

## 交叉问题（未改，留给对应负责人）

- Codex：`TurnTaskRecorder.beginRemoteRound` 主线程访问 Room 且吞异常返回 null（单兵发送失败的疑似根因）。
- Codex：`TerminalWriteGuardTaskStore` 生效后，`PipelineRunner` 在终态任务上写 `error`+`needs_attention`、`MemberDelivery`/`LocalTaskRunner` 在已取消任务上补写 `result` 会被拒（调用点都已 try/catch，行为是“不覆盖取消说明”）。
- Muse：`McpServersConfig.resolveValue` 只认整值 `keystore:<ref>`，文档示例 `Bearer keystore:github-mcp` 解不出来。
- Muse：`McpOAuthWiring` 用内存 token 存储，重启即丢；如改为持久化须走 Keystore。
- Muse：平台消息若能带服务端签名的发送者身份，可替代 App 侧按消息 id 学习自身 agent_id 的做法；`ChannelAgentRelay` 的 `isTrustedSender` 默认放行仅为兼容，新调用点必须传策略。
- Hand：`LocalHand.run` 先 `waitFor` 再读管道，输出超过管道缓冲会卡到超时；运行中不看停止/取消。
- 命令：git 在不可信仓库里仍可经仓库配置（`core.fsmonitor`、`.gitattributes` 的 diff/textconv 驱动）执行程序；`npm`/`npx`/`make`/`cargo`/`go` 本质能执行任意代码（需审批）。
- 符号链接 TOCTOU：检查与写入之间换链接的竞态在 JVM 层无法完全关闭（无 openat/O_NOFOLLOW）。
- 删除 MCP 服务器不会清掉 Keystore 里对应的引用值（密钥库无列举接口）。
