# R8 摘取报告 — 频道功能补全二期（phase2-extract）

- **来源**：组合提交 `193cbc2f`（全量构建，基线 `f5b1d39`）——整提交不可直接采纳，按九块带修法移植。
- **基线**：一期新头 `b201e881`（`feat/channel-phase1-20261120`，含 P1-D 管理 UI 合并）。
- **分支**：`feat/channel-phase2-extract-20261120`
- **本地头**：`cbd3074`（变基后本地七提交：`d66141b`、`9dd0fd7`、`986c2e3`、`e7646f0`、`87cac0a`、`cbd3074`，基线 `b201e881`）
- **远程头**：`4776b8eddabef60e3f625ab127669ff9b8217509`（custom.github-agent commit-files 两次原子提交：`91bef90d` 变基内容＋`4776b8ed` 括号修复；树与本地头逐文件一致，已校验 `git diff HEAD origin/...` 为空；`b201e881` 是远程分支的真祖先；**本报告以 `[skip ci]` 单独提交补入，其后分支头再进一位，代码头仍以 `4776b8ed` 为准**）
- **未合 main、未发版、未部署**。合 main 仍等 Funk 发话；验收清单见 §5。

## CI（变基后头为准）

- Android CI：运行号 **44717668951**，头 `4776b8ed`，结论：**success** ✅
- iOS：运行号 **44715255704**，头 `91bef90d`，结论：**success** ✅（`91bef90d`→`4776b8ed` 仅改 `MainActivity.kt` 一个安卓文件，iOS 树完全一致；ios.yml 路径过滤 `ios/**`，故括号修复未触发 iOS 重跑，绿的结论沿用）
- 变基前双端亦绿（头 `cc7da848`）：Android **44710989611** success、iOS **44710989739** success（全步骤通过，第 10 步约 6 分钟正常通过，无冻结）。
- 变基后中间态：`91bef90d` 的 Android **44715255587 failure**（第 7 步 kapt 编译错，见下「变基」一节），iOS 同头 success；括号修复后 Android 重跑即绿。
- iOS 第 10 步冻结判据：超过约 23 分钟按环境阻塞定性、停发、不改代码（本分支此前多次出现跑者／模拟器环境冻结，与代码无关；本次两轮 iOS 第 10 步均为约 6 分钟正常通过）。

## 变基（协调窗指令：合前先变基到一期新头）

- 变基前分支（`8e2f2ec0`＋`cc7da848`，基线 `abf94062`）先在本地变基到 `b201e881`，再删远程旧分支、重建在 `b201e881` 上推上去（同名，真变基历史）。
- 冲突两处，均已解（以一期新头 P1-D 接线为准，保留摘取改动的实质内容）：
  - **MainActivity.kt**：P1-D 的七项扁平管理菜单为准（`performChannelManagement`＋`channel_manage` 入口保留）；摘取的实质两项并入——「发起私聊」不再报待开发，改切私聊分段（方案 A；新私聊从频道成员经控制器 `onStartDm` 发起）；加第八项「创建入帖 Webhook」（令牌只显示一次）。摘取的三项菜单 helper（`showChannelCreateMenu` 三项版／`showChannelTextInput`／`showCreateChannelInput`／`showChannelAdminMenu`）已删，无残留引用；`runTeamChannelAction`（控制器回调在用）保留。
  - **FlagshipRouteDestinations.swift**：新建／删除改走 P1-D 的 `CreateChannelSheet`／`ChannelManagementListSheet`（我的 alert 版新建／删除已撤，两个 state 已删，无残留引用）；摘取的旗舰页动作全保留（feed 接线、二期消息动作、4000、定时、@all、消息中心条）；「发起私聊」仍开既有表单；分段「私聊」与空态「暂无私聊」保留。
- 变基引入的编译错（仅一处）：合并菜单自带的闭合括号与冲突区外的三行公共 `}` 重复，Android kapt 报 `MainActivity.kt:6184 Expecting a top level declaration`。已删重复三行（括号 3215/3215 平衡），提交 `cbd3074`／远程 `4776b8ed`，Android 重跑即绿。iOS 侧同类公共闭合处理正确，无此问题。
- 变基后 §5 自查重跑全过（见 §5）。

## 测试数（以文件内测试函数计，CI 已跑过）

| 文件 | 测试数 | 本轮新增/改动 |
| --- | --- | --- |
| `ChannelExtrasTest.kt` | 13 | @all（mentionsAll/allMentionLive）、单键重试（sendWithSameEffectKey，含全失败抛错且同一键用两次） |
| `PlatformChannelTransportTest.kt` | 22 | 新增消息动作的增量用例；六管理工具重复用例已删；P1-D 合并带入 2 个（变基自动落下） |
| `ChannelFeedRebindTest.kt` | 3 | 新文件：按频道重订（channelFeedRebindNeeded） |
| `TeamChannelTest.kt` | 22 | 只加新边界与任务卡用例；4000 断言沿用一期已有的那一处，未重复加 |
| `ChannelParsingTests.swift` | 12 | 新增 testMentionAllGate（@all 门闩＋消息中心计入） |
| `server/awm` 桥接测试 | — | 未摘（见块 ⑧） |

本地无 Java/Kotlin/Swift 工具链，无法本地编译；CI 为验证口径（与对话框线一致）。

## 九块逐块落实

### ① 安卓传输层 — 修后采纳 ✅
`PlatformChannelTransport.kt`：只新增消息动作（`postMessageEx` 含 reply_to/effect_key/attachment_ref/forward_of、`searchMessages` q、`pollPinnedMessages`、`recallMessage`/`deleteMessage`/`setMessagePinned`/`reactMessage`/`channelReaders`、`listChannels`/`openDm`/`createChannelWebhook`）。管理六工具未动；「服务端尚未提供这些工具」旧注释未扩散。

### ② ChannelExtras — 可采纳 ✅
`ChannelExtras.kt`（新文件）＋测试原文采纳；`buildEffectKey` 只是 `ck_`+nonce，注释已改口，不称 AWM KeyMint。新增：`mentionsAll`/`mentionsAllText`/`allMentionLive`、`buildMessageCenter` 的 `privilegedSenderIds` 可选参数（缺省空，旧测试不动）、`sendWithSameEffectKey(key, maxAttempts=2)`（一键一试＋一次重试，失败抛最后一次错）。

### ③ TeamChannel＋Controller — 修后采纳 ✅
- `TeamChannel.kt`：只加六个新字段（`replyToId`/`recalled`/`pinned`/`reactions`/`attachmentRef`/`forwardOf`）＋映射；4000 规则、ChannelTaskCard、显式派发注释已在一期，不重摘。
- `TeamChannelController.kt`：**整文件未摘**。回复/置顶/反应/转发/撤回/删除/定时/附件引用/已读/搜索/任务卡等动作全部做进一期 `MessageListView`＋`ChatComposerView` 骨架（快照加 9 字段、17 个动作回调全缺省、输入栏工具行、分段行/频道切换行/私聊区/搜索行/消息中心区/置顶区/定时区/每条消息动作行）。旧自绘列表未恢复；`MessageListView`/`ChatComposerView` 引用仍在（本文件 5 处）。

### ④ MainActivity — 修后采纳 ✅（变基后以 P1-D 菜单为准，见「变基」一节）
阻塞①③⑤已修：频道切换按 `activeId` 重订 feed（`teamChannelFeedChannelId`＋`channelFeedRebindNeeded`，与推送整改分支同法）；未读与已读标记按 `activeId`（`0 to false` 硬写已删）；即时发送一次意图只铸一键、失败同一键重试一次。另接：定时队列（`team_channel_prefs` 持久化）、DM 入口（控制器 `onStartDm`→`startTeamChannelDm`，菜单「发起私聊」切私聊分段）、转发选择器、已读情况、频道内搜索、附件引用输入、创建入帖 Webhook（菜单第八项）。`ChannelFeed.kt` 的 `parseChannelWsEvent` 改走 `parsePlatformChannelMessage`（WS 回推的消息也带二期字段，比推送整改分支多做一步）。

### ⑤ iOS 传输层 — 修后采纳 ✅
`ChannelTransport.swift`：只加 `postMessageEx`＋八个消息动作；管理六工具、list、join、q 搜索（一期已在 `pollMessages` 参数里）未重摘。

### ⑥ iOS 视图 — 修后采纳 ✅（管理部分变基后改走 P1-D sheet，见「变基」一节）
`FlagshipRouteDestinations.swift`（`ChannelDetailWorkspaceView`＋`ChannelsHomeView`），动作全部挪到旗舰页的 `DialogMessageListView`＋`DialogComposerView` 上；旧 `ChannelChatView`（不在旗舰壳导航里）**未动、未加动作**。
- **关键发现**：旗舰页此前从不显示平台回帖——`startFeed()` 从未接 `feed.onMessages`，`messages` 只由本地审批流追加。本轮接上：`onMessages` 转 `V5ChannelMessage` 进列表（去重、固定 `yyyy-MM-dd HH:mm:ss` 格式排序、已读游标记到最新一条）。
- `V5ChannelMessage` 加二期字段（`recalled`/`pinned`/`reactions`/`attachmentRef`/`forwardOf`，全缺省）。
- 消息行渲染：回复引用、转发标注、附件引用、反应行、撤回占位、@all 生效 chip／未生效注记；上下文菜单动作全部走平台工具（回复／置顶／👍／❤️／转发／已读情况／撤回仅自己／删除由平台裁决），**删掉本地「编辑消息」（无编辑功能）与本地假「撤回消息」**。
- 输入上限 **500→4000**（与安卓、传输层 `maxPostLength = 4000` 一致）；发送走 `postMessageEx`（replyTo＋attachmentRef＋单 effect_key 失败同一键重试一次）；＋菜单加「定时发送」（10 分钟／1 小时／3 小时）与「加附件引用」。
- 消息中心条：未读按本机已读游标＋置顶数；@我数不报（需推送线的 `learnedSelfNames`，不在本分支）；定时条（UserDefaults 持久化，与安卓同形）。
- `ChannelsHomeView`：新建／管理改走 P1-D 的 `CreateChannelSheet`／`ChannelManagementListSheet`（新建／改名／归档／删除／邀请／移除全走平台工具）；私聊分段去「待开发」；空态改「暂无私聊」＋「＋ 新建 → 发起私聊」指引；发起私聊直接开既有表单。
- `Models.swift`：`ChannelExtras` 加 `mentionsAll`/`mentionsAllText`/`allMentionLive`（与安卓同口径），`buildMessageCenter` 计生效 @all；`ScheduledChannelPost` 补 `replyToId`（与安卓同形）。iOS 取消息没有独立 `searchMessages`，走 `pollMessages`（q 是其参数）。

### ⑦ Models.swift — 修后采纳 ✅
一期 `isArchived` 保留（只一处定义），加 `kind`（缺省 `"channel"`）＋`ChannelMessage` 二期字段＋纯 `ChannelExtras`（iOS 子集：无 `replyThreadOrder` 等）。**大脑未动**：摘后复核全 diff，`brain`／`BrainKind`／`大脑` 命中为 0。

### ⑧ channel_bridge.py — 不摘 ✅
`server/awm/` 下无 `channel_bridge.py`，无其测试。频道派发继续走既有创建任务对话框；服务端仲裁如要接，走已落地的 P4 `platform_adapter.py`（另立方案，不在本轮）。

### ⑨ 测试 — 修后采纳 ✅
见上表。`test_channel_bridge.py` 未摘；`PlatformChannelTransportTest` 的六工具重复用例已删；`TeamChannelTest` 的 4000 断言未重复加；`ChannelParsingTests` 的 `isArchived` 只出现在旧载荷 fixture 键里，断言的是 `kind` 缺省。

## 第七节五项单列（协调窗裁定：一项不划，随块摘）

| 项 | 落实位置 |
| --- | --- |
| 私聊 `openDm`／`kind=dm` | 安卓传输层＋控制器 `onStartDm`＋菜单切分段； iOS 传输层 `openDm`＋旗舰页分段「频道／私聊」＋发起私聊表单 |
| 入站 Webhook | 双端传输层 `createChannelWebhook`（管理六工具之外的新增方法）；安卓菜单第八项「创建入帖 Webhook」 |
| 已读回执 `readers`＋消息中心聚合（三期） | 双端 `channelReaders`＋`ChannelExtras.buildMessageCenter`；安卓消息中心区、iOS 消息中心条（未读＋置顶） |
| 证据等级展示＋`ck_` effect_key（四期，非 KeyMint） | `buildEffectKey`＝`ck_`+nonce，双端注释已改口；即时发送与定时队列共用同一键 |
| 一期管理 UI 接线 | 安卓 P1-D 七项菜单（＋私聊切分段＋Webhook 第八项）； iOS P1-D 的 `CreateChannelSheet`／`ChannelManagementListSheet`／`ChannelSettingsSheet` |

## 阻塞修法落实

- ① 轮询标成实时：安卓频道切换重订 WS（`channelFeedRebindNeeded`）；未接通前 `connectionState` 只许 POLLING/STOPPED，文案「轮询兜底」。
- ② 对话框组件复用：块 ③⑥ 见上；验收：`TeamChannelController.kt` 与 `FlagshipRouteDestinations.swift` 仍引用对话框组件，旧自绘列表未恢复。
- ③ 缺 @所有人：客户端已做——文本提及渲染＋owner/协调者门闩（安卓按快照特权集；iOS 按本机成员角色「项目主管／协调」判定）。**服务端广播语义未定**：17 个合同工具名里没有 @all 专属工具，本轮未自造工具名；是否需要服务端机制，由父代理／协调窗另行裁定，不阻塞本摘取。
- ④ 桥接 vs P4 双入口：桥接不摘（块 ⑧）；摘后检查 `server/awm/` 下无 `channel_bridge.py`。
- ⑤ effect_key 双发：双端一次发送意图只铸一键，失败重试同一键；定时队列路径沿用已铸的键。
- ⑥ 已取消（协调窗复核：P1-S 合同四字段已在案，无需再等）。

## 未做完的尾巴

1. @所有人服务端广播语义待裁定（见阻塞③）。
2. iOS 消息中心 @我聚合待推送整改线的 `learnedSelfNames`（本分支如实只报未读＋置顶）。
3. iOS `@all` 门闩按本机成员角色文本判定（「项目主管／协调」），客户端无服务端下发的频道 owner 字段；角色文案若改，门闩口径须同步。
4. 注册表 `feature_channel_*` 仍为 TODO，未端到端验证不翻 IMPLEMENTED。
5. 服务器补丁部署＋端点验证仍待 Mac 回来；签名测试包版本号分配仍待 Funk 发话；合 main 仍等 Funk 发话。
6. 变基前已绿的中间态（`cc7da848`）与变基后中间态（`91bef90d`）的运行号见 CI 一节，仅作过程记录；验收以变基后头 `4776b8ed` 的运行为准。

## 验收自查（方案第五节，父代理核验，变基后重跑）

- ✅ `server/awm/` 下无 `channel_bridge.py`
- ✅ 管理六工具双端各一处定义，未重复
- ✅ 4000 断言只一期那一处，未重复加
- ✅ `isArchived` 只一处定义；q 搜索：安卓 `searchMessages` 一处定义，iOS 走 `pollMessages` 参数，无重复
- ✅ 对话框组件引用仍在（安卓 `TeamChannelController` 5 处；iOS 旗舰文件 `DialogMessageListView`／`DialogComposerView` 4 处）
- ✅ 即时发送单键重试有测试覆盖（安卓 `ChannelExtrasTest` 全失败抛错且同一键用两次；iOS `sendMessage` 同一键重试一次）
- ✅ 全 diff（`b201e881..HEAD`）`brain`／`BrainKind`／`大脑` 命中 0
- ✅ 新代码工具名只在 17 个合同名内，未自造
- ✅ 远程树与本地头一致（`git diff HEAD origin/feat/channel-phase2-extract-20261120` 为空）；`b201e881` 是远程分支的真祖先
- ✅ 已删的变基前 helper（`showChannelTextInput`／`showCreateChannelInput`／`showChannelAdminMenu`／`showCreateChannelInput`／`newChannelNameDraft`）无残留引用

CHANNEL-PHASE2-EXTRACT-DONE
