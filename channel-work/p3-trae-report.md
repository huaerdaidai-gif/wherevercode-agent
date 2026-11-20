# P3-MSG 三期 · 消息中心（双端）— TRAE 国内版交工报告

- 派发包：P3-MSG 消息中心（双端），Muse 派发，2026-11-20
- 基线：`feat/channel-phase1-20261120 @ abf94062`（已核对）
- 分支：`feat/channel-p3-msgcenter-trae`（只在此分支作业，不合 main）
- 代码头（双端 CI 跑此头）：`7e0a78160a8ccbcdd92f038c84f89bbc1233a4cb`；分支最终头 = 本文档提交（[skip ci]，只加报告，不改代码），回执时一并提供

## 一、做了什么

双端各新增一个消息中心视图，客户端聚合频道消息的三类条目：

1. **@我**：频道消息正文里以 `@<Name>` 提及本机身份的消息（平台寻址无收件人字段，寻址惯例就是正文前缀——已核对 `PlatformChannelTransport.kt` / `ChannelTransport.swift` 头注释）。
2. **未读**：用既有读接口 `pollMessages` 的 `only_unread` 拉各频道未读页。
3. **任务回执**：带任务归属头（任务短 id）的消息；复用基线已有的归属头解析与口径（Android 直接调用 `core/team/TeamChannel.kt` 的 `parseTeamAttribution`，iOS 按同一协议镜像实现），不另造协议。

条目呈现复用已摘入基线的共用对话组件：Android 用 `MessageListView`（列表宿主，与频道页/单聊同款），iOS 用 `DialogMessageListView`（LazyVStack 脚手架，与频道工作区/单聊同款）。不重复造轮子。

## 二、改动清单（只新增文件为主）

新增（Android）：
- `android/app/src/main/java/wherevercode/agent/core/message/MessageCenterRules.kt` — 纯聚合规则 + 只读加载管道
- `android/app/src/main/java/wherevercode/agent/ui/MessageCenterController.kt` — 消息中心控制器/视图（复用 MessageListView）
- `android/app/src/test/java/wherevercode/agent/core/message/MessageCenterRulesTest.kt` — 规则 + 桩传输读语义测试（7 条）
- `android/app/src/test/java/wherevercode/agent/ui/MessageCenterControllerTest.kt` — 视图 Robolectric 测试（3 条）

新增（iOS）：
- `ios/WhereverCodeAgent/Services/MessageCenterRules.swift` — 纯聚合规则（含 `MessageCenterTransport` 最小协议 + 既有 `ChannelTransport` 适配器）+ 只读加载管道
- `ios/WhereverCodeAgent/Views/MessageCenterView.swift` — 消息中心 SwiftUI 视图（复用 DialogMessageListView）
- `ios/WhereverCodeAgentTests/MessageCenterTests.swift` — XCTest：规则 + 桩传输读语义测试（7 条）

接线修改（不在禁区名单内，均为最小改动）：
- `MainActivity.kt`（Android）— 挂载消息中心屏、后台线程拉取渲染、点条目跳转、频道页「＋ 新建」菜单加第三项「打开消息中心」
- `ui/DrawerRouter.kt`（Android）— `EntryScreen.MESSAGE_CENTER` 与字面 id 路由（`message_center` / `feature_message_center` / `notifications`）
- `FlagshipRouteDestinations.swift`（iOS）— 既有 `notifications` 路由渲染真实 MessageCenterView；频道工作区顶栏「＋ 新建」菜单加「打开消息中心」（`channels.action.messageCenter`）
- `ios/WhereverCodeAgent.xcodeproj/project.pbxproj` — 注册 3 个新 Swift 文件（app target 2 个 + test target 1 个）

**未触碰**（工作包禁区，逐一核对过 git diff）：
- 传输文件：`PlatformChannelTransport.kt`、`ChannelTransport.swift` 未改
- `TeamChannelController.kt`、对话框组件（`ListDialog` / `MessageListView` / `DialogComponents.swift`）未改
- 注册表 `EntryRegistry.kt` / `EntryRegistry.swift` 未改，`feature_message_center` 保持 TODO（翻转留到组装步）
- `v5_platform.py`、大脑目录未碰；无任何代码执行入口；频道不能执行代码

## 三、入口（可达可测，不翻注册表）

- Android：频道页 → 顶栏「＋ 新建」→ 菜单第三项「打开消息中心」→ 进入消息中心；顶部「关闭」回到频道页、「刷新」重拉。`DrawerRouter` 已备 `message_center` 字面 id 路由，注册表翻转后抽屉即可直达（组装步用）。
- iOS：频道工作区（频道路由）→ 顶栏「＋ 新建」→「打开消息中心」；或抽屉 → 控制 → 系统分组 → 「消息中心」（既有 `notifications` 路由，此前是占位屏，本次挂上真实实现）。注册表 `feature_message_center` 仍 TODO。

## 四、点条目跳转语义（双端一致，如实标注）

- 双端：点条目 → 跳到**对应频道**页（Android `showEntry(频道)`；iOS `onNavigateRoute("channel")` 推回频道工作区），条目来源频道、发送者与正文摘要在消息中心行内已标注。
- 「对应位置」现状：Android 端频道页为唯一频道页（单频道现状），消息就在该页的消息流/任务串里；iOS 端工作区显示该频道的消息流。**页内像素级滚动定位未做**——频道页自身的滚动焦点需要改 `TeamChannelController` / 频道工作区视图，而本包禁区明确不改它们；留待频道页支持焦点参数后接线（见「七、尾巴」）。

## 五、已读标记语义（重点，与桩传输测试一起读）

服务端 `agent_channel_messages` 的 `only_unread` 与已读标记联动（读到哪标到哪）。消息中心的取数口径：

1. @我 / 任务回执区：`pollMessages(channelId, null, 20, onlyUnread=false, markRead=false)` —— 普通读最新一页，不碰已读标记。
2. 未读区：`pollMessages(channelId, null, 20, onlyUnread=true, markRead=false)` —— 取服务端已读标记之后的消息；`markRead=false` 意味着**本调用不推进服务端已读标记**，频道页的未读状态不会被消息中心误消费。
3. 消息中心**永不发起 `markRead=true`**：唯一允许推已读的是频道页自己（用户真正浏览频道时的既有路径）。消息中心也无任何本地已读写入（纯函数 + 只读管道）。

锁定方式：双端桩传输测试逐调用记录 `pollMessages` 参数并断言——全部调用 `markRead=false`；未读区条目只来自 `onlyUnread=true` 的那一页；逐频道恰好两页（普通读 + only_unread 读）。Android 见 `MessageCenterRulesTest`（FakeTransport），iOS 见 `MessageCenterTests`（StubTransport）。此口径与 Android 频道页既有未读探针（`serverTeamChannelUnread`，同样只探不标）同族。

## 六、三类聚合判定规则

- **@我**：正文（剥归属头后）含 `@<别名>`、别名属于本机身份集合 = 本机 App 别名（Android「本机 App/协调者」；iOS「我/本机 App/协调者」）∪ 注册表里启用的本机 Agent 的 agentId 与显示名（@ 到自己的 Agent 也算 @我，与 `ChannelAgentRelay.findMentions` 的 @ 寻址口径同族但更严格）。边界：`@` 后按最长别名优先匹配；后随英文字母/数字不算命中（`abc@x` 是词内 @）；单字中文别名后随汉字不算命中（「@我们」≠「@我」），多字别名后随汉字算命中（「@协调者一下」算）。
- **未读**：只认 `only_unread=true` 页（见第五节口径），区内按消息 id 去重、新→旧排序。
- **任务回执**：归属头带任务短 id 即回执（Android 复用 `parseTeamAttribution`；iOS 镜像同协议：新 `【发送者 · 任务 <短id> · <标题>】` 与旧 `[发送者]` 两格式）。条目展示剥头正文；发送者优先取归属头署名（中转回复的头才是真发声者），否则用传输 `agent_name`。
- 每区最多呈现最近 20 条（一页读回上限），超出如实截断；一条消息可同时出现在多个区（如既是 @我 又是任务回执），区内按 id 去重。

## 七、测试与 CI

本地验证（沙箱，非真机）：
- Android：`gradle :app:testDebugUnitTest`（JDK17 + SDK34，与 CI 同参）。全量套件一次性跑不完（沙箱 Kotlin daemon 内存受限），按包分批执行，受影响测试类约 100 类全绿，含新增 `MessageCenterRulesTest`（7 条）、`MessageCenterControllerTest`（3 条），以及受接线影响的 `MainActivitySmokeTest`（2 条，容器子视图 13→14 的合法维护）、`EntryRegistryTest`（12 条）、`DrawerRoutingTest`（5 条）、`TeamChannelTest`（22 条）、`ListDialogTest`（8 条）、`HomeScreenWiringTest`（7 条）。
- iOS：沙箱无 macOS，无法本地跑 xcodebuild；新 Swift 文件已注册进 `project.pbxproj`（app + test target），以 CI（macOS runner）为准。`MessageCenterTests` 共 7 条（与 Android 端逐条对照），CI 结论见下。

CI 证据（双端均跑在代码头 `7e0a781`）：
- Android CI：运行号 ____，结论 ____
- iOS：运行号 ____，结论 ____（含 `MessageCenterTests` 7 条通过，日志核对）

## 八、未做完的尾巴

1. 页内滚动定位：点条目目前跳到对应频道页，页内像素级滚动到具体消息需频道页支持焦点参数（涉及禁区文件），留待组装步。
2. 多频道：Android 端现状单频道（`teamChannelEndpoint()` 单端点），消息中心的聚合结构已按多频道设计（逐频道两页拉取、合并去重），Android 端多频道落地时无需改本包结构。
3. `feature_message_center` 注册表翻转 + 抽屉直达入口：按包要求留到组装步。
4. iOS 端真机验证：本包无真机条件，交工以 CI 绿 + 桩测试为准，如实标注「未经真机验证」。

## 九、报告回执

（最后一行由文档提交回填）
