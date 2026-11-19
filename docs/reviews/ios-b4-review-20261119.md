# 独立只读复核：iOS B4 @ 5cbc8df9

- 仓库：`huaerdaidai-gif/wherevercode-agent`
- 基线：`f3dfe06c9833ab1c2ba125f1c0547b6938a2e7c7`
- 被审：`feat/ios-b4-20261119` @ `5cbc8df98f779eb1443c5a70a340c4ef956eaf95`
- 范围：`f3dfe06c..5cbc8df9`（`6562824` 界面、`60b2796` 测试、`952ca82` 设备迁移过滤、`5cbc8df` 测试）
- 方式：只读 diff，不改被审代码
- 总评：**不可合并，阻断 1**

`ios/WhereverCodeAgent/Brain/**`、`ios/WhereverCodeAgent/Views/BrainSettingsView.swift`、`ios/WhereverCodeAgent/Views/SettingsView.swift`、安卓 `brain/**` 相对基线零差异。

## 逐条

### ① 差异1 抽屉搜索词关后保留 — 通过

搜索词从抽屉内部 `@State` 抬到壳层，关抽屉只改 `isOpen`，不清词。

- `FlagshipApp.swift:524` `@State private var drawerSearchQuery`
- `FlagshipApp.swift:663-666` 以 Binding 传入 `FlagshipDrawerView`
- `FlagshipApp.swift:215,227,236` 抽屉只持有绑定
- 清空只发生在搜索框旁按钮（`FlagshipApp.swift:331-333`）
- 关抽屉的 `isOpen = false`（如 `267` 的 `drawer.close`）不写 `searchChatQuery`
- UI 测试 `WhereverCodeAgentUITests.swift` `testDrawerSearchPersistsAfterClosing` 关开后断言原词还在

### ② 差异3 控制台移出 2 卡、路由保留、对齐安卓 8 卡 — 通过

`FlagshipRouteDestinations.swift` `controlModulesList`（4955-5017）现为 8 张，标题与副标题对齐安卓 `ConsoleScreenController.kt:378-441`：设备、Agent、模型、MCP、Webhook、任务、事件、用量。

移出的两张不再在控制台：

- 安全权限与操作审计（原 `routeId: "audit"`）
- 检查点、恢复与扩展中心（原 `routeId: "extensions"`）

路由仍在。功能中心「控制与安全」：`FlagshipApp.swift:779-783` `openRoute("audit")` / `openRoute("extensions")`。目的地仍在：`FlagshipRouteDestinations.swift:123` 含 `"audit"`，`129` 含 `"extensions"`，`5509`、`5686` 有对应 case。

非阻断：安卓用量卡角标是「待开发」并 toast（`ConsoleScreenController.kt:435-440`）；iOS 用量卡角标仍是「用量」且点击走 `onNavigateRoute("usage")`（`5011-5016`）。8 张卡本身对齐，不单列阻断。

### ③ 差异7 Webhook「待开发」角标 + 诚实提示 — 通过

- 角标 `FlagshipRouteDestinations.swift:4991` `badge: "待开发"`
- 点击不导航：`4993` `showWebhookNotice = true`
- 弹窗 `4859` 标题「【待开发】Webhook 尚未开发」，按钮「我知道了」
- UI 测试点 `control.card.automation` 后断言该 alert

### ④ 差异10 模型行「自定义」胶囊 — 通过

`ModelSettingsView.swift:259-264` `isCustomModel`：`modelId` 以 `custom-` 开头，或 `extra` 预设 id 以 `custom-` 开头，或 `extra["model_origin"] == "manual"`。行内胶囊文案是「自定义」（`220-226`）。手工添加写入 `model_origin: manual`（约 `61-66`）。测试 `ModelDecoupleTests.swift` `testCustomModelBadgeUsesProviderPrefixOrManualOrigin`。

### ⑤ 差异11 已添加三分组 / 本地并回 / 去掉「测试当前连接」/ 组底重新添加 — 不通过

模型页这三项落地：

- 严格三分组，空组也保留：`ModelSettingsView.swift:176-178`「模型 API」「CLI 会员」「订阅授权」
- 本地模型没有第四组。`ModelRegistry.swift:417-425`（本分支未改）`modelApis` 含全部 `kind == .modelApi`，本地地址是这个 kind 的子集（`423-425`），出现在「模型 API」里
- 组底「＋ 重新添加」：`ModelSettingsView.swift:196-206`，在该组条目 `ForEach` 之后，文案 `＋ 重新添加 \(seed.label)`。`saveConfig` 不看墓碑（`ModelRegistry.swift:146-149`），保存成功后再 `clearModelDeleted`

阻断：独立分节「测试当前连接」还在。

`BrainSettingsView.swift:224-235` 仍是 `Section("测试当前连接")`。该文件相对 `f3dfe06c` 零差异。设置页「管理大脑」仍导航到这个页（`SettingsView.swift:106-108`）。模型页 `ModelSettingsView` 没有这个标题，但编辑 sheet 里新加了一节「测试连接」（`310-321`），没有删掉设置路径上的独立分节。

### ⑥ 差异12 删模型确认文案 — 通过

`ModelSettingsView.swift:347`：

`确定删除「\(config.label)」吗？只删这一条模型配置，不删 Agent、不删设备。`

含要求的句子。

### ⑦ 差异15 设置大脑分节未改、feature_brain 指向模型页 — 通过

- `SettingsView.swift` 相对基线零差异。大脑分节仍是 `106-120`：`Section("大脑")`、`管理大脑`、原说明段落
- `feature_brain` 在基线与被审都是 `EntryRegistry.swift:104` `routeId: "models"`
- `"models"` 落到独立模型页：`FlagshipRouteDestinations.swift:105-106` `ModelSettingsView`

### ⑧ 差异17 频道标题、快捷入口收起、私聊待开发 — 通过

- 顶栏标题 `FlagshipRouteDestinations.swift:2701` `Text("频道")`（原「频道与协作」）
- 抽屉路由名 `FlagshipApp.swift:100` `title: "频道"`
- 顶栏下横向快捷行（团队空间 / Agent 列表 / 协作编排 / 可视化编排 / 跨设备接力）已删除，`2626-2628` 顶栏后直接是分区选择器
- 分区文案 `2746`：私聊显示「私聊 · 待开发」
- 「发起私聊」走诚实弹窗 `2716`「【待开发】私聊服务待开发…」

非阻断：选中私聊分区仍渲染 `directMessagesListView`（`2637-2638`），不是整页拦住。标「待开发」这一条已经落地。

### ⑨ N3 附近设备并入「＋添加设备」 — 通过

设备页「添加设备」区不再列附近设备（`DevicesView.swift:99-123` 只剩「＋ 添加设备」和安装 Hand）。附近设备在添加 sheet 内：`411` `Section("附近的设备")`，`401` `onAppear` 触发扫描，`419`「重新扫描」。点「＋ 添加设备」（`101-102`）才进入这条流程。

### ⑩ N4 对话首页顶栏下上下文条 — 通过

顶栏注释与按钮行在 `LocalData.swift:1133-1193`。紧接着是 `1195-1203` `Text(connectionSummaryText)`，标识 `chat.connectionSummary`。文案 `1113-1122` 来自真实连接状态（已连接 / 检查中 / 未配置 / 失败 / 未检查），不编数字。

### ⑪ N7 控制台底部手动刷新 + 诚实说明 — 通过

8 张卡和「运行脉络」之后：`FlagshipRouteDestinations.swift:4844-4852` 按钮「刷新」（`control.refresh`）和「数据全部来自本机真实记录；没有数据就明说暂无，不编数字。」`reloadLocalRecords`（`4863` 一带）重读任务、Agent、模型数、待审批。`4858` 进入页面也会读一次。空脉络写「暂无运行中的任务与活动脉络」（`5030`）。

### ⑫ N8 选择器去掉「管理模型与连接」、补智能建议、残留清零 — 通过

- `LocalData.swift:1989` `Section("智能建议")`，理由三条：同项目已连接设备、默认 Agent、唯一候选（`1970-1982`）
- `2050` 按钮改为「管理 Agent」，不再有「管理模型与连接」。全仓 `git grep` 无「管理模型与连接」
- 全仓 `git grep "选择模型 / Agent"` 在 `5cbc8df9` 为零。iOS 回落 `2076` `return "选择对话方"`，导航标题 `2055`。安卓同步改了 `MainActivity.kt` 选择器标题与 `screen_chat.xml` 默认按钮，以及两处测试

行号与任务书不一致，不单列阻断：`LocalData.swift:2044` 是 `}`，不是「选择对话方」。该文案在 `2055` 与 `2076`。插入智能建议节后，原回落行从约 2041 移到 2076。

## 红线

- SettingsView 大脑分节：通过。`SettingsView.swift` 零差异，分节仍为 `106-120`
- brain 核心零差异：通过。`ios/WhereverCodeAgent/Brain/**` 与安卓 `wherevercode/agent/brain/**` 零差异

## 阻断项

1. 「测试当前连接」独立分节未删，仍在 `BrainSettingsView.swift:225`，设置「管理大脑」（`SettingsView.swift:107-108`）仍进这个页。

其余 11 项与两条红线通过，不抵消这一条。未合并。

非清单改动（不升为阻断）：`AgentRegistryStore.swift` 删 agy 设备宿主时多认一类「kind=device 且无 deviceId」的旧行；`HandClientTests.swift` 改为比较删除前后的持久化 Agent。
