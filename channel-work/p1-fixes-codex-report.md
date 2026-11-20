# 频道一期阻塞项修复

- 基线 `feat/channel-phase1-20261120 @ 18f4c6ec03f82111a938348c1eee0672c796d532`，动手前用 `git ls-remote` 核对。
- 交付分支 `feat/channel-p1-fixes-codex-20261121`。
- 审查依据为 `review/grok-phase1-20261121` 的 `channel-work/grok-phase1-review-20261121.md`。

## 五项阻塞

| 项目 | 修法 |
| --- | --- |
| Android @ 中转与只读扇出 | 从频道 feed 回调移除 `ChannelAgentRelay`，删掉扇出控件、回调和 `TeamFanoutRunner` 的频道调用。@ 保留在平台频道消息中，供提及提醒使用。显式创建和派发任务仍走原入口。 |
| iOS 频道任务状态与消息 | 任务列表与消息任务卡只展示状态和详情入口。删除频道的认领、审查、批准、打回及暂停/恢复快捷动作和相关本地伪造消息。显式确认创建的本地排队任务按频道会话关联。频道显示从平台取得的消息和回执。 |
| iOS 频道成员与角色 | 成员来自 `agent_channel_list`，按平台成员 ID 区分同名成员。删除默认成员、本地成员 KV 读写和只写本地的邀请入口。旧的成员 KV 记录不再被读取。@所有人发帖及亮起依平台返回的 owner/协调者角色，缺少身份或角色时收紧权限。 |
| Android 非默认频道 | 默认频道、其他频道和私聊的消息列表统一使用当前 feed 的缓冲。首轮历史拉取及断线轮询由 feed 管理。轮询失败时保留已收到的消息，状态行对应当前订阅。过期 feed 回调和旧频道的快照不能覆盖当前频道。 |
| iOS 切换频道 | 旗舰工作台按频道或私聊 ID 区分视图身份。旧工作台退出时停止 feed 并解除回调，新工作台以新 ID 建立 feed，独立加载消息。已排队的旧回调先校验 feed 身份。 |

## 四项一般修复

| 项目 | 修法 |
| --- | --- |
| iOS 定时发送 | 发送成功后才从持久队列移除。失败项保留原 `effect_key`，频道工作台下一次检查到期队列时按各自的频道重试。 |
| iOS 发送者归属 | 保留平台 `agent_id`，结合平台成员的本人标记，或用已确认发送的消息 ID 与回推匹配学习本机发送者。撤回菜单以本人身份判定，@所有人亮起以发送者的真实角色判定，Agent 标记读取平台身份字段。 |
| iOS 文件连接状态 | 四种连接徽章改为「待开发」。 |
| 频道协同测试 | `ChannelCollaborationTests` 已加入 Xcode 单元测试 target。用例通过生产 `ChannelTransport` → `McpClient` 发出真实的 JSON-RPC 请求，在 URLSession 边界替换 HTTP 响应。校验成员 ID/角色、发帖和按频道读取平台回执。另有 Android 显式任务派发回归测试。 |

## CI 证据

双端均在 `b3c67da28841b0d40631ad5d49b0774e09da80bf` 完成。

| 工作流 | 运行 | 结论 |
| --- | --- | --- |
| Android CI | [#583](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44780511797)（`44780511797`） | `success` |
| iOS | [#298](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44781744518)（`44781744518`） | `success` |

iOS #298 日志确认两项 `ChannelCollaborationTests` 都已执行且通过。iOS #295 已结束后才推送最终代码提交。iOS #297 在排队时取消，由同提交的手动运行 #298 完成验证。Android #579 的 JVM 测试编译发现一处旧构造参数，修正后 #581 和最终 #583 均通过。

本地 iOS 模拟器验证包含频道 feed 与解析测试 20 项、频道传输协同测试 2 项，均通过。70 屏契约和 E2E 接线检查通过。

## 未修及验证边界

- 平台缺少成员角色或本人标记时，客户端不会宣称 @所有人已生效。已有凭证可通过确认发送的消息学习本人 ID，旧平台仍需返回真实角色才能放行。
- iOS 既有正式建单能力是本地任务账本的排队记录。当前分支的 MCP 任务客户端尚未接入平台建单接口，iOS 与 Android 的远端派发落点差异留待后续。旧 `ChannelChatView` 对话组件复用、双端定时回复形状差异和 Android @所有人门闩属于审查的其他一般项。
- @所有人服务端工具、权限管理 UI、频道 Webhook 和 P4 集成留给后续包。话题线程、Markdown、输入中与 iOS 通知档由 AGY 处理。本次改动范围排除 MessageCenter 和大脑分节。
- 频道协同测试使用受控的 HTTP 替身，未在生产平台或真机做验收。

CHANNEL-P1-FIXES-DONE
