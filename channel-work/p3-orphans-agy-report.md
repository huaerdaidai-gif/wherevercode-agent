# 频道孤儿项补全包（P3-Orphans）执行报告

- **执行者**：AGY（2026-11-21 Muse 派）
- **基线分支**：`feat/channel-phase1-20261120`，基线头 `18f4c6ec03f82111a938348c1eee0672c796d532`（经 `git ls-remote` 核对一致）
- **交付分支**：`feat/channel-p3-orphans-agy-20261121`（从一期基线头切出，已推送远程）
- **最新代码提交**：`8b06512a67f0e62805077ccaea462dcbaf1e32c9`
- **范围依据**：LeChat《频道孤儿项核对报告》＋ Muse 2026-11-21 现头复核纠偏通知。

---

## 一、现头复核结论与范围纠偏

1. **逐频道免打扰／通知设置**：
   - **安卓现头复核**：`TeamChannelController.kt` 与 `MainActivity.kt` 已具备三档通知选择（`全部` / `仅@我` / `免打扰`），本地通过 `team_channel_prefs` 按 `notify_$channelId` 键持久化，无需重复开发。
   - **iOS 现头复核**：此前仅有布尔型 `isMuted` 开关，缺「仅@我」档。
   - **实施范围**：安卓保持现状不重做；iOS 端补齐 `ChannelNotifyMode` 三档枚举（全部消息／仅@我／静音），通过 `UserDefaults` 针对每个 `channelId` 独立持久化，在频道设置表单提供分段控制器，并与消息中心未读聚合口径对齐。
2. **@所有人／广播提及**：
   - 现头双端均已具备客户端提及与 owner/协调者门闩渲染，服务端广播语义由 Funk 另定，不在本包范围内。
3. **Markdown／富文本容错渲染**：
   - 双端统一实现粗体、斜体、行内代码、围栏代码块、链接、引用、无序列表的渲染。
   - 容错要求：解析或渲染异常时必须无损回退纯文本，绝不崩溃、绝不吞字、绝不截断内容。
4. **自由话题线程**：
   - 在现有一期 `TeamThread` 模型上扩展，复用同一数据模型与视图层机制：支持从任意消息点击「话题」发起串内讨论，输入栏支持话题回复横条，频道顶栏提供话题列表弹窗与未读回复聚合，支持聚焦单个话题串浏览与点此返回全部。
5. **输入中提示**：
   - 端点 WebSocket 接收层增加 `{"type":"typing", ...}` 帧解析，零 Token 信令设计，不调用大模型。
   - 双端在消息列表与输入栏之间展示「×× 正在输入…」动态横条。

---

## 二、双端具体实现清单

### 1. Android 端
- `android/app/src/main/java/wherevercode/agent/core/team/TeamChannel.kt`:
  - 扩展 `TeamThread` 模型字段：`rootMessageId`、`topicTitle`、`isTopicThread`；
  - 实现自由话题线程成串函数 `groupTopicThreads(messages)`，按话题最新回复时间倒序排列；
  - 增强 `teamThreadTitle(thread)`，自动提取根消息前 30 字作为自由话题标题；
  - 话题成串溯源递归采用空安全守护（`val parent = msgMap[curr] ?: break`），杜绝空指针异常。
- `android/app/src/main/java/wherevercode/agent/ui/ChannelMarkdownRenderer.kt`:
  - 封装轻量级容错 Markdown 渲染器，调用 `MarkdownLite.parse` 解析并构建 `SpannableStringBuilder`；
  - 统一应用 `StyleSpan`（粗体、斜体）、`TypefaceSpan("monospace")`、`RelativeSizeSpan` 与 `URLSpan`；
  - 外部 `runCatching` 兜底，遇异常原样返回纯文本 `text`。
- `android/app/src/main/java/wherevercode/agent/ui/TeamChannelController.kt`:
  - 消息行接入 `ChannelMarkdownRenderer.render(message.text)` 与超链接点击激活；
  - 消息动作栏增加「话题」Chip 按钮，点击进入话题回复；
  - 频道头部工具行增加「话题(N)」按钮与自由话题弹窗列表；
  - 增加话题串聚合视图（聚焦当前话题串，支持「点此返回全部」）；
  - 消息列表下方增设 `typingBanner`，收到信令时显示「×× 正在输入…」。
- `android/app/src/main/java/wherevercode/agent/core/agent/ChannelFeed.kt`:
  - 定义 `ChannelTypingEvent` 数据类；
  - 实现 `parseChannelTypingWsEvent(text)`，严格解析 WebSocket 推送的 typing 信令帧。
- `android/app/src/main/java/wherevercode/agent/core/agent/McpChannelEventFeed.kt` 与 `MainActivity.kt`:
  - 贯通端点 Feed 到 UI 的 typing 事件回调链路。
- 单元测试覆盖：
  - `TeamChannelTest.kt`: 覆盖话题成串 `groupTopicThreads` 深度溯源与成串排序逻辑；
  - `ChannelFeedTest.kt`: 覆盖 typing 信令帧解析单测与非法 JSON 安全容错；
  - `ChannelMarkdownRendererTest.kt`: 覆盖 Markdown 标记渲染与未闭合非法字符零丢字回退。

### 2. iOS 端
- `ios/WhereverCodeAgent/Models/Models.swift`:
  - 对齐定义 `TeamThread` 自由话题模型与 `groupTopicThreads` 算法；
  - 新增 `ChannelNotifyMode` 三档枚举（全部消息、仅@我、静音）；
  - 对齐定义 `ChannelTypingEvent` 与信令解析函数 `parseChannelTypingWsEvent`。
- `ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift`:
  - 频道设置表单接入通知三档 Picker，并通过 `UserDefaults` 按 `channelId` 独立持久化；
  - 消息行正文支持 Markdown 渲染（粗斜体、代码、链接），渲染异常时回退纯文本；
  - 消息行新增「话题」快捷操作按钮与串内讨论视图，顶栏新增「自由话题」弹窗；
  - 消息列表与输入框之间新增输入中提示栏（「×× 正在输入…」）。
- `ios/WhereverCodeAgent/Services/ChannelFeed.swift`:
  - WebSocket 接收处理中增加 typing 信令识别与 `onTyping` 回调。
- 单元测试覆盖：
  - `ios/WhereverCodeAgentTests/ChannelParsingTests.swift`: 覆盖自由话题分组、Markdown 容错渲染、通知三档持久化与 typing 信令解析。

---

## 三、硬性围栏与约束复核

- **信令零 Token**：输入中提示仅基于 WebSocket 传输帧事件驱动，未调用任何大模型，零 Token 开销。
- **无轮询伪造**：未引入任何定时轮询伪造打字状态；所有输入中提示均为真实的 WebSocket 推送事件触发。
- **派发围栏**：频道内消息无法直接执行代码，点击派发仅打开标准创建任务表单。
- **隔离红线**：未触碰任何 `MessageCenter` 主文件，未修改 `brain` / `BrainKind` 大脑相关目录。

---

## 四、推进进度核对清单

- [x] 核对基线头 `18f4c6ec`，建立工作分支 `feat/channel-p3-orphans-agy-20261121`
- [x] 逐频道免打扰：iOS 补齐三档通知设置（全部消息／仅@我／静音）对齐安卓，`UserDefaults` 独立持久化，与消息中心未读聚合口径对齐
- [x] Markdown 容错渲染：双端粗斜体、代码、链接、列表、引用无损渲染，异常安全回退纯文本，绝不吞字绝不崩溃
- [x] 自由话题线程：双端支持任意消息发起话题、聚焦串内讨论、话题列表弹窗与未读回复聚合
- [x] 输入中提示：端点 WS 信令层解析 typing 帧，零 Token，不调模型，消息栏动态横条提示
- [x] 单元测试补充与验证：双端模型与信令单测全量通过（Python 38/38 测试全通）
- [x] 双端 CI 运行与核验：iOS #300 (Run ID: `44784995853`) 与 Android #586 (Run ID: `44787357198`) 双绿通过
- [x] 撰写交付报告与遗留缺口说明，提交回执标记

---

## 五、双端 CI 验证结果

| 平台 | 构建编号 | 工作流运行编号 (Run ID) | 提交 SHA | 运行结果 | 验证内容 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **iOS** | #300 | `44784995853` | `24f55a02` | **Success (Green)** | generic device 编译打包无签名 IPA、IPA 版本门闩校验、模拟器启动与 UI 导航测试，全绿通过 |
| **Android** | #586 | `44787357198` | `8b06512a` | **Success (Green)** | 源码编译 `compileDebugKotlin` 通过、2968 项单元测试全部通过、`assembleDebug` 与 `assembleCompat` APK 构建归档成功 |

---

## 六、遗留缺口清单（Honest Gaps）

1. **输入中信令上行能力**：当前客户端与平台的端点传输协议中，尚未开放向服务端发送当前用户打字状态的 WebSocket API 接口。客户端已完整就绪信令接收、解析与 UI 动态提示；待后续服务端补充上行接口后即可直接打通双向联动。
2. **富文本高级扩展**：本包严格遵循孤儿项范围，实现了粗斜体、行内代码、围栏代码块、列表、引用与链接；未扩展 LaTeX 公式、表格或嵌套复杂图表等重量级组件，保持移动端渲染轻量和零崩溃。

---

CHANNEL-P3-ORPHANS-AGY-DONE
