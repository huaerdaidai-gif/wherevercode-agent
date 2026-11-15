# Android V5 源码审计（开发前）

审计日期 2026-11-14 UTC。目标仅 wherevercode-agent。

主线 `1b9f4f8976e262fcb3a40f95cbad5ada597ce9a6` 并非最新 Android 集成成果。
核对远程分支后，选用 `origin/feat/v5-dual-70-assembly-20261114` 的 `5f123cc` 为开发基线，独立分支 `android/v5-ui-assembly`。该分支包含现有 Android 任务创建、控制回执修正、Compose 依赖与测试；保留其 iOS 树，不修改 iOS、共享 API 或生产配置。

上传 ZIP 已完整解压到 baseline，包含 70 Kotlin/SwiftUI 路由、两份验收表、两份 HTML 参考与接线契约。检查 JSON 清单为 70 个唯一 ID。原 Compose 脚手架含五栏底导航，违反最新四父级要求；不直接覆盖 Activity。包中没有独立 PNG/JPEG 首页截图，像素级对照仍缺原截图。

## 实际发现

- MainActivity 为 XML/View 单 Activity，已有业务 Controller、后台刷新和生命周期逻辑；Compose 仅在附加 FlagshipActivity。当前两个导航体系割裂。
- EntryRegistry 有十个一级入口，另有扩展分组和 70 屏附加入口；不符合四父级要求。旧定制数据需保留，不以新导航覆盖持久化表。
- FlagshipApp 70 页多数仅为占位卡，未验证能力也被禁用，导致已有功能无法从旗舰层使用。
- AppFlagshipCapabilities 将历史 K70 使用描述直接标为 READY_VERIFIED，证据不覆盖本次构建；本轮应统一降为源代码已接线、待验收。
- ChatViewModel 复用真实模型/Agent 路由、项目绑定、任务记录与远端控制回执。conversation 不能复用“新对话”动作，否则会丢失当前会话上下文。
- ConsoleSnapshot 真读 TaskStore/TaskStepStore/eventLog/ApprovalBroker/AgentStore/RemoteDeviceStore。在线≠配对；无用量账本，不可编造数字。
- MemberTaskControl 保持独立 paused，stop 转 cancelled 是现有内部语义；TaskControlActions 通过 ControlCommandPort 等待 confirmed。新 UI 不新增“取消=停止”别名，不绕过控制平面。
- DeliveryTracker 为单向水位 PERSISTED→DELIVERED→CLAIMED→READ，READ 也不代表任务完成。
- 任务创建 TaskComposer→TaskStore，派发与领取 TaskScheduler/RemoteTaskDispatcher，执行 LocalTaskRunner/RemoteTaskPump，审查返工集成 PipelineOrchestrator。详情入口需要实体 ID，不能将列表打开称为详情验收。
- docs/p0/screens-18.csv 与 ui-backend-70.csv 在所选基线存在；后者是 **70 个操作**，并不是上传文件的 **70 个路由**。保留原表，新审计以 route_id 为键另建。

## API 与阻塞

70 路由不是 70 个 HTTP API。多数本地页面读 Room/Preferences 或调用现有引擎；远端客户端使用 HandClient、GitHostApiClient、模型 HTTP/SSE、频道 WebSocket、MCP。源码 API 证据见 api-evidence.txt；原接线 JSON 的 method/path 全为空，不推断新端点。

自动化规则、持久触发器、跨端同步/离线发件箱、统一成本账本、技能市场与安装沙箱、运行预览与测试矩阵无完整产品链路。需要设计并实现服务与权限契约，当前禁止标为可运行。语音仅保留诚实入口，未提供识别服务。统一网络页和运行时探针可复用底层，但需要开发页面。

首批仅组装主导航/路由层并复用现有真实页面，明确缺口，不改变任务控制或接口契约。70 路由可达不等于 70 能力完成。完整矩阵见 audit-70.csv。
