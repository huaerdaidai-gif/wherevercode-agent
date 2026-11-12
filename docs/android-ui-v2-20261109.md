# Android UI V2：四入口原生开发工作台（2026-11-09）

分支：`feat/android-ui-v2-20261109`  
基线：`fix/android-tool-approval-20261109`（含 DeepSeek 工具链、审批与写轮次修复）  
范围：只改独立 Android 客户端；不接旧 WhereverCode 平台，不改协作架构，不合并
`main`，不部署。

## 1. 改造前的 UI 架构（勘察结论）

- **界面**：单 `MainActivity`（约 918 行）+ 单个 `activity_main.xml`（约 341 行），
  只有“聊天 / 控制台”两个切换按钮。聊天是 `ScrollView` + `LinearLayout` 里手工
  添加的 `TextView` 气泡；控制台把任务、Agent、设备、诊断、Cloudflare、DeepSeek、
  ChatGPT 登录全部塞在同一个 `ScrollView` 里。
- **聊天状态**：全部是 Activity 字段（`brainConversationId`、`turnActive`、
  `ChatReplyBuffer`）。旋转/切换主题会重建 Activity，进行中的回合与可见 transcript
  会丢失；旧代码只在 `onDestroy` 停止回合。
- **工具/推理事件**：`ChatReplyBuffer` 把 `BrainEvent.ToolCall` / `ToolResult`
  直接追加成气泡里的 `[调用工具 …]` 文本，工具参数/结果没有独立层级，也无法折叠；
  DSML 若被模型写进 `content`，会原样进入气泡。
- **项目工作区**：真实工作区是 `filesDir/workspace`；`FileWorkspaceAdapter` 负责
  写轮次/备份/undo/keep，`ToolRegistry` 负责 `read_file` / `write_file` /
  `run_command` / `list_files`。**没有现成的 UI 文件浏览器或编辑器**，也没有必要
  假装有。
- **任务**：底层有 `TaskStore`（Room）与 `ControlPlane`，但当前聊天轮次并没有写入
  `TaskStore`；用户实际能用的“暂停/停止/改目标”是 `TurnController` 对当前
  `BrainEngine` 回合的控制。
- **Agent/设备**：`AgentDiscovery` 扫描 App 私有目录下的 CLI（codex/claude/
  muse/gemini/codebuddy/agy + 额外环境变量），只报告“二进制在不在、`--version`
  能不能跑”，登录状态保持 **未验证**；设备信息来自 `Build`。没有“在线设备”数据。
- **大脑/审批/执行**：`BrainBootstrap` 组装 `BrainEngine`、DeepSeek/Cloudflare/
  ChatGPT 适配器、`TurnController`、`AndroidApprovalGate`、Chaquopy Python；
  这些能力本身是可复用的，不应在 UI V2 重写。

## 2. 最小改造计划（已实施）

1. **外壳**：保留单 Activity，但改为 4 个一级入口（聊天 / 项目 / 任务 / Agent）
   + 顶部状态条 + 底部导航 + 可返回的设置页；五个页面用独立 XML，按需 inflate。
2. **聊天分层**：新增纯 Kotlin `ChatTimeline`，把正文、推理、工具调用、工具结果、
   系统提示、错误分成独立字段；`ChatTurnView` 用独立气泡/可折叠卡片渲染。
3. **DSML 隔离**：新增 `ChatTextSanitizer`，按 DeepSeek V3.2 真实
   `<｜DSML｜function_calls>` / `<｜DSML｜invoke>` / `<｜DSML｜parameter>` 格式
   整块移除；流式未闭合块从起始标记到缓冲区末尾全部隐藏；另隔离 `<think>` 到
   推理层。普通代码里的 `<`、`>`、`|` 不受影响。
4. **状态存活**：新增 `ChatViewModel`，回合在 ViewModel scope 里跑；旋转/切主题
   只重建 View，不取消回合、不丢 transcript。
5. **项目页**：新增纯 Kotlin `WorkspaceBrowser` + `ProjectScreenController`，真实
   文件树、目录展开、UTF-8/二进制/大小判断、只读代码查看、路径越界拒绝；保留
   “交给 Agent 修改”入口，不伪造保存/编辑。
6. **任务页**：当前轮次真实状态 + 现有暂停/停止/改目标按钮；运行事件来自
   `EventLog`；持久任务库来自 `TaskStore`，空库时明确说明“聊天轮次不写入任务库”；
   持久任务逐行控制未接入 ControlPlane 的部分在 UI 里明确标注“未实现”。
7. **Agent 页**：复用 `AgentDiscovery` 的真实注册表与 `Build`/包信息；未安装、
   版本、登录未验证分别展示；不展示固定角色、不假造在线设备。诊断/安装按钮复用
   `RuntimeEnv` 的 Python/shell/Node 能力。
8. **设置**：ChatGPT 登录、Cloudflare、DeepSeek、模型列表刷新等原控制台能力
   原样迁入设置页；密钥仍只走 Keystore。
9. **主题**：`Theme.AppCompat.DayNight` + `values`/`values-night` 语义色；顶部
   ◐ 按钮在“跟随系统 → 浅色 → 深色”间切换。

## 3. 复用与边界

| 能力 | 处理 |
|---|---|
| DeepSeek / Cloudflare / ChatGPT 推理 | `BrainBootstrap` / `BrainEngine` 原样复用 |
| 工具调用与审批弹窗 | `BrainEvent` + `AndroidApprovalGate` 原样复用 |
| 暂停 / 停止 / 改目标 | `TurnController` 原样复用；任务页调用同一 API |
| 本地 Python / shell / Node | `RuntimeEnv` / Chaquopy / `AgentRunner` 原样复用 |
| 工作区写保护 / 备份 | `FileWorkspaceAdapter` 原样保留；项目页只读 |
| Agent 发现 | `AgentDiscovery` 原样复用 |
| 项目 UI 编辑器 | **未实现**（原本就不存在）；不伪造 |
| 持久任务逐行控制 | **未接入**；UI 明确区分当前轮次与持久任务 |
| 旧平台 / 协作架构 | **不接入、不重设计** |

## 4. 测试与构建

```bash
cd android
ANDROID_HOME=/opt/android-sdk \
ANDROID_SDK_ROOT=/opt/android-sdk \
GRADLE_USER_HOME=/opt/dsh-projects/wherevercode-agent/.gradle-home \
/opt/gradle/gradle-8.0/bin/gradle :app:testDebugUnitTest --offline
```

新增测试：

- `ChatTextSanitizerTest`：完整/流式 DSML 隔离、`<think>` 提取、普通代码不误伤。
- `ChatTimelineTest`：正文/工具调用/结果/推理分层、运行中工具取消、系统提示。
- `WorkspaceBrowserTest`：目录排序、UTF-8 读取/截断、路径越界、二进制识别。
- `ChatTurnViewTest`（Robolectric）：真实 View 层级里工具参数/结果不进入正文气泡。
- `MainLayoutTest`（Robolectric）：K70 竖屏尺寸下浅/深色壳层与五个页面可 inflate。
- `MainActivitySmokeTest`（Robolectric）：Activity 启动后聊天默认选中、四入口存在。
- 既有测试全部保留；`KeystoreSiwcTokenStoreTest` 在 Robolectric 缺少
  `AndroidKeyStore` Provider 时用 `assumeTrue` 跳过，真机/具备 Provider 的 JVM 仍执行。

构建：

```bash
/opt/gradle/gradle-8.0/bin/gradle :app:assembleDebug --offline
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（debug 签名，侧载）。
本次版本：`versionCode 2` / `versionName 0.2.0-ui2`。

## 5. K70 真机验收步骤

1. K70 开启开发者选项与 USB 调试，`adb devices` 确认设备在线。
2. `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`。
3. 打开 App：默认进入“聊天”，底部四个入口均可点；浅色/深色各切一次，聊天
   transcript 不应因切主题丢失（ViewModel 存活）。
4. 设置 → 配置 DeepSeek（Base URL / 模型 / API Key）→ 保存；顶部状态变为
   “DeepSeek · 已连接”。
5. 聊天发送：“列出工作区文件，再读取 README，最后总结”。
   - 正文只出现模型文字；
   - 每次工具调用是独立可折叠卡片，卡片内有“参数 / 结果”；
   - 不应出现 `<｜DSML｜...>` 原文；若模型吐出 DSML，应看到“已隔离原始工具协议”提示。
6. 触发写入：发送“在工作区新建 hello.py 并运行”。
   - 写入/命令前应弹系统审批；
   - 拒绝后不应执行；允许后观察工具卡片成功/失败。
7. 项目页：展开目录 → 打开 `hello.py` → 显示只读代码；返回文件树；刷新后仍在。
8. 任务页：发起一个较长任务，点“暂停 / 停止 / 改目标”，观察真实状态与事件日志；
   持久任务库为空时不应出现伪造任务行。
9. Agent 页：确认只列出本机注册表与设备信息；未安装的 Agent 显示未安装，
   登录状态显示“未验证”，没有“在线设备”或固定角色。
10. 诊断：运行探针，确认 Python/shell/文件探针输出真实结果；Node 缺失时应明确
    报缺失，不伪造成功。

## 6. 明确未做

- 未在真机安装/运行（本环境无连接设备）；真机步骤见上。
- 未接入旧平台、未接协作架构、未改服务端、未合并 `main`、未部署。
- 未实现独立代码编辑器；项目页只读，写入仍走 Agent 工具 + 审批。
- 未实现持久任务行的逐行 ControlPlane 控制。
- 未把 Termux 里的 CLI 纳入 App（沙箱不同 UID，原有约束不变）。
