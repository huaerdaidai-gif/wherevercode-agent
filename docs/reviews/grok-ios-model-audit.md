# iOS 模型页数据源与大脑分节核查

审查人：Grok。只看代码和 git 历史。未装机，未跑模拟器，未做真机复验。

核对的 main 头：`b640601`（`b640601bde72c1984138604992aa1c0596329d90`）。克隆后 `git rev-parse HEAD` 与指定头一致。iOS 三件套在 `24522af`、`3a6ee64`、`408f59f`，由 `b640601` 合入；这三笔的基线是 `75c7218`。

## 1. 「已添加的模型」数据源：借大脑的

列表读的是大脑配置注册表 `BrainConfigStore`，不是另一套模型注册表。标题是源码里的固定字符串，进出页面时没有把同一只标题改写再写回。

证据链：

1. 列表视图是 `BrainSettingsView.addedModelsSection`（`ios/WhereverCodeAgent/Views/BrainSettingsView.swift`）。`Section("已添加的模型")` 在约 376–412 行。行组件是 `BrainRowView`（约 460 行），行类型是 `BrainConfig`，标识是 `brainId`。
2. 数据从 `BrainSettingsModel.reload()` 来（约 44–47 行）：`configs = configStore.listConfigs()`，`currentBrainId = configStore.currentBrainId()`。分组函数 `modelApis` / `localModels` / `cliBrains` / `subscriptions`（约 363–374 行）只是在这份数组上按 `BrainKind` 过滤：`MODEL_API`、`AGENT_RUNTIME`（界面写成「CLI 会员」）、`SUBSCRIPTION`（界面写成「订阅授权」）。`BrainKind` 定义在 `BrainTypes.swift` 约 26–31 行，同枚举里还有 `PLATFORM_AGENT`、`CLOUD_AGENT`，列表分组没有这两类，但它们若写在同一份数组里，来源仍是这一份。
3. 生产存储是 `KeyValueBrainConfigStore`（`BrainConfig.swift` 约 351–373 行）。键名：`brain.configs_json`、`brain.current_brain_id`、`brain.deleted_brain_ids_json`（约 355–357 行）。`listConfigs()` 把 `brain.configs_json` 整段解析成 `[BrainConfig]`，结构字段是 `brainId`、`kind`、`adapterId`、`label`、`model`、`authRef`、`fallbackBrainIds`（`BrainConfig` 约 27–45 行）。iOS 树里没有 `ModelRegistry`、没有 `models.configs` 这类另一套键。
4. 接线在 `AppState.init`（`WhereverCodeAgentApp.swift` 约 82–93 行）：`KeyValueBrainConfigStore(kv: data.kv)` 交给 `BrainSettingsModel`。添加走 `createGenericBrain` / `addModelConfig`（`BrainSettingsView.swift` 约 118–170 行），仍是 `configStore.saveConfig`。设当前走 `setCurrentBrainId`（约 72–79 行）。
5. 进出页不改标题、不换数据源。`BrainSettingsView` 的 `.navigationTitle("模型")` 和 `Section("已添加的模型")` 是字面量。`.onAppear` 只调用 `model.reload()`（约 237–238 行）。文件里没有 `onDisappear`，也没有安卓那种按进入/离开改写同一只 `TextView` 的函数。
6. 这只视图在三件套之前就是大脑列表。`75c7218` 的 `BrainSettingsView.body` 用 `ForEach(model.configs, id: \.brainId)`，header 文案是「大脑列表」，`reload()` 同样是 `configStore.listConfigs()`。`24522af` 把 header 改成 `Section("已添加的模型")`，存储键和 `reload()` 没换成新表。

## 2. 设置里的大脑分节：标题被改过，位置没搬，预设块没有插进设置表单

对比范围：`75c7218`（iOS 三件套分支基线）到 `b640601`。改标题的提交是 `24522af`。`3a6ee64`、`408f59f` 没有再改 `SettingsView.swift`。

两处「设置」要分开看。

抽屉头像打开的设置页是 `AccountSettingsView`（`FlagshipShellView` 的 sheet，`FlagshipApp.swift` 约 610–624 行；抽屉按钮 `onOpenSettings` 约 418–448 行）。这个结构体在 `75c7218` 与 `b640601` 之间整段相同（224 行）。分节仍是「平台连接」「账户」「应用」，外加开发者选项。`Section("应用")` 第一行仍是 `NavigationLink` 到 `BrainSettingsView`，标签「模型配置」（`FlagshipRouteDestinations.swift` 约 5108–5114 行）。这里没有名为「大脑」的分节，也没有预设块、智能路由块插进来。

另一张表单是 `SettingsView`（`SettingsView.swift`）。`75c7218` 第一段是 `Section("大脑")`，行文案「管理大脑」，说明句以「大脑是可替换的推理后端」开头（当时约 106–117 行）。`24522af` 把这三处字符串改成 `Section("模型")`、「管理模型」、「模型是可替换的推理后端」（现在约 106–117 行）。分节顺序没变，后面仍是「平台连接」「平台令牌」「连接状态」「账号与外部连接」「关于」。diff 只有这三处文案。关于段（约 256 行）仍写着「大脑管理」「设当前大脑」，没有改。`currentBrainSummary`（约 350–356 行）注释仍写「大脑 section row」，读的仍是 `brainConfigStore.currentBrainId()`。这张表单里没有新增预设分节。

它的出现位置：路由 `connections` 直接渲染 `SettingsView`（`FlagshipRouteDestinationView.routeContent`，约 111–112 行），页标题是该视图自己的「设置」。账号设置里的「平台连接与令牌签发」包了一层 `PlatformConnectionFormScreen`（约 5262–5268 行），里面仍是这张 `SettingsView`，外层标题改成「平台连接」。两条路看到的第一段都是被改名后的那一节。

预设和智能路由没有从设置表单的别处搬到大脑分节旁边。iOS 设置表单没有「大脑名称」行，也没有「高级配置」容器。这两块是 `24522af` 写进 `BrainSettingsView` 的：`addSection` 的 `Section("从预设添加模型")`（约 271–315 行），`routingSection` 的 `Section("智能路由")`（约 320–358 行），放在「已添加的模型」上面。`75c7218` 的同一视图顺序是「大脑列表」「添加大脑」「恢复内置大脑」「连接方式与验证」「模型策略」，导航标题「模型与提供方」。`24522af` 换成现在的五段，导航标题「模型」。

功能中心条目没被这次改名带走。`EntryRegistry` 的 `feature_brain` 仍是标题「大脑」、`routeId: "models"`（`EntryRegistry.swift` 约 104 行）。`75c7218` 与现在这一行相同。`408f59f` 只把 `feature_model_routing` 的状态从待开发改成已实现。

## 3. 控制台没有「模型」卡；模型页从设置、对话选择器和功能中心进

控制页是抽屉「控制」→ `FlagshipShellView` 的 `selectedMenu == .control` → `ControlHomeView`（`FlagshipApp.swift` 约 572–580 行；一级菜单 `FlagshipDrawerMenu` 只有对话、项目、频道、控制，约 4–8 行）。

卡片列表在 `ControlHomeView.controlModulesList`（`FlagshipRouteDestinations.swift` 约 4832–4905 行）。现有 9 张，标识是 `control.card.\(routeId)`（约 4989 行）：

| 标题 | routeId | 角标来源 |
| --- | --- | --- |
| 设备 | `devices` | `agents` 里 `kind == .device` 的条数 |
| Agent | `agents` | `listAgents()` 条数 |
| MCP | `mcp` | 有无平台 Token |
| Webhook | `automation` | 固定文案「触发与唤醒」 |
| 任务 | `queue` | 待审批数或队列数 |
| 事件 | `activity` | 固定文案「动态」 |
| 用量 | `usage` | 固定文案「用量」 |
| 安全权限与操作审计 | `audit` | 固定文案「审计」 |
| 检查点、恢复与扩展中心 | `extensions` | 固定文案「扩展」 |

没有标题「模型」，也没有 `routeId: "models"`，因此不会生成 `control.card.models`。角标参数只有 `devicesCount`、`agentsCount`、`queuedCount`、`pendingApprovals`，不读 `BrainConfigStore.listConfigs()`。

`75c7218` 的 `controlModulesList` 已是这 9 个 routeId，没有模型卡。`24522af` 把前七张的长标题改短（例如「设备管理与配对」→「设备」），最后两张标题未改，没有加模型卡。

模型页实际入口（都落到同一个 `BrainSettingsView(model: appState.brainSettingsModel)`）：

1. 抽屉底部头像 → `AccountSettingsView` →「应用」→「模型配置」。这是 sheet 内的 `NavigationLink`，不经过路由 `models`。
2. 同一设置里「平台连接与令牌签发」，或路由 `connections`，打开 `SettingsView` →「模型」→「管理模型」。
3. 路由 `models`（以及 `connectmodel`、`modelroute`）在 `FlagshipRouteDestinationView` 约 105–106 行直接给出 `BrainSettingsView`。调用点：对话底部选择器 `ModelAndAgentSheet` 的按钮「管理模型与连接」（`LocalData.swift` 约 2018 行）经 `ChatComposerBar` 的 `onSelectRoute("models")`（约 2144–2147 行）；首次引导 `OnboardingLocalFirstScreen` 的「配置推理模型」（`FlagshipRouteDestinations.swift` 约 5365 行）；功能中心 `FeatureCenterSheet` 点已实现条目 `feature_brain`（标题仍是「大脑」）走 `routeId` `models`（`FlagshipApp.swift` 约 774–779 行，`EntryRegistry.swift` 约 104 行）；开发者选项里的 70 屏列表可点 `diagnostics.route.models`。
4. `WhereverCodeAgentUITests.navigate` 在目的地为 `models` 时会先找 `control.card.models`（约 153–160 行）。卡片列表不创建这个标识，测试代码随后改走设置里的开发者诊断。这只说明测试在找一张控制页上不存在的卡，本次没有跑这条 UI 测试。

路由表 `FlagshipRoutes` 里有 `id: "models"`、标题「模型」，父级是项目、分组「连接」（`FlagshipApp.swift` 约 107 行）。`24522af` 把标题从「模型与提供方」改成「模型」。这是路由注册，不是控制页卡片。

## 与安卓侧发现的对照

安卓结论以同一头 `b640601` 上的代码核对，并对照分支 `review/grok-197-review-20261119` 的 `docs/reviews/grok-197-review.md`。

已添加列表借大脑注册表：iOS 也有。安卓 `MainActivity.refreshBrainList`（约 10794 行）用 `BrainLoginRows.buildRows(configs = configStore.listConfigs(), …)` 画「已添加」列表；`configureModelsMode(true)`（约 1868–1900 行）把同一只 `brainListTitle` 写成「已添加的模型（点按一行切换）」，离开写回「已添加的大脑（点按一行切换）」（约 1911 行）。iOS 列表同样来自 `configStore.listConfigs()` 和 `brain.configs_json`，视图类型仍是 `BrainSettingsView` / `BrainRowView`。差别是 iOS 标题在 `24522af` 里写成固定的「已添加的模型」，没有进出页改写再写回。

控制台模型卡用大脑条数当角标：iOS 没有这张卡，也就没有这只角标。安卓 `ConsoleScreenController` 在设备、Agent 之后有标题「模型」的卡（约 394–401 行），`MainActivity` 把 `modelsCount` 设为 `brainComponents?.configStore?.listConfigs()?.size`（约 1558–1559 行）。iOS `controlModulesList` 的 9 张卡里没有模型，角标不读大脑配置条数。

设置页预设块搬家：iOS 没有安卓这一种布局残留。安卓 `screen_settings.xml` 里 `brainNameRow` 文案「大脑名称」（约 249 行）下面就是 `presetModelBlock`「从服务商预设添加模型」（约 267–276 行），再下面才是 `buttonBrainAdvanced`「高级配置」（约 368–376 行）。`brainSectionTitle` 仍是「大脑」（约 80–84 行），`brainListTitle` 静态文案仍是「已添加的大脑」（约 172 行）。iOS 的 `AccountSettingsView` 相对 `75c7218` 没有改分节。`SettingsView` 被 `24522af` 改过的是第一段标题「大脑」→「模型」，顺序未动，表单里没有插入预设块。预设和智能路由是加在原来的大脑管理页 `BrainSettingsView` 顶部，不是安卓那种同一张设置布局里把预设块留在「大脑名称」和「高级配置」之间。

功能中心仍有一条标题为「大脑」的已实现入口，点进去就是模型页路由 `models`。这一行不是三件套新改的，`75c7218` 已经如此。
