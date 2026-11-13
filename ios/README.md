# WhereverCode Agent — iOS 版

原生 Swift / SwiftUI iPhone 客户端，与 Android V5 同一个仓库（`android/`、`ios/`、`shared/` 单仓库方案）。只做服务端真实有的事：团队频道读 / 发、我的任务领取与交付、设置与连接状态。不做假入口——服务端没有的接口在界面上明确标注“服务端无此接口，阻塞中”，未实现的功能标注“未实现”。

> 开发状态：源码在分支上，CI 编包中。源码存在不等于真机验证通过；真机安装与实测结果以交付记录为准。

## 这是什么

- **对话**：团队频道列表（来自平台 `agent_channel_list`）+ 频道聊天。WebSocket 推送为主，断线 15 秒轮询兜底，45 秒后重连，断线期间的消息在重连后自动补拉。连接状态（实时 / 轮询兜底 / 连接中 / 未连接）与真实错误原文显示在聊天页顶部；加载失败在列表里内联显示并可重试。
- **任务**：`list_my_tasks` 列表 + 任务详情页；领取、汇报进度（续租 2 小时）、心跳、交活（分支 / 提交 / PR 链接）、放手（可填原因）。每次操作后都从平台回拉任务状态，页面显示的是服务端的当前状态与版本。平台没有创建 / 指派任务的接口，本 App 不发明任务。
- **设置**：平台地址与频道 ID（普通配置）、Agent Token（只存本机 Keychain）。每次保存后自动做一次真实连接检查（`tools/list` + 可见频道数），结果（已连接 / 还缺什么 / 连接失败的错误原文）就显示在设置页。
- **导航**：四个标签页的选中状态由 `AppState.selectedTab` 统一持有；“去设置”永远是切换到设置标签，不往当前页的导航栈里再压一个设置页。
- **阻塞中**（服务端无此接口，入口处明确标注，不冒充已实现）：审批、暂停 / 恢复 / 停止、改目标、设备管理——V5 的 13 个 MCP 工具里没有对应工具。**未实现**：大脑对话、单兵对话。边界集中在 `Services/ApprovalGate.swift`（`CapabilityGate`）。

## 仓库结构

```
ios/
  WhereverCodeAgent.xcodeproj/     # Xcode 工程（app + 单元测试 + UI 测试三个 target）
  WhereverCodeAgent/
    App/WhereverCodeAgentApp.swift # 入口 + AppState（标签选中、设置、Token、连接状态、客户端工厂）
    Models/                        # ChannelMessage / AgentTask / ChannelInfo / AppSettings
    Services/
      McpClient.swift              # MCP tools/call（JSON-RPC 信封、isError 处理、HTTP 错误带回原文）
      ChannelTransport.swift       # agent_channel_* 三个调用
      ChannelFeed.swift            # WS 推送 + 轮询兜底 + 去重 + 重连状态机
      TaskService.swift            # list/claim/report/heartbeat/submit/release
      KeychainStore.swift          # Token 的唯一存放处
      ApprovalGate.swift           # 未实现 / 阻塞中能力的边界（调用即抛错）
    Views/                         # 对话列表、频道聊天、任务列表与详情、设备（阻塞说明）、设置
    Resources/
      Info.plist                   # Bundle ID wherevercode.agent，最低 iOS 17.0
      Assets.xcassets/             # AppIcon + AccentColor
  WhereverCodeAgentTests/          # 单元测试：解析、MCP 信封、设置校验、Keychain、Feed、导航与连接状态、页面截图渲染
  WhereverCodeAgentUITests/        # UI 测试：标签导航回归 + 配置不可达地址后的真实错误态 + 截图
```

## 协议（已按 2026-11-13 实测核对，不要编造）

- MCP 端点：`{平台地址}/api/open/v1/mcp`，POST JSON-RPC 2.0 `tools/call`，Bearer Agent Token 只进 `Authorization` 请求头。响应信封 `result.content[0].text` 是工具 JSON；工具级失败是 `result.isError == true`，不是 JSON-RPC error。
- 已核实的 13 个工具：`list_my_tasks`、`claim_task`、`report_progress`、`heartbeat`、`report_status`、`submit_result`、`release_task`、`attach_delivery_diff`、`get_delivery_diff`、`agent_channel_list`、`agent_channel_join`、`agent_channel_post`、`agent_channel_messages`。没有 `create_task` / `assign_task` / `subscribe_events`。
- 频道 WS：`GET /api/open/v1/channel/ws?channel_id=...`（Bearer 鉴权，101 + hello，20 秒 ping，发帖即推 `{"type":"message"}`）。
- 频道消息字段：`id` / `agent_id` / `agent_name` / `text` / `reply_to` / `created_at`（Unix 秒，小数）。
- 任务租约 2 小时；`report_progress`（正文最长 2000 字）与 `heartbeat` 可续租；`submit_result` 开发任务须至少给 `branch` / `commit` / `pr_url` 之一。

## 安全

- 平台地址默认留空，由用户在设置里填；只接受 `https`（ATS 保持开启，不信任自签证书，不关 ATS）；地址里不许带 `user:pass@`。
- Token 只存 Keychain（`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`，不同步 iCloud），不进 UserDefaults、文件、日志与错误信息。
- 代码、注释、测试数据里没有任何真人实名、硬编码私有地址或密钥；测试用 `example.invalid` 占位域名与明显假的 Token。

## 本地构建

需要 Xcode（macOS，iOS 17 SDK 及以上）。打开 `ios/WhereverCodeAgent.xcodeproj`，选自己的 Team 签名后 Run；或命令行：

```sh
xcodebuild -project ios/WhereverCodeAgent.xcodeproj -scheme WhereverCodeAgent \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
xcodebuild test -project ios/WhereverCodeAgent.xcodeproj -scheme WhereverCodeAgent \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

## 未签名 IPA（GitHub Actions 云端构建）

`.github/workflows/ios.yml` 在 `macos-15` runner 上用其自带最新稳定 Xcode 构建 `.app`，打包 unsigned IPA 并上传 Artifact（保留 30 天）。公开仓库 Actions 免费，不烧私有库额度。

安装（自签侧载，任选其一）：

1. 在 Actions 的 run 页面下载 `WhereverCodeAgent-unsigned-ipa`（`WhereverCodeAgent-unsigned.ipa`）。
2. 用 [Sideloadly](https://sideloadly.io/) 或 AltStore 侧载到 iPhone：把 IPA 拖进去，用自己的 Apple ID 签名安装；Bundle ID 为 `wherevercode.agent`，最低 iOS 17.0。
3. 首次启动后去「设置」填平台地址与 Token，再进「对话」。

CI 不存任何用户签名私钥；每次安装后 7 天需重签是 Apple 免费签名的固有限制，与本 App 无关。

## 测试

`xcodebuild test`（见上）跑全部测试：频道 WS 事件解析、消息 / 频道 / 任务宽容解码、MCP 信封解包与 `isError`、HTTP 错误原文透传、设置校验（https 强制、userinfo 拒绝）、Keychain 存取删、Feed 重连状态机与 1000 条去重上限、标签导航回归（选中状态归 AppState）、连接状态、阻塞标注文案。另有 UI 测试驱动真实 App：从“去设置”切标签、在设置里填不可达地址后各页显示真实网络错误。测试不使用真实账号；网络相关用例走 URLProtocol 替身或不可达的 `.invalid` 地址。

CI 每次运行把模拟器截图（五个主页面渲染 + UI 测试截图）作为 `ios-screenshots-simulator` Artifact 一并上传——那是模拟器证据，不冒充真机。
