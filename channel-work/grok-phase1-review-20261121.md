# 频道一期合并前独立审查（Grok Build）

- 仓库：`huaerdaidai-gif/wherevercode-agent`
- 审查分支：`feat/channel-phase1-20261120`
- 动手前 `git ls-remote`：`18f4c6ec03f82111a938348c1eee0672c796d532`（与指定头 `18f4c6ec` 一致）
- 本地只读克隆头：`18f4c6e`「Merge phase-2 extraction (R8 pick, nine blocks with fixes) into channel phase-1」
- 对照：`channel-work/p1d-manus-report.md`、`channel-work/phase2-extract-report.md`
- 范围：源码与单测文本。未重跑 CI，未改一期分支，未合 main。
- 结论：**有阻塞项，不建议按现状合 main。**

严重度：阻塞＝合前必须处理；一般＝行为不对但不至于单独挡合并口径里的「完全不能用」；建议＝残留或文案。

---

## 1. 频道硬标准

### ① 频道页复用单 Agent 对话框组件

主路径已接上同一套列表宿主和输入区，消息行仍各画各的（组件注释写明不共用渲染器）。

- 安卓频道：[TeamChannelController.kt](android/app/src/main/java/wherevercode/agent/ui/TeamChannelController.kt) 第 142 行 `MessageListView`、第 181 行 `ChatComposerView`（INLINE）。单聊同一类：[MainActivity.kt](android/app/src/main/java/wherevercode/agent/MainActivity.kt) 第 474–477 行，布局 [screen_chat.xml](android/app/src/main/res/layout/screen_chat.xml) 第 142、191 行。
- iOS 旗舰频道页：[FlagshipRouteDestinations.swift](ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift) 第 3607 行 `DialogMessageListView`、第 3886 行 `DialogComposerView`。单聊：[LocalData.swift](ios/WhereverCodeAgent/Data/LocalData.swift) 第 1215、2090 行。

**一般** — 旧频道页没有复用。

- 文件:行号：`ios/WhereverCodeAgent/Views/ChannelChatView.swift:74`（`ChannelContentView.messageList` 自绘 `List`）；入口 `ios/WhereverCodeAgent/Views/ChatListView.swift:122`
- 问题：`ChatListView` 仍能打开 `ChannelChatView`。这条路径的列表和输入区不是 `DialogMessageListView` / `DialogComposerView`。P1-D 报告把它称作旧导航，但代码还在。
- 证据：`ChannelContentView` 的 `body` 是 `connectionBanner` + 自绘 `List` + 自绘 `composer`，全文无 `DialogMessageListView`。
- 严重度：一般

主路径本身：已核、无发现。

### ② 频道不能执行代码

未通过。定版写的是「@ 只提醒、不直达执行」，安卓仍把 @ 和「只读扇出」送进会执行 Agent 的同一条 `runAgent`。iOS 主频道页另外在任务分区改任务状态，并写入 Agent 没说过的话。

**阻塞** — 安卓 @ 中转会启动远端 Agent。

- 文件:行号：`android/app/src/main/java/wherevercode/agent/core/team/TeamChannel.kt:33`；`android/app/src/main/java/wherevercode/agent/MainActivity.kt:6326`；`android/app/src/main/java/wherevercode/agent/core/team/ChannelAgentRelay.kt:233`；`android/app/src/main/java/wherevercode/agent/core/mcp/RemoteAgentSession.kt:180`
- 问题：2026-11-20 定版写明「频道本身不执行代码——@ 提及只提醒、不直达执行」。feed 每收到一条消息仍调用 `maybeRelayChannelMessages` → `ChannelAgentRelay.relay` → `handPort.runAgent` 或 `manusPort.runChat`。`runAgent` 的 argv 头是 `"agent"`，注释写明服务端据此查 `agents.json` 执行。围栏只是提示词（`TeamChannel.kt:177`「不要改任何文件」），不是关掉工具。这和任务派发用的是同一个 `RemoteAgentChatPort.runAgent`。
- 证据：受信发送者的 `@codex` 会走到 `HandRemoteAgentChatPort.run`，超时 300 秒（`TeamChatLimits.RELAY_TIMEOUT_SECONDS`）。iOS 频道发送路径没有对应中转（`sendMessage` 只 `postMessageEx`），双端对「@ 会不会跑 Agent」不一致。
- 严重度：阻塞

**阻塞** — 安卓频道页「只读扇出」同样是 `runAgent`。

- 文件:行号：`android/app/src/main/java/wherevercode/agent/MainActivity.kt:6785`（`startTeamFanout`）、第 6828–6830 行 `TeamFanoutRunner(remoteChatPort, store).run`
- 问题：界面写「分支只读摘要，不改文件」（`TeamChannelController.kt:1023`）。实际仍是一次完整 Agent 调用，约束只在 `buildFanoutBrief` 正文里。
- 证据：与上面同一 `RemoteAgentChatPort.runAgent`。没有只读工具开关。
- 严重度：阻塞

**阻塞** — iOS 频道工作台在频道内改任务状态，并插入伪造发言。

- 文件:行号：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:4513`（认领：`queued → assigned → working`）、`:4494` / `:4690`（批准交付）、`:4504` / `:4719`（打回返工）、`:4670`（伪造审查消息）、`:4746`（伪造 Agent 回执）
- 问题：这些按钮在主视图 `ChannelDetailWorkspaceView` 的「任务」分区和消息里的协同卡上，不是死代码。安卓定版写「频道页没有改任务状态、认领、标记完成的入口」（`TeamChannelController.kt:91`）。iOS 直接 `LocalData.shared.tasks.transition`，再 `messages.append` 一条 UUID 消息，正文写成 Codex/Agent 已经接单或请审查。消息不经过平台，feed 也不会有这条。时间用 `DateFormatter.localizedString`（`:4671`），真实 feed 消息用固定 `yyyy-MM-dd HH:mm:ss`（`:4841`），混在同一个数组里按字符串排序会错位。
- 证据：`submitTaskForReview` / `reworkTask` 在失败以外的路径没有 `postMessageEx`。用户看到的「Agent 已完成」不是 Agent 说的。
- 严重度：阻塞

**阻塞** — iOS 频道成员默认是写死的在线假人，并会落盘。

- 文件:行号：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:2396`（`ChannelMemberManager.loadMembers`）、`:2401`
- 问题：本地没有记录时，写入并返回「我 / 项目主管 · 人工审批 / 在线」「Codex / 在线」「Claude Code / 就绪」。这不是平台成员列表。随后 @all 门闩（`:4048`）读的就是这条假角色。
- 证据：默认数组写死 `id: "agent-codex"`、`status: "在线"`，`setJSON` 进 kv。安卓成员来自 `buildChannelMembers(agentStore.listAgents())`（`MainActivity.kt:5958`），没有这段假人。
- 严重度：阻塞

### ③ 频道可以派发任务

安卓显式派发是真的。iOS 主视图只建一条本地排队任务，和安卓不是一条路。

- 安卓：每条消息「转为正式任务」（`TeamChannelController.kt:933`）和输入栏「作为任务发送」（`:406`）都进 `showCreateTaskDialog`（`MainActivity.kt:1656`、`:9022`）。确认后 `TaskComposer.submit`（`:9045`），提示「等待执行器认领」，并 `recordChannelTaskCard`（`:6085`）。讨论发送不建任务。这条已核。
- iOS 主视图消息行上下文菜单没有「转为正式任务」（菜单收到 `:3878`，只有「关联任务」）。输入方式选择（`:3898`）会打开表单，`createTaskFromComposer`（`:3455`）只 `LocalTask(state: "queued")`，不选 Agent、不进 `TaskComposer`、不留频道任务卡。页头按钮（`:3298`）是 `onNavigateRoute("compose")`，不是这个确认表单。旧 `ChannelChatView` 的行按钮（`ChannelChatView.swift:160`）同样只建本地 `LocalTask`。

**一般** — 双端派发落点不一致。

- 文件:行号：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:3455`；对照 `android/app/src/main/java/wherevercode/agent/MainActivity.kt:9045`
- 问题：iOS 能建本地任务，所以不是「完全不能派发」。但它不走执行器认领，消息行也没有和安卓同名的逐条入口。P1-D 报告写的「每条消息先表单再落任务」在旗舰主视图的消息行上没有。
- 证据：`createTaskFromComposer` 成功路径只清空输入并关表单，无任务卡、无 `openTaskDetail`。
- 严重度：一般

### ④ 功能齐全（对照 channel-work）

对照两份工作包，下面这些是按包落地的，抽查代码在：

- 对话框宿主复用：见 ① 主路径。
- 显式派发文案双端一致：「仅讨论会发送频道消息；作为任务发送会打开创建表单，确认后进入任务列表。」（`TeamChannelController.kt:160`，`FlagshipRouteDestinations.swift:4036`）。
- 管理六工具未另起炉灶；安卓菜单八项在 `MainActivity.kt:6098`，删除仍交给服务端（`:6160`）。
- 单次发送一个 `effect_key`、失败用同一键再试：安卓 `MainActivity.kt:6467`，iOS `FlagshipRouteDestinations.swift:4237`。
- 4000 字：安卓 `TeamChatRules.validatePost` 拦住（`TeamChannel.kt:114`，控制器 `:432`）。iOS 传输层 `ChannelTransport.maxPostLength = 4000` 会抛错（`ChannelTransport.swift:42`）。
- 注册表没有把未端到端的项翻成 IMPLEMENTED：`feature_channel_files` / `feature_direct_message` / `feature_team_space` / `feature_message_center` 仍是 TODO（`EntryRegistry.kt:204`，`EntryRegistry.swift:98`）。这和 phase2 报告第 4 条尾巴一致，不算假绿。

工作包自己写明、这次仍在的缺口（不算新挖，但「功能齐全」不能划勾）：

- iOS 消息中心不计 @我。`FlagshipRouteDestinations.swift:3629` 注释写明要等推送线的 `learnedSelfNames`，本分支没有。
- @所有人没有服务端工具，只是客户端门闩，而且门闩不可靠（见第 3 节）。
- 频道文件、私聊服务、团队空间、消息中心注册表仍是 TODO。iOS 却有文件页和私聊分段 UI（见第 3 节假状态）。

**结论：① 主路径已核；② 未通过；③ 安卓已核、iOS 只是本地任务（一般）；④ 工作包里的管理/派发文案/4000/幂等键已核，齐套未达到。**

---

## 2. 实时口径

推送为主、轮询兜底、文案不把 POLLING 叫直播：默认频道和 iOS feed 这条是对的。

- 安卓 `ChannelFeed`：先拉一页，再 WS；断开才 `POLLING`，15 秒轮询，45 秒重连（`ChannelFeed.kt:42`、`:130`、`:182`）。顶栏 `LIVE` 写「直播推送」，`POLLING` 写「轮询兜底」（`TeamChannelController.kt:466`）。
- iOS 同数字（`ChannelFeed.swift:100`），旗舰顶栏同样区分（`FlagshipRouteDestinations.swift:3480`）。单测 `TeamChannelControllerTest` 要求轮询文案含「轮询兜底」且不把证书原文直接铺开，和实现对得上。

**阻塞** — 安卓一切到非默认频道，屏幕上的消息主路径变成轮询，状态行仍可能写直播。

- 文件:行号：`android/app/src/main/java/wherevercode/agent/MainActivity.kt:6012`
- 问题：`activeId !=` 端点默认频道时，快照消息来自 `pollMessages(activeId, null, 50)`。异常被吃掉，变成 `emptyList()`（`:6016`）。feed 缓冲 `teamChannelMessages` 只在「正在看的就是默认频道」时渲染（`:6019`）。连接状态却用 `teamChannelFeed.connectionState`（`:6045`），WS 连着就会显示「● 直播推送」。频道切换会把 feed 重绑到当前频道（`:6287`），推送进了缓冲，界面不用它。轮询失败时，用户看到空频道，而不是已收到的推送。
- 证据：`startTeamChannelFeed` 把非默认频道的 WS 消息 `add` 进 `teamChannelMessages`（`:6307`），`buildTeamChannelSnapshot` 在 `activeId != channelId` 时不读这个表。
- 严重度：阻塞

**阻塞** — iOS 旗舰页切换频道不重订 feed，旧订阅和旧消息留在新频道名下。

- 文件:行号：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:2663`、`:3344`、`:4777`
- 问题：`ChannelsHomeView` 在同一分支里换 `channelItem`，没有 `.id(频道 id)`。`@State feed` / `messages` 会留着。`startFeed` 在 `feed != nil` 时直接返回。`onAppear` 只在首次装载时 `loadWorkspaceData`。频道名会变成新的（`channelItem` 不是 `@State`），发送用新的 `currentChannelId`（`:4233`），列表和 WS 仍是上一个频道。
- 证据：`startFeed` 开头 `guard feed == nil`。全文件没有按 `channelItem.id` 调用 `stopFeed` 再订。
- 严重度：阻塞

### 信令零 token（投递、已读、唤醒不调模型）

已核、无发现（信令帧本身）。

- WS 的 hello / ping / 未知类型直接丢掉，不进模型（`ChannelFeed.kt:23`，`ChannelFeed.swift:238`）。
- 已读是 `pollMessages(..., markRead = true)`，只打平台 `mark_read`（`MainActivity.kt:6740`，`PlatformChannelTransport.kt:363`）。已读名单是 `agent_channel_readers`（`MainActivity.kt:6635`）。
- 频道通知档只写本机偏好 `notify_$channelId`（`MainActivity.kt:1710`），不发模型请求。
- 令牌只进 Authorization 头（`ChannelFeed.swift:15`、`:214`）。

收到带 @ 的频道消息后，安卓会再调模型。那是 ② 的执行路径，不是 ping/已读/唤醒帧。已读和唤醒信令本身没有模型调用。

---

## 3. 代码质量（状态不一致、双端不一致、测试假绿）

上面 ② 和实时两节里的阻塞项同时是状态/双端问题，这里不重复展开。下面是另外几条。

**一般** — iOS 定时消息先出队再发送，失败就丢；安卓失败留在队列里。

- 文件:行号：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:4180`；对照 `android/app/src/main/java/wherevercode/agent/MainActivity.kt:6559`
- 问题：iOS `flushScheduled` 先 `removeAll` 到期项并 `saveScheduled`，然后 `_ = try? await transport.postMessageEx`。失败无提示、队列里也没了。安卓 `catch` 后不从 `remaining` 删除，下次刷新用同一个 `effect_key` 再发。
- 证据：iOS 发送在删除并落盘之后，错误被 `try?` 吃掉。
- 严重度：一般

**一般** — 定时回复双端形状不一致。phase2 报告写 iOS `replyToId`「与安卓同形」，安卓数据类没有这个字段。

- 文件:行号：`android/app/src/main/java/wherevercode/agent/core/team/ChannelExtras.kt:243`；`ios/WhereverCodeAgent/Models/Models.swift:545`；安卓入队 `MainActivity.kt:6531` 不写回复目标
- 问题：正在回复时设定时，iOS 会带 `replyTo`（`FlagshipRouteDestinations.swift:4166`、`:4190`），安卓到期发送只有 `effectKey` 和 `attachmentRef`（`MainActivity.kt:6561`）。
- 证据：`ScheduledChannelPost` 安卓构造参数止于 `attachmentRef`。
- 严重度：一般

**一般** — iOS 旗舰页把平台消息一律当成「我 / Agent」的判断是错的，撤回和 @all 亮起因此失效。

- 文件:行号：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:4852`（`toV5Message`）；`:3850`；`:3776`
- 问题：`senderId` 取 `msg.agentId`，`isAgent` 恒为 `true`。撤回按钮只在 `senderId == "user-me"` 时出现。真实回帖的 id 不会是 `user-me`，本机发出的消息看不到撤回。@all「生效」chip 同样只认 `user-me`，自己发出的 @all 回推后会显示「未生效」。安卓用本机发帖学到的 `selfAgentIds`（`MainActivity.kt:6321`、`TeamChannelController.kt:923`）。
- 证据：`V5ChannelMessage` 初始化写死 `isAgent: true`。假消息（`:4701`）才用 `senderId: "user-me"`，所以只有伪造消息能撤回。
- 严重度：一般

**一般** — iOS 文件页把网盘写成已连接。注册表仍是 TODO。

- 文件:行号：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:4276`；`ios/WhereverCodeAgent/Data/EntryRegistry.swift:98`
- 问题：主频道「文件」分区四个徽章全部 `isOk: true`，文案「可用 · OAuth2」「已连接 · NAS」「已连接 · 私有」。没有读账号状态。`feature_channel_files` 的 todo 文案是「频道文件待开发」。
- 证据：`cloudDriveBadge` 的 status 是字符串字面量，不是传输结果。
- 严重度：一般

**一般** — 安卓 @all 门闩是死代码；iOS 门闩读的是假角色。

- 文件:行号：`android/app/src/main/java/wherevercode/agent/ui/TeamChannelController.kt:426`；`android/app/src/main/java/wherevercode/agent/MainActivity.kt:6057`；iOS `FlagshipRouteDestinations.swift:4048` 与 `:2402`
- 问题：安卓拦截条件是 `TeamVoice.COORDINATOR_ID` 不在 `trustedAttributionPosterIds`。快照每次都 `setOf(COORDINATOR_ID) + selfAgentIds`，本机发送永远过门。iOS 要求成员角色含「项目主管」或「协调」，而默认成员就是这个角色。两边都不是服务端 owner 裁决。服务端没有 @all 工具，这一点工作包写过；客户端门闩本身并不生效。
- 证据：特权集的第一项是常量 `"local-app"`，与发送者是否真是频道 owner 无关。
- 严重度：一般

**一般** — 名为频道协同的测试没有打到频道。

- 文件:行号：`ios/WhereverCodeAgentTests/ChannelCollaborationTests.swift:39`（`testChannelMultiAgentDispatchClaimReviewReworkLifecycle`）；`:5`（`testChannelMemberManagerPersistence`）
- 问题：前一个测试直接 `LocalTaskStore.createTask` / `transition`，不断言频道传输、不断言没有伪造消息、不断言执行器被调用。名字是「频道多 Agent 派发—认领—审查—返工」。后一个测试断言默认成员里有「Codex」，把 ② 的假成员锁成绿。
- 证据：测试文件没有 `ChannelTransport`、`postMessageEx`、`ChannelFeed`。断言全是本地任务状态和 kv 成员名。
- 严重度：一般

安卓 `ChannelExtrasTest` 的同一 `effect_key` 重试（失败抛错且键用两次）、`ChannelFeedTest` 的断线轮询、`TeamChannelControllerTest` 的「轮询兜底」文案，抽查和实现对得上。这些不记假绿。

---

## 4. 大脑红线

频道这一期没有把大脑挪到台前，也没有用大脑 prefs 冒充模型页的存储。

- 模型页列表只读 `modelConfigStore.listEntries()`（`MainActivity.kt:11927`）。注释写明不再读大脑存储（`:11918`）。
- `CompositeBrainConfigStore` 的 `save` / `delete` / `setCurrentBrainId` 只写大脑存储，不写模型存储（`android/.../models/CompositeBrainConfigStore.kt:37`；iOS `ModelRegistry.swift:553`）。
- phase2 摘取报告称该 diff 的 `brain` / `BrainKind` / `大脑` 为 0。频道发送、已读、feed 路径没有模型存储读写。

**建议** — 启动时仍从大脑存储往模型存储补条目。这不是「把大脑文件当成模型文件读」，但是模型列表的新增还可以来自大脑存储。

- 文件:行号：`android/app/src/main/java/wherevercode/agent/models/ModelStoreMigration.kt:54`（`topUp`，每次启动）；iOS `ios/WhereverCodeAgent/Models/ModelRegistry.swift:628`
- 问题：`MODEL_API` / `AGENT_RUNTIME` / `SUBSCRIPTION` 若 id 不在模型存储、也不在墓碑里，就复制进模型存储。已有条目不覆盖。模型页展示的是复制后的模型存储。若红线要求模型存储不再吃大脑存储，这条还连着。频道功能不依赖它，不作为本期阻塞。
- 证据：`topUp` 在 `isMigrationV1Done` 判断之外，每次 `run` 都执行。
- 严重度：建议

大脑红线（冒充独立存储、把大脑挪到台前）：已核、无发现。

---

## 总判

| 项 | 结论 |
| --- | --- |
| ① 对话框复用 | 主路径已核。旧 `ChannelChatView` 未复用（一般） |
| ② 频道不执行代码 | 未通过。安卓 @ / 扇出走 `runAgent`；iOS 频道内改任务状态并伪造 Agent 发言；iOS 假成员（阻塞） |
| ③ 可以派发 | 安卓已核。iOS 只建本地排队任务（一般） |
| ④ 对照工作包齐全 | 管理、文案、4000、幂等键、注册表不假翻已核。齐套未达到（@我、@all、文件/私聊仍是包内尾巴） |
| 推送为主、轮询不冒充 | 默认频道与 iOS feed 文案已核。安卓非默认频道列表以轮询为准却可标直播（阻塞） |
| 切频道订阅 | iOS 旗舰页切换不重订（阻塞） |
| 信令零 token | 已核、无发现 |
| 大脑红线 | 已核、无发现。启动补齐为建议 |

合 main 前至少要处理的阻塞项：安卓 @ 中转和扇出不再调用可执行的 `runAgent`（或从结构上关掉工具，而不是靠提示词）；iOS 去掉频道内认领/批准/返工和伪造消息，成员改读平台真实名单；安卓非默认频道的列表改吃当前 feed 缓冲，轮询失败不得画成空频道；iOS 切换频道必须停掉旧 feed 再按新 id 订阅。

CHANNEL-P1-REVIEW-DONE
