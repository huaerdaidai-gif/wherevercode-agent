# 频道孤儿项补全包（P3-Orphans）执行报告

- **执行者**：AGY（2026-11-21 Muse 派）
- **基线分支**：`feat/channel-phase1-20261120`，基线头 `18f4c6ec03f82111a938348c1eee0672c796d532`（经 `git ls-remote` 核对一致）
- **工作分支**：`feat/channel-p3-orphans-agy-20261121`（从一期基线头切出，独立推送）
- **范围依据**：LeChat《频道孤儿项核对报告》＋ Muse 2026-11-21 现头复核纠偏通知。

---

## 一、现头复核结论与范围调整

1. **逐频道免打扰／通知设置**：
   - **安卓现头状态**：已在 `TeamChannelController.kt` 与 `MainActivity.kt` 实现三档通知（`全部` / `仅@我` / `免打扰`），本地通过 `team_channel_prefs` 键 `notify_$activeId` 持久化，无需重复造轮子。
   - **iOS 现头状态**：仅有布尔型 `isMuted`（免打扰），缺「仅@我」档，且设置项未完整落入频道设置与 `UserDefaults` 区分。
   - **调整结论**：安卓不重做；本包只在 iOS 端补齐三档对齐（全部消息／仅@我／静音），本地按 channelId 持久化，并与消息中心未读口径对齐。
2. **@所有人／广播提及**：
   - 现头双端均已具备客户端提及与 owner/协调者门闩渲染，服务端广播语义由 Funk 另定，不在本包范围内。
3. **Markdown／富文本渲染**：
   - 现基线消息文本为纯文本显示（`message.text`）。
   - 安卓已有纯 Kotlin 无依赖解析器 `MarkdownLite`；iOS 缺乏富文本解析与容错渲染。
   - 需双端统一支持粗斜体、代码块、链接、列表、引用，且解析失败必须无损回退纯文本，绝不崩溃、绝不吞字。
4. **自由话题线程**：
   - 现头仅有按任务分组的 `TeamThread`，缺少基于任意消息发起的自选话题线程。
   - 需在现有 `TeamThread` 机制上扩展自由话题：支持从任意消息发起话题线程、话题内回复、话题列表可见入口，双端复用同一模型与机制，不另起平行实现。
5. **输入中提示**：
   - 现头成员模型具备 status 字段，但缺乏信令层 typing 事件解析与输入提示展示。
   - 需实现 WS 信令层 typing 帧解析（零 token、不调模型）并在输入栏上方展示「×× 正在输入…」。现有传输缺乏上行 typing 发送通道的现状将如实记录在缺口清单中，杜绝轮询伪造。

---

## 二、实施计划与文件清单

### 1. 文件改动清单
- **Android**:
  - `android/app/src/main/java/wherevercode/agent/core/team/TeamChannel.kt`：扩展 `TeamThread` 支持自由话题（rootMessageId / topicTitle / groupTopicThreads / teamThreadTitle）。
  - `android/app/src/main/java/wherevercode/agent/ui/TeamChannelController.kt`：消息正文接入 MarkdownLite 容错渲染；消息行添加「话题」动作入口与话题列表入口；输入栏接入 typing 正在输入提示。
  - `android/app/src/main/java/wherevercode/agent/core/agent/ChannelFeed.kt`：信令层增加 `parseChannelTypingWsEvent` 与 `ChannelTypingEvent`。
  - `android/app/src/test/java/wherevercode/agent/core/team/TeamChannelTest.kt`：新增自由话题线程、标题与成组单测。
  - `android/app/src/test/java/wherevercode/agent/core/agent/ChannelFeedTest.kt`：新增 typing 信令帧解析单测。
- **iOS**:
  - `ios/WhereverCodeAgent/Models/Models.swift`：对齐引入 `TeamThread`、`ChannelTypingEvent`、三档通知枚举 `ChannelNotifyMode`，以及消息中心按免打扰/仅@我的未读过滤逻辑。
  - `ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift`：
    - 频道设置补充「通知设置」三档选择器（全部消息 / 仅@我 / 静音），持久化到 UserDefaults；
    - 消息行接入 Markdown 渲染与安全纯文本回退；
    - 消息行增加「话题」入口、顶栏增加「话题列表」视图与话题内回复；
    - 输入栏上方增加「×× 正在输入…」信令提示。
  - `ios/WhereverCodeAgent/Services/ChannelFeed.swift`：信令层解析 typing 事件。
  - `ios/WhereverCodeAgentTests/ChannelParsingTests.swift`：新增自由话题模型、通知三档过滤、Markdown 容错及 typing 信令单测。

### 2. 推进节奏
1. [x] 核对基线头 18f4c6ec，检出 `feat/channel-p3-orphans-agy-20261121`。
2. [x] 撰写实施计划与审计结论报告（本初版提交 [skip ci]）。
3. [ ] 双端核心模型与信令层实现及单测编写。
4. [ ] 双端 UI 交互（三档通知、Markdown 渲染、话题线程、输入中提示）落地。
5. [ ] 运行分支定向 CI 并记录运行号与状态。
6. [ ] 补齐最终总结与缺口清单，交付回执。

---
（待代码与 CI 跑通后补充后续章节）
