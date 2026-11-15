# WhereverCode V5 Android 第二阶段收尾交接

交接时间：2026-11-15 UTC。收到用户收敛指令后停止新功能、停止额外手工验收并关闭本地模拟器，不等待未完成 CI。接手人可以直接从 GitHub 继续，不依赖当前对话。Muse 汇总协调，AGY 继续 iOS 独立总装；本轮没有向他们发送外部消息。

## 1. Git 基线、提交和工作区

- 仓库仅 `huaerdaidai-gif/wherevercode-agent`；未访问旧 wherevercode 仓库。
- 用户指定交付分支：**android/v5-ui-assembly**。收尾 fetch / ls-remote 时远端仍为 `ad5ca87bf21b14f78b93a13a46805d481cc82c83`，它是本轮全部提交的祖先。本轮以普通 fast-forward 推送保存成果；如远端并发推进导致拒绝，应停下记录，不强推。
- 独立开发分支保留：`feat/android-v5-stage2-p0-20261115`。
- 最新代码与测试提交：**f0f16289cee4074e81460d0b36a2515e27822a37**（只修正原生测试滚动定位与等待实际删除，不改 App）。
- 最新 App 功能提交：**03c3178a5832b4b0b932ec924e1bb3a69b7d14af**。本地与 CI APK 来自此提交。
- 安全补充提交：**50cd0ed80a58f7afbaf8d52d04bb4095a470ada7**。移植候选及完整提交序列见 [evidence/commits.txt](evidence/commits.txt)。
- 本报告、矩阵、截图与日志随最后的文档交接提交保存。该提交自身 SHA 请以 `git rev-parse HEAD` 或 [交付分支提交记录](https://github.com/huaerdaidai-gif/wherevercode-agent/commits/android/v5-ui-assembly) 为准；最终回复另提供完整 SHA。文档提交不改变已测 APK。
- 有效源码、测试、脚本与文档全部提交；推送后工作区应为 clean。`artifacts/stage2/` 被仓库规则忽略，存放本地 APK/报告，不应提交二进制、密钥或构建缓存。
- 没有合并 main、强推、删除分支、发布正式版、修改 iOS / server / 生产配置。PR #13、#14 与其他 Agent 分支未被覆盖或合并。PR #13 核验仍 open / draft / merged=false；仅逐提交移植其 Android 候选，不代表 PR 已合并。

## 2. 本轮改动文件与目的

完整源码/测试文件清单见 [evidence/changed-code-files.txt](evidence/changed-code-files.txt)，完整交付文件清单见 [evidence/changed-files.txt](evidence/changed-files.txt)。本轮没有新增生产环境配置或共享 API DTO。

| 文件 | 实际修改 |
|---|---|
| MainActivity.kt | 新增 showV5Local，接 search/memory/skills；真实实体 ID 落点与局部页面返回/旋转恢复；保留原控制器、安全门、详情刷新与置顶 |
| ui/flagship/LocalSearch.kt | 读原 ConversationStore 正文/标题、TaskStore、ProjectStore 与 70 路由；脱敏、去重、保留实体 ID；不建第二数据库 |
| ui/flagship/LocalFeatureScreens.kt | 本地搜索、记忆确认/列表/删除、技能项目范围/详情/人工记录/删除；IO、错误、重试、空状态；无云端成功占位 |
| brain/PersistentMemoryStore.kt、MemoryStore.kt | 原 MemoryStore 接口的本机持久实现；保留来源与时间；敏感拒绝；损坏数据不覆盖；commit 失败不返回成功 |
| brain/siwc/BrainBootstrap.kt | 生产 BrainEngine 与 UI 共用同一个 memoryStore；skillStore 仍用原实例与既有 Room 表 |
| AppFlagshipCapabilities.kt、FlagshipApp.kt | 三个真实本地绑定；搜索可打开指定路由详情；保留全部 ID/title/parent 与四父级、不改首页、不加 TabBar |
| ToolRegistry.kt、BrainEngine.kt | 审批后重新检查暂停/停止；executeControlled 单独扩展，原 execute 尾随 lambda 仍代表 stopped，避免语义回退 |
| core/task/TerminalWriteGuard.kt | 终态迟到业务写拒绝；透传 withTaskLock，检查与更新在底层同一锁中；保留控制计数接口 |
| core/agent/*、core/security/*、core/workspace/* 等 | 移植候选的系统 CA + pin、路径/符号链接、防危险命令、脱敏、发送者验证；详见完整清单 |
| core/extension/mcp/McpSecrets.kt、core/dispatch/McpDispatchHost.kt | Keystore 封存 MCP Token；默认持久任务库套终态写守卫 |
| core/team/ChannelAgentRelay.kt、ChannelSenderPolicy.kt、MainActivity 频道接线 | 默认 fail-closed；生产显式传入受信发送者策略 |
| 测试 | 新增长期记忆/搜索测试；安全及持久恢复回归；LocalFeaturesTest 真正通过 UI 写入并独立读库；未删除或弱化安全断言 |
| scripts/export-v5-stage2.py、docs/v5/stage2/* | 独立第二阶段 70 行矩阵、差异、阻塞和证据；第一阶段矩阵与上传基线保持历史快照 |

## 3. 必须分开看的四层证据

**代码存在 ≠ 功能接通 ≠ 测试通过 ≠ 真机验证。** 本轮只有软件模拟器，没有物理真机、已配置模型、平台账号或外部设备。

| 范围 | 代码 / 接通 | 已有测试证据 | 未验证 |
|---|---|---|---|
| 四父级 / 70 路由 | 全保留，可导航 | 本批原生 FlagshipNavTest 70/70 | 不能当成 70 屏业务完成 |
| 长期记忆 | 生产 engine/UI 同实例，真实本地持久化与确认/删除 | JVM 重开/敏感拒绝/损坏及写盘失败；模拟器 UI 保存、独立重读、删除 | 云同步不存在；真实设备 Keystore/隐私场景未验收 |
| 技能 | 原 SkillStore；人工记录 successCount=0、failureCount=0、evidenceTaskIds=[] | 模拟器 UI 创建、独立 Room 重读、零证据、删除；既有 JVM 技能用例通过 | 项目筛选仅代码与单元基础，未做完整设备场景；自动化/市场/生成未完成 |
| 本地搜索 | 正文/任务结果错误/项目元数据/路由实际接线，真实 ID 导航代码 | JVM 正文脱敏/去重、任务/项目 ID；模拟器路由检索与跳转 | 真实任务搜索落点手工复核未完成；云端/文件全文索引不存在 |
| 任务详情刷新 | 首批定时读取、代次隔离、滚动/折叠保留继续保留 | 本批 UI 创建 → queued → assigned → working → failed，详情自动同步 | failed 原因为 brain_not_configured，绝非模型执行成功；完整控制/审查/集成仍未设备验收 |
| 安全 | 候选移植 + 锁与审批后暂停补丁已接生产 | 全量 JVM 及针对性回归通过 | 不等于真机 TLS、网络、权限与远程执行验收 |

原生首轮 3 场景中 2 通过、1 因屏外删除按钮的测试定位失败；没有隐藏失败。f0f1628 修正可见边界检查和实际删除等待后，该场景单独重跑 **OK (1 test)**。最终 3 个不同验收场景均分别通过，不能写成某次“OK (3 tests)”。

## 4. 七个主要域的真实接线

| 域 | 现有生产链路 | 本轮验证与边界 |
|---|---|---|
| 聊天 | MainActivity → ChatViewModel → BrainEngine → 现有模型 Adapter；ConversationStore / SessionLedger 读写；原 Agent/模型切换、流事件、错误和停止入口保留 | JVM 回归通过；未配置模型，流式推理成功、外部 Agent 会话、附件/语音完整权限链未设备验收 |
| 任务 | 原创建表单 → 持久 TaskStore → TaskScheduler / LocalTaskRunner；ControlPlane 独立处理暂停/恢复/停止/改目标；TaskDetailController 与事件/回执存储 | UI 创建、真实领取、失败回执、详情同步通过。任务暂停/恢复/停止的原生完整确认尚未通过本批验收；停止/取消/暂停不混用，回执不当执行成功 |
| 项目 | 原 ProjectStore、项目家/授权目录、WorkspaceBrowser/编辑保存确认与保存前 Diff；GitHostApiClient 真实站点 API | 原代码保留、全量 JVM 通过；本批未对真实 Git 账号、远程 Hand 文件写入、独立全仓 Diff 做设备验收 |
| 控制台 | 原 ConsoleSnapshot 从任务、事件、设备/Agent、审批等真实来源聚合；ConsoleScreenController 复用原控制器 | 入口与导航通过，任务详情置顶代码保留。**本批控制台点任务行的遮挡回归未完成**；首批截图不能顶替本批结果 |
| MCP | 原扩展注册表 / McpServerManager / 真实客户端，执行沿用审批；McpSecrets 安全存储；McpDispatchHost 持久库默认守卫 | Token、发送者、路径、审批/终态写 JVM 回归通过；未配真实远程 MCP 服务，不声明远程连接或执行成功 |
| Hand | 原本地 HandServer / DeviceAdapter / WorkspaceAdapter 与外部 Hand 端口；原 Python server.test_hand_server | 本地及 CI Hand 23 通过；没有外部设备联机，不能推导远端 Hand 安装、权限和执行成功 |
| 设备管理 | 原设备入口/能力测试、配对信任存储、授权与连接器；配对数据和在线/能力状态区分 | 入口可达、相关 JVM 回归通过；真实配对/断网恢复/多设备派单与安全权限未设备验收 |

具体原调用线索见第一阶段 [api-evidence.txt](../api-evidence.txt)、[actions.csv](../actions.csv) 与当前源代码，不发明 HTTP 接口。本批三项新本地操作无远程 API。

## 5. 70 屏状态与阻塞

- 第二阶段权威矩阵：[acceptance-70.csv](acceptance-70.csv) / [JSON](acceptance-70.json)，**70 行**。36 路由有局部可调用能力，34 路由尚无可调用业务页；**0 个整屏全量验收结论**。
- 开工 37 缺口的分类快照：[gap-70.csv](gap-70.csv)：19 可本地实现、13 需服务端、5 依赖设备/Agent/平台；本批从本地项接入 search/memory/skills。不是所有已有 33 个绑定都已闭环。
- 五态按功能证据区分；局部通过写 verified_operations，不能直接升级整个路由 READY_VERIFIED。功能状态与运行时 FAILED 分开。
- [BLOCKERS.md](BLOCKERS.md) 列出规则/持久触发器、同步冲突/离线发件箱、接力所有权、成本账本、可信技能安装、隔离运行实例与编排图等缺失请求/响应和权限要求；**无臆造 endpoint**。
- 停止当前任务仍遵循既有 STOP → cancelled 语义；独立业务取消接口未新增。已送达不等于领取/执行，离线不改变任务终态。
- 其余本地 UI：独立 Git/终端、上下文/角色、运行时与网络诊断、通知、语音权限等仍需后续分批；当前不再继续开发。
- Android/iOS 对齐见 [ANDROID-IOS.md](ANDROID-IOS.md)。固定实现参考 062fb33962d5b9093f0d96476697ac9c61b2c062；最新只读观察 6aeaadaee74fecd79b4372edc01c418f9a66b328 的 70 ID/title/parent 仍 0 差异。未把 iOS 本地 CLI 不支持直接套给 Android。

## 6. 已知问题、安全与未完成检查

1. 本轮追加的只读手工“真实任务搜索 → 指定详情 → 控制台详情”未完成：测试结束后外部 UIAutomator 未取得预期页面树，停在 search-empty；未到控制台步骤。诊断保存在 evidence/manual-not-verified.txt，不将其判为功能通过或确定产品 Bug。用户要求收尾后不再追查。
2. 控制台遮挡首批修复保留，但本批仅任务详情同步已再次实测；360dp 控制台筛选换行问题仍在，不重做 UI。
3. 记忆损坏数据报错并保留，无修复向导；旧 RAM 内容无法追溯迁移；不同设备不做同步。设备备注不是实际授权。
4. SkillStore 若原数据库探针失败回退内存，界面明确提示重启不保留。未借此创建第二套数据库。
5. App 在无 KVM 的软件模拟器冷启动约 45 秒；不把此测量当真实手机性能。原始首页 PNG/JPEG 未随基线上传，不能声称像素级验收。
6. 保留系统 CA+pin、路径/符号链接、控制/审批/写入守卫、MCP Keystore 与脱敏、发送者验证和任务持久恢复。测试没有被删减以追绿。仍需物理设备和真实服务安全验收，不能据本报告做生产发布。
7. 首轮失败的测试生成数据仅在 `.ci` 隔离包，未清空用户数据；失败后可能保留一条 stage2-* 人工零证据技能记录，非业务运行结果。

## 7. 测试、CI 与环境

- 本地全量 JVM：**2805 总数，2804 通过、1 跳过、0 失败**。跳过为原 KeystoreSiwcTokenStoreTest 的 real BrainKeyStore round-trip，见 evidence/jvm-skipped.json。
- JVM 首轮新增保存失败用例的 Editor 测试替身未透传链式返回值，修正替身后全量通过；未删除用例或弱化断言。首轮日志保留在 evidence/jvm-first-run.txt。
- 本地 Hand：**23/23**。
- 原生：FlagshipNavTest 70/70；NativeTaskCreationTest.localRunnerClaimsCreatedTaskAndRecordsOutcome 通过；LocalFeaturesTest 修正定位后重跑通过。环境 **Android 11 / API 30，Google ATD x86_64，360×800，TCG，无 KVM**，不是物理真机。
- 安全提交 CI：[43659979142](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43659979142) **success**。
- App 功能提交 03c3178 CI：[43660719563](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43660719563) **success**；CI XML 独立核对也是 2804 通过/1 跳过，Hand、debug/compat 构建成功。
- 原生测试修正 f0f1628 CI：[43662305473](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43662305473) **收尾快照仍 in_progress**，按用户要求不等待、不预判成功，下一位 Agent 先查看最终状态。快照见 evidence/github-ci-test-fix.json；该提交未修改 App 实现。
- 第一阶段 ad5ca87 没有对应 Actions run，原 2735 通过/1 跳过是本地证据；没有补写为 CI 成功。
- 证据目录包含本批 navigation/local/task 截图、完整失败与重跑日志、构建日志、CI JSON、签名与 SHA 清单；不拿历史截图代替本批。

## 8. APK、下载与复现

以下均为 `wherevercode.agent.ci` **debug 测试包**，不是正式版。完整清单见 [evidence/apk-manifest.json](evidence/apk-manifest.json)。ARM64 与本地包的 debug 签名可能不同，不自动覆盖其他签名安装。

| 包 | 本地实际路径（仓库根下） | SHA-256 / 验证 |
|---|---|---|
| ARM64 CI APK | artifacts/stage2/WhereverCode-V5-stage2-arm64-debug.apk | bad8958e09292d587db7f520a15f6adb8dfa5d533641f45674225d7113596b2f；CI 构建与签名校验通过，未做 ARM64 设备运行 |
| x86_64 APK | artifacts/stage2/WhereverCode-V5-stage2-x86_64-debug.apk | 849dd3e896d789e1ea7089a443a9ae15b0a109d9be2a11749ede612ad6377519；本批模拟器安装与场景验收使用此包 |
| 原生测试 APK | artifacts/stage2/WhereverCode-V5-stage2-native-tests.apk | 81c2147bd33583db7d7c2de40124ba4bc9e1a8e9094bbe56c57116a326b6e429；测试源码 f0f1628，仅测试包重编，App SHA 不变 |

GitHub ARM64 下载：[CI run / Artifacts](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43660719563)，名称 `wherevercode-agent-ci-apk-03c3178a5832b4b0b932ec924e1bb3a69b7d14af`，artifact ID 14140172108；另有 compat APK 与测试报告。CI artifact 保留 14 天（到 2026-11-29 左右），到期可从源码重建；没有发布 Release。

可移植构建前置：JDK 17、Gradle 8.0、Android SDK 34 / 已声明 build tools、项目所需网络依赖。示例：

```bash
git switch android/v5-ui-assembly
git pull --ff-only
gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleCompat
python3 -m unittest server.test_hand_server -v
# API 30 x86_64 模拟器包与原生测试
gradle -p android :app:assembleDebug :app:assembleDebugAndroidTest -PandroidAbis=x86_64
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e allowTaskCreation true -e class wherevercode.agent.ui.FlagshipNavTest,wherevercode.agent.ui.LocalFeaturesTest,wherevercode.agent.ui.NativeTaskCreationTest#localRunnerClaimsCreatedTaskAndRecordsOutcome wherevercode.agent.ci.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/export-v5-stage2.py
```

当前机器本地工具：/tmp/wc-tools/jdk17、gradle-8.0、sdk；环境变量 JAVA_HOME / ANDROID_HOME 指向这些路径。代理/本机缓存配置没有提交。8GiB / 2CPU 环境应先编译、再停 Gradle daemon、再启动软件模拟器；ATD 需在 App 启动前设置 `debug.hwui.drawing_enabled=1`，否则可能有语义树却无有效画面。磁盘不足可清理可再生成 Gradle 缓存，不删除用户数据。

## 9. 下一位 Agent 的最短接手路径

1. 从 android/v5-ui-assembly 拉取并核对当前头与 f0f1628 的祖先关系，先看 43662305473 的最终 CI 状态；不要假设 PR #13/#14 已合并，也不要覆盖并行成果。
2. 先读本报告、acceptance-70.csv、BLOCKERS.md。**首个建议任务是补完真实任务搜索落点和控制台详情遮挡的只读设备回归**，不是开启新的大功能。
3. 从 MainActivity.kt 的 **showV5Local / openV5Route / openTaskDetail / taskDetailRefresh（Runnable）** 与 ui/flagship/LocalSearch.kt.search 开始；原生参考 LocalFeaturesTest、NativeTaskCreationTest。对控制台检查 ConsoleScreenController.taskRow → onOpenTask → openTaskDetail 的实际顶层与返回行为。
4. 如要扩展实体搜索设备用例，使用隔离 `.ci` 包真实 UI 创建的数据，按实体 ID 断言；不要把静态标题匹配或列表跳转当成指定详情成功。
5. 之后再由 Muse 协调优先补原生任务控制确认（暂停/恢复/停止/改目标）与真实模型错误/流式链路；需要账号/设备/后端的先列条件，不伪造服务或成功结果。AGY 继续独立 iOS，总装只对齐接口和交互语义。

本轮已停止开发；未开始下一轮功能，未留下未提交的产品 WIP。
