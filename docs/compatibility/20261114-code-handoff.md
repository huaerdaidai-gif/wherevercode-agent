# PR #10 代码整合与升级兼容交接（Codex → Muse）

本文件是代码审计与开发自测记录，不是最终验收、签名证明或版本发布。
依据 2026-11-14 最新分工，Muse 组织 CI、构建、截图、真机验收和发布；
Codex 继续本分支的代码工作，不改旧仓库，不替换生产签名。

## 三条路线的实际差异

共同祖先：`a9206e169b3ed0052a3245e9ca3a895290791bad`。

| 路线 | 已核对的实际差异 | 本次处理 |
|---|---|---|
| UI4 Build 182 / `2304e87d2db12ddebe7931f96630fdeaa736d198` | MCP 项目范围与执行者权限、claim/start/进度/交付回执链；Hand 身份与 attempt 隔离、断线/租约恢复、产物有界重试和哈希校验；维护者签名与构建版本接线 | 选择性恢复业务源码、协议与测试。生产构建签名能力在现有 Gradle 中恢复，并移除无密钥时退回 debug 签名的行为。未导入整条发布工作流 |
| UI4 Build 5 / `8b46a8a83cf6b4c6bdd3fb2936ff01e6d451675d` | 配对与在线状态分开、频道重连与诊断折叠、错误重试、项目面包屑/搜索、Agent 类型表单、设置跳转与更新源缺失反馈 | 合入现有控制器；保留 PR #10 的任务创建、频道讨论/建任务分流和单抽屉 |
| PR #10 / `feat/native-dual-ui-delivery-20261114` | 双端原生聊天首页、iOS 真实 BrainEngine / SQLite 会话、共享视觉 token 与工作台、Android 真实任务表单与控制回执、删除重复 70 屏 UI 壳 | 保留并继续修复，不回退或重做 |

代码整合提交 `ee9cd85` 使用上述共同祖先到两个版本的**功能文件差异**，
不是将两个旧分支无条件整体合并。冲突处理保留真实新对话按钮、任务表单、
频道建任务回调和按需展开工作台；Hand stdout EOF 修复与 lease/artifact 清理同时保留。

## 复用与数据证据

以下五个 Android 文件在三条路线的 Git blob 完全相同：

| 文件 | Git blob |
|---|---|
| core/task/TaskStore.kt | afc77fcc1e86e09d0402797ab5c84484f5050eee |
| brain/ConversationStore.kt | a1216a5290ab0c75bacb856eb724bff0b749d022 |
| brain/BrainConfig.kt | 4c99bd279cf6e06d6b8f96b6dba2f96433928088 |
| core/agent/AgentStore.kt | 02a2b8cbb949431b9aae35412832f9419b557628 |
| core/project/ProjectStore.kt | a183bb2935e14f0652a4140de0f89ac019cf587d |

生产源码只有 `core/task/TaskStore.kt` 的一个 `@Database` 与
`Room.databaseBuilder`；SessionDatabase 提供实体/DAO，没有第二个生产数据库。
聊天、任务、MCP 派工、Hand 直连/安装/更新仍复用既有引擎、调度器、
设备密钥、项目目录授权和通信端口。本次未新增平行引擎、调度器或数据库。
iOS 使用既有 AppState、BrainBootstrap、BrainEngine、LocalData 和 MCP transport；
LocalChatModel 只是 AppState 拥有的界面适配层，不是另一套推理或存储实现。

未改 Android 数据库版本、迁移、SharedPreferences 名称、KeyStore 别名或应用身份。
MCP token 的项目授权采用 Build 182 既有的收紧规则：不自动扩大旧 Agent token 的
权限；旧 token 缺少所需项目范围时应由所有者重新签发。不能以兼容为由绕过授权。

## Android 版本与签名规则

唯一版本文件：`android/version.properties`，首个整合基线 `versionCode=183`，
高于已知已安装 182 与 5。可由 `-PappVersionCode` 或
`WHEREVERCODE_VERSION_CODE` 提供更高生产计数；低于 183 或非法值直接拒绝。
versionName 包含版本、counter、commit 和构建时间，避免不同来源继续共用 UI4 名称。

| 构建 | 包名 | 用途与签名 |
|---|---|---|
| debug | wherevercode.agent.ci | 隔离开发测试；普通 debug 签名；不能覆盖正式包，也不继承正式包数据 |
| compat | wherevercode.agent | 正式身份的兼容构建；有显式维护者密钥才签名，否则输出 unsigned |
| release | wherevercode.agent | 正式身份；同样不允许自动替换历史密钥 |

维护者签名仅读取已有环境变量 `ANDROID_KEYSTORE_FILE`、
`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
没有新建或导出生产密钥。**是否与已安装应用证书相同，必须由 Muse 对照设备确认。**
不要使用不同 workflow 的 `run_number` 当作统一递增发布序号；每次正式发布要推进
统一 versionCode。不能把 unsigned / 新密钥签名包称为可覆盖升级，也不要求卸载旧版。

## 应用内更新

保留既有 Google Drive 只读 OAuth 与 Keystore；来源仍由
`updateDriveFileId` / `WHEREVERCODE_UPDATE_DRIVE_FILE_ID` 注入，未配置时明确禁用。
没有将任何服务器设为唯一更新中心，也没有写入私人分发文件 ID。

校验和不同只表示存在候选文件，不能直接证明“新版可安装”。下载后与安装前均通过
PackageManager 读取包身份、版本与当前签名集合，并检查：

1. 包名完全一致，CI 包与正式包不可相互覆盖。
2. versionCode 必须高于已安装版本，拒绝同版本重包及降级。
3. 签名可验证且当前签名集合完全一致；未知签名、unsigned、换钥均拒绝。
4. Drive 校验和/大小错误仍删除下载文件，不交安装器。

签名轮换不自动放行，需 Muse 单独组织合法 lineage 的验证与迁移。

## 开发自测与待验收

- 整合 Hand 服务端：`python -m unittest server.test_hand_server -q`，38 项通过。
- 两个 Hand Python 文件：`python -m py_compile` 通过。
- Android 本地 JDK 17 / Gradle 8.0 / API 34 开发自测：316 套件、2779 项，0 失败、0 错误、1 项既有跳过。包含恢复的安全回归与新增升级校验。
- 新增升级策略测试：182→183、5→183、同版/降级、CI/正式错包、换钥、无签名、
  多签名集合、指纹格式，以及无效 APK 在生成安装 Intent 前被拒绝。
- 早期 PR #10 的 2730 JVM 测试、4 项模拟器测试、167 iOS XCTest 结果是相应
  旧提交的开发证据，不能替代本次整合后的 Muse 最终验收。

Muse 后续验证：原签名证书与 Build 182/5 的覆盖安装、真实用户数据库/授权/历史保留、
更新源权限与 counter 分配、双端编译与回归、iPhone 17 / Redmi K70 实机、
断网/重连/过期 lease/产物重试和升级中的权限提示。Codex 不据 CI 成功宣布真机通过。

## 尚未实现及后续顺序

- P0：继续 PR #10，修复开发自测暴露的问题；交由 Muse 做最终验收。
- P1：版本/签名/更新校验代码已补；历史签名可用性与实机升级仍是 Muse 验证项。
- P2：iOS 文件浏览/编辑与执行、Hand 发现配对、直接外部 Agent 通信和本地任务
  执行器尚未完成。已有读取界面与平台任务不能冒充这些功能，未接入的操作禁用说明。
- P3：多通道自动唤醒、跨设备自主派工与审查闭环需逐接口补齐；沿用现有端口与门禁。
  不支持自动唤醒的外部服务继续如实报告，不模拟成功，不固定模型供应商或 Agent 角色。
