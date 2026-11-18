# 197 审查：抽屉搜索框 + 模型页结构

审查人：Grok。只看代码，对照 196 小米 12 真机实证（2026-11-19 03:30–03:45）。未装机、未真机复验。

## 结论

分支头与指定一致：`1791831`（`17918310374ae31d0f1071122d3044443f8fac30`）。搜索框这一笔只改 `DrawerView.kt`，`+53/−13`。

- 重影：**仍有风险**。逐键重挂已从打字路径拿掉，但搜索框并没有「永不重挂」。占位文字在不打字时的双重绘制，代码保证不了消失。
- 吞字（逐键重建）：**代码上成立**。打一个字不再整抽屉 `refresh()`，搜索框不会被这次重建摘下。
- 副作用：逐键不丢焦点、不丢光标、不重复注册监听，抽屉重开也不清搜索词。打开抽屉后的异步 `refresh()` 仍会摘掉搜索框，这时打字仍可能丢焦点、吞字。
- 模型页：**不能算与设备页 / Agent 页同口径**。控制台有「模型」卡，点进去是「添加区在上、列表在下」，但添加区不是一颗添加按钮，列表仍是大脑列表。
- 大脑分节：**标题文案相对 main `75c7218c` 没有改名，也没有把「大脑」放进控制台。不能说完全没被碰。**
- 总评：**可以交给 Muse 装机复验。不要当成已经修好。**

## 1. 搜索框（`1791831`）

196 的两条机制：界面树里搜索 `EditText` 只有一个，这一只被整体画了两遍（占位文字和输入正文都有双影）；每打一个字就整抽屉重建并重挂这只 `EditText`，快打「AGY」只剩「A」。

修之前（父提交 `17a09af`）已经复用同一只 `EditText`，但 `TextWatcher.afterTextChanged` 仍调用 `refresh()`。`refresh()` 第一行是 `content.removeAllViews()`，然后新建一只搜索框，再把这只 `EditText` `removeView` 后 `addView` 进去。所以树里始终只有一只输入框，却每个字都脱离窗口再挂上。这和 196「不是两个视图、但是重挂」对得上。

### 重影：仍有风险

打字这条路上，重挂已经切断：`buildSearchBox()` 只在 `searchBoxView == null` 时建一次；`afterTextChanged` 改为 `updateSessionRowsOnly()`。会话行画在 `sessionListContainer` 里，搜索框是它的兄弟，不在这个容器里。逐键不会再 `removeView` 搜索框。

这还没有消除 196 的双重绘制：

1. `refresh()`（`DrawerView.kt` `refresh`）仍以 `content.removeAllViews()` 开头。搜索框是 `content` 的子视图，这一行会把它连同里面的 `EditText` 从窗口摘下。随后 `addRecentConversationsSection()` 再 `content.addView(searchBox)`。注释写「不再重挂」，窗口层面仍会重挂。调用点在 `MainActivity.openDrawer()`、`refreshSessionList()` 回到主线程时的 `drawerView?.refresh()`、`setBadges()`（`refreshDrawerBadges()`）。
2. 196 的占位文字双影在还没打字时就能看见。逐键重挂解释不了「一个字都没打、占位文字已经是双的」。
3. 抽屉不是 `DrawerLayout`。`drawerContainer` 有 `android:elevation="16dp"`，打开时 `translationX` 动画 220ms（`activity_main.xml`、`MainActivity.openDrawer`）。`EditText.setLayerType(LAYER_TYPE_SOFTWARE)` 是针对硬件层缓存的缓解，代码路径上没有把这层合成关掉。软件层能不能让小米把这一只 `EditText` 只画一遍，这里证明不了。

### 吞字：代码上成立

196 的吞字机制是：每个字 → `refresh()` → 输入框脱离窗口 → 输入连接被拆掉 → 后面的字进不去。

现在 `onTextChanged` 只写 `searchQuery`，`afterTextChanged` 只调 `updateSessionRowsOnly()` → `renderSessionRows()` → `sessionListContainer.removeAllViews()`。`recentSessionsProvider` 在 `MainActivity.bindDrawer` 里是 `{ cachedRecentSessions }`，读列表不会再调 `drawerView.refresh()`。逐键重建不再波及搜索框，输入连接不会被这次击键拆掉。

### 副作用

- 焦点，逐键：**代码上成立**，这次更新不碰 `EditText`，也不给新行要焦点。
- 焦点，其它刷新：**仍有风险**。上面三条 `refresh()` 会 `removeAllViews()`。打开抽屉后会话列表和角标是后台线程回来再刷的。用户已经开始打字时，这次刷新会摘掉焦点。
- 输入状态 / 光标，逐键：**代码上成立**。同一只 `EditText`，`Editable` 没有被换掉，兄弟容器重建不清光标。
- 输入状态，异步 `refresh()`：**仍有风险**。同一只控件，正文一般还在；脱离窗口会丢掉正在组合的输入。这和 196 的逐键吞字不是同一条路径，但快打开抽屉就打字时仍可能吞字。
- 抽屉重开后搜索词：**代码上成立**。关掉抽屉只把 `drawerContainer` 设为 `GONE`，不销毁 `DrawerView`。`searchQuery` 和 `EditText` 正文都留在原对象上，再次 `openDrawer()` 的 `refresh()` 复用 `searchBoxView`，没有 `setText("")`。
- 监听：**代码上成立**。`TextWatcher` 只在 `buildSearchBox()` 里 `addTextChangedListener` 一次。`searchBoxView` 之后不再置空，`refresh()` 不会再加一个监听。
- `updateSessionRowsOnly()` 只替换 `renderedRowList` 里 `group == "recent"` 的一段；没有这段时插到 `group == "bottom"` 之前。会话行在 `refresh()` 里是连续的一段，这个拼接对得上。它不碰搜索框。

## 2. 模型页（`17a09af`，并核对分支相对 main `75c7218c`）

控制台卡片顺序是设备、Agent、模型、MCP（`ConsoleScreenController` 控制卡一段）。「模型」卡点击 `onOpenModels` → `showEntry(ID_MODELS)`。`DrawerRouter` 把 `models` 送到 `EntryScreen.MODELS`。`ID_MODELS` 不在 `DEFAULT_ORDER` 里。角标用 `configStore.listConfigs().size`，也就是大脑配置条数，写成「N 个模型」。

`configureModelsMode(true)` 把 `presetModelBlock`、`buttonBrainAdvanced`、`brainAdvancedContainer` 挪到 `brainListTitle` 前面，页头改成「模型」，列表标题改成「已添加的模型（点按一行切换）」。隐藏跳转条、大脑标题、状态卡、换号行、大脑名称、SIWC 模型行，以及高级配置容器之后的分节。离开时按记下的顺序和可见性放回去，标题恢复「设置」和「已添加的大脑（点按一行切换）」。

### 与设备 / Agent 同口径：不能算

设备页（`AgentScreenController.renderDevices`）：上面一颗「＋ 添加设备」（附近设备算添加流程），下面「已添加设备」。

Agent 页（`screen_agents.xml` 的「＋ 添加 Agent」+ `renderAddedAgents`）：上面一颗添加按钮，下面「已添加的 Agent」。列表里没有第二颗添加按钮。

模型页看得见的是四截，不是这两页那种两块：

1. 页头「模型」。
2. 预设块：服务商预设、自动发现、智能路由勾选、路由池。路由是开关，不是添加。
3. 「高级配置」按钮。点「＋ 添加模型…」会把 `brainAdvancedContainer` 展开（`addGenericBrain`），表单在列表上面。提示文案仍写「在下方通用表单里填」。
4. 「已添加的模型」下面仍是 `refreshBrainList()` / `BrainLoginRows`：点行切换当前大脑，末尾有「＋ 添加模型…」「＋ 重新添加 …」和分流小模型行。

所以「添加相关的块在列表上面」这一层顺序有了。块的内容和设备页、Agent 页不是同一套：添加不只是一颗按钮，列表也不是单独的已添加模型列表。

### 大脑分节：文案没改名，不是完全没被碰

对照 main `75c7218c`：

- `BrainLoginRows.kt` 以及 `android/app/src/main/java/wherevercode/agent/brain/` 无差异。`e5e23f0` 曾把大脑改成模型，`fa345a3` 已撤回。设置页 XML 里仍是「大脑」「大脑：加载中…」「已添加的大脑（点按一行切换）」「大脑名称」。
- 控制台卡片标题是「模型」，没有一张「大脑」卡。模型页里大脑标题、状态卡、大脑名称被隐藏。
- 设置页布局被挪过。相对 main，`e5e23f0` 把「从服务商预设添加模型」整块从「高级配置」容器之后搬到「大脑名称」和「高级配置」之间；`fa345a3` 只撤回改名，没有把这块搬回去。不开模型页、直接开设置，这块也比 main 更靠前。
- `17a09af` 给大脑标题、状态卡、列表标题等加了 id，文本没改。进入模型页时，同一只 `brainListTitle` 会被写成「已添加的模型」，离开才写回。大脑列表本体出现在控制台「模型」入口后面。

「不许改名」对设置页上的「大脑」标题成立。「完全没被碰」和「不许挪到台前」不成立：设置页顺序变了，大脑列表被模型页改标题后拿到控制菜单下。

## 总评

可以装机复验。复验前不要把 197 当成已通过。

Muse 真机请看这几件：

1. 抽屉停稳、还没打字时，占位文字是否还有双影。
2. 慢速、快速各打一串（含「AGY」），字是否都在，焦点和光标是否还在。
3. 打开抽屉后马上打字（会话列表和角标还在后台刷新时），字会不会被吃掉、键盘会不会收起。
4. 关上再打开，搜索词是否还在。
5. 控制台「模型」卡进去：上面是不是预设/发现/路由加「高级配置」，下面是不是大脑列表改了标题；再回设置页，「大脑」标题和原来的分节是否回来，预设块是否仍比 main 更靠前。
