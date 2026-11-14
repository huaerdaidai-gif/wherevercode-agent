# T9 S2：存储白名单登记与 App 设置盘点

分支：`task/t9-configsync`
S2 状态：登记完成（仅 DTO allowlist，不实现导入/写入）
基线：`2f5e8a87bac61d0b7507d17f00aa7ddb67f33d77`

本登记以仓库当前可见代码为准。`可迁移`表示允许进入 WCA-ConfigBundle v1 DTO；不表示目标端已经具备持久化或导入实现。凭据、授权态和设备配对永不随 bundle 携带。未发现同端可复用的明确信息一律排除或标注目标端不支持。

## 1. 五类字段登记

### 设备记录

| Bundle 字段 | Kotlin/Android 来源 | Swift/iOS 来源 | 类型 | S2 决定 |
|---|---|---|---|---|
| `sourceDeviceId` | `RemoteDevice.deviceId`；`SharedPreferencesRemoteDeviceStore`，prefs `wherevercode_remote_devices` / key `devices_json`，JSON string array | 未发现可持久化的 RemoteDevice registry；`DevicesView` 注释说明 iOS Hand 发现/配对尚未启用 | string | 允许作为源标识元数据；iOS 当前无持久化目标，导入时必须标记不支持，不视为本机设备 ID |
| `displayName` | `RemoteDevice.displayName`，同上 | 无对应 store | string | 允许作为普通设备别名元数据；目标 iOS 不支持时不得写入其他 store |
| `host`、`port` | `RemoteDevice.host:String`、`port:Int`，同上 | 无对应 store | string / integer | 允许作为普通连接地址提示；不构成信任/配对。iOS 当前无目标 store |
| `pairingState` | 不是 RemoteDevice 持久字段；pairing 状态另在 `core/security/PairingRecord`/授权相关存储 | iOS 未发现对应持久设备 store | 固定字面量 | 仅序列化 `reauthorizationRequired` 占位；导入端需重新配对，不迁移 fingerprint、密钥或凭据 |

Android RemoteDevice 还有 `publicHost`、`addedAtEpochSeconds`、`lastSeenEpochSeconds`、`lastServerStatus`、`capabilities`、`protocolCapabilities`、`handVersion` 等字段；S1 DTO 未登记这些。在线/服务端批准/协议能力/软件版本属于运行态或远端声明，不随配置迁移。`publicHost` 与端点策略需另经产品审查，不在 S2 allowlist。配对公钥/本机私钥位于安全存储边界，不读取、不导出。

### Agent 注册表

Android 源：`android/app/src/main/java/wherevercode/agent/core/agent/AgentStore.kt`，`SharedPreferencesAgentStore`，prefs `wherevercode_agents` / key `agents_json`；主要模型 `AgentRecord`。iOS 源：`ios/WhereverCodeAgent/Data/AgentRegistryStore.swift`，`AgentRegistryStore.storageKey = agents.records.v1`，写入 `KeyValueStore` SQLite kv。

| Bundle 字段 | 来源模型属性 | 类型 | 决定/处理 |
|---|---|---|---|
| `agentId` | `AgentRecord.agentId` | string | 可迁移 |
| `displayName` | `displayName` | string | 可迁移 |
| `kind`、`access`、`brainKind`、`adapterId` | 同名字段；iOS `brainKind` 为 string，Android 对应 enum wire name | string | 可迁移；未知值/适配器支持情况留给后续 schema/import validation |
| `enabled` | `enabled` | boolean | 可迁移；用户可见开关，不等同授权状态 |
| `capabilities` | `capabilities` | string array | 可迁移；后续校验端需核实能力枚举 |
| `authorizedProjectIds` | `authorizedProjectIds` | string array | 可迁移；需闭合引用检查 |
| `deviceId` | 远程设备关联 `deviceId` | optional string | 可迁移为来源关联提示；不得据此视为已配对 |
| `credentialState` | 非 store 字段 | 固定字面量 | DTO 合成 `reauthorizationRequired`，不是来源授权状态 |

未列入 Agent DTO 的本地字段均不迁移：`authRef`、`webhookSecretRef`（仅凭据引用也不跨设备复用）、`webhookUrl`、`baseUrl`、`binary`、`model`、登录/测试状态、测试摘要与时间、成员状态镜像、`deviceFolder`、`platformHost`/`platformChannelId`、Webhook 配置、`knownLimits`、执行权限/调度状态、角色、时间戳等。URL 和 endpoint 仅在后续有明确必要且经过 secret scanner/用户确认时再评估。

### 项目

Android 源：`android/app/src/main/java/wherevercode/agent/core/project/ProjectStore.kt`，`SharedPreferencesProjectStore`，prefs `wherevercode_projects`，key `projects_json`、`locations_json`、`active_homes_json`。iOS 源：`ios/WhereverCodeAgent/Data/ProjectStore.swift`；`LocalData.swift` 建立共享 SQLite `projects`、`project_locations` 与 `kv` 表（文件位于 `SQLiteDatabase` 决定的应用本地数据库路径），项目和 home 在前两张表，当前 home 另由 `KeyValueStore` kv 键 `project.activeHome.<projectId>` 记录。Android DrawerPrefs 表复用同一文件 `wherevercode-tasks.db`（`TaskDatabase`），不是新的并行数据库。

| Bundle 字段 | 源模型映射 | 类型 | 决定/处理 |
|---|---|---|---|
| `projectId`、`name` | `Project.projectId`、`name` | string | 可迁移 |
| `repoUrl` | `Project.repoUrl` | optional string | 可迁移，但后续校验必须拒绝 URL userinfo/内嵌凭据 |
| `defaultBranch` | `Project.defaultBranch` | string | 可迁移 |
| `homes[].homeKind` | `ProjectLocation.homeKind` / Android `ProjectLocation` home 类型 | string | 可迁移为来源 home 类型提示 |
| `homes[].hostId` | `ProjectLocation.hostId`；设备 ID 或 git host wire (`github`/`gitlab`/`gitee`) | string | 可迁移为引用提示；设备关联需重新核对 |
| `homes[].pathHint` | Android 项目 location path / iOS `ProjectLocation.workspacePath` | string | 允许作为提示文本，不得自动授权/打开该路径；UI 必须提示“需重新选择位置” |

不迁移 iOS `canonicalCommit`、location ID、可用性、来源/运行状态、last-seen、Android active-home 的已选状态。Android `active_homes_json` / iOS `project.activeHome.*` 只代表设备本地当前选择，目标端应由用户重新选择 home。路径提示不得作为文件访问权或沙箱授权 receipt。

### 大脑配置

Android：`android/app/src/main/java/wherevercode/agent/brain/BrainConfig.kt`；`SharedPreferencesBrainConfigStore`，prefs `wherevercode_brain_configs`，key `configs_json`、`current_brain_id`、`deleted_brain_ids_json`。凭据另经 `BrainKeyStore`（Keystore-backed `EncryptedSharedPreferences`，文件 `wherevercode_brain_keys`）。iOS：`ios/WhereverCodeAgent/Brain/BrainConfig.swift`，`brain.configs_json`、`brain.current_brain_id` 存 `KeyValueStore` SQLite kv；秘密单独由 Keychain store 管理。

| Bundle 字段 | 来源属性 | 类型 | 决定/处理 |
|---|---|---|---|
| `brainId`、`kind`、`adapterId`、`label`、`model` | `BrainConfig` 同名字段（iOS brain config 对应字段） | string | 可迁移 |
| `baseUrl` | `BrainConfig.baseUrl` | optional string | 可迁移；导入计划需校验 URL 不含 userinfo/凭据 |
| `fallbackBrainIds` | `BrainConfig.fallbackBrainIds` | string array | 可迁移；引用需闭合 |
| `currentBrainId` | Android `current_brain_id` / iOS `brain.current_brain_id` | optional string | 可迁移，仅当对应 bundle brain 存在且受支持时接受 |
| `credentialState` | 非来源字段 | 固定字面量 | DTO 合成 `reauthorizationRequired`；`authRef`、API key、Token、CLI 登录态及 Keychain/Keystore 值一律不迁移 |

`BrainConfig.extra`、tombstone/deleted ids、login state、last test 时间/摘要、credential refs 均不导出。

### App Settings

| Bundle 字段 | Android 来源 | iOS 来源 | 类型 | 决定 |
|---|---|---|---|---|
| `brainDisplayName` | `ui/BrainDisplayName.kt`，SharedPreferences `brain_display_name` / `brain_display_name`，string | `Brain/BrainDisplayName.swift`，KeyValueStore SQLite kv key `brain.displayName`，string | optional string | 唯一跨端 App Settings 白名单字段 |

S2 前 S1 中暂列为候选的 `platformBaseUrl`、`channelId`、`workModeId`、`freedomLevel`、`hiddenModules`、`drawerOrder`、`drawerHidden`、`drawerFavorites` 已从 Android/iOS DTO 与共享 fixture 移除。Android 未找到与 iOS `AppSettings` 用户可编辑平台地址/频道相匹配的持久化设置（Android 平台 host/channel 由构建配置/Agent 记录等其他边界提供）；WorkMode/Console/Drawer 设置仅 Android 有源或 schema 不同。基于跨端同义性与目标写入来源不足，当前均不迁移。

## 2. App 设置全量盘点（候选项与排除项）

盘点方法：遍历 Android Kotlin `getSharedPreferences`/持久化 store 与 iOS Swift `UserDefaults`、`KeyValueStore` 调用点；逐个检查写入存储及 key。以下内容用于证明候选已审，不表示全都属于可迁移 App 设置。

| 持久化类别/位置 | 已发现内容 | S2 处理及理由 |
|---|---|---|
| Android `brain_display_name` / `brain_display_name`；iOS kv `brain.displayName` | 用户自定义大脑显示名 | 纳入唯一白名单 |
| iOS `UserDefaults` | `wherevercode.platformBaseURL` (String)、`wherevercode.channelID` (String)，见 `Models/AppSettings.swift` `SettingsStore` | 只在 iOS 存；Android 没有匹配的用户偏好 store；排除 |
| Android `wherevercode_work_mode` / `work_mode_id` | WorkMode ID | Android 专属、iOS 无同义设置，排除 |
| Android `console_prefs` / `freedom_level` (Int)、`hidden_modules` (comma-separated String) | Console 页面自由度/隐藏模块偏好 | Android 专属；名称/枚举体系未证实跨端一致，排除 |
| Android Room `drawer_prefs` (`DrawerPrefsEntity`, `DrawerPrefsDatabase.kt`) | 固定 `id="default"`；`orderJson:String`、`hiddenJson:String`、`favoritesJson:String`、`nestedJson:String`、`moreExpanded:Boolean`；表位于共享 `wherevercode-tasks.db` | iOS 未发现同结构持久化；全部排除 |
| Android `ui_v2_theme` | theme 偏好 | iOS 未发现对应 key/store，排除 |
| Android `wherevercode_chat`、ChatHistoryStore (`chat_history` 等)；iOS kv `chat.currentSessionId`、`chat.sessionProjects`、`chat.directAgentSessions`、channel message cursor | 会话指针、聊天快照/项目绑定、会话来源标记、消息游标 | 运行态/可能含用户内容及跨端无同义，全部排除 |
| Android Onboarding prefs、iOS onboarding/引导状态（如存在） | 引导完成/选择方向 | 安装/流程状态，排除 |
| Android `AppUpdateSettings`/Google Drive OAuth 设置 | Google Drive 客户端 ID 等更新集成配置 | 更新/外部授权边界，不迁移 |
| Android `HandUpdateController` | 自动同步 Hand 设置 | 安全/更新策略，不迁移 |
| Android `SharedPreferencesRouterSettings`、autonomy、capability cards/extensions | 路由、自治级别、插件/扩展配置 | 影响执行权限/行为或平台专属；未证明 iOS 一一对应，排除 |
| Android `wherevercode_authorization`、授权 Room 表、device identity/pairing store | 授权状态、设备身份及 pairing 记录 | 安全敏感，必须本地重新授权/配对，禁止迁移 |
| Android `wherevercode_brain_keys`、DeviceCode token prefs/Keychain；iOS Keychain | API/token/secret 和授权凭据 | 绝不进 bundle、测试、日志 |
| Android task ID、platform reply cursor、MCP dispatch/runtime、eval/routing logs、capability snapshots；iOS task/session/database/cache | 任务状态、网络游标、诊断日志、缓存 | 运行态、日志或瞬态缓存，排除 |
| Android `extension-manifests-v1`、team channel prefs | 组件/团队上下文 | 非本期配置迁移类别且目标 schema 未核实，排除 |
| iOS `ChannelListCache`、`project.activeHome.*`、deleted brain IDs | 缓存/本地活动位置/删除墓碑 | 缓存与设备本地状态，排除 |

若后续发现遗漏，不得把它们反射式塞入 bundle；需新增登记行、核实两个平台的字段语义与秘密/权限边界，并同步扩充测试。

## 3. DTO 对齐与限制

Android 明确 JSON codec 仅创建 `Bundle*V1` 字段；iOS 仅对 `Bundle*V1` Codable value 编码，不对业务模型反射编码。DTO 目前可承载五类登记中批准字段和固定授权占位。App Settings 只允许 `brainDisplayName`。设备、Agent、Brain 的 credential/pairing state 固定为 `reauthorizationRequired`，不能由输入替换为“已授权”。

目前 S1 测试覆盖 shared fixture 解析/往返和格式/版本拒绝；S2 不实现读写业务 stores。引用闭合、secret scanner、冲突、原子提交归 S3/S4，本轮未实现；上述限制不应被解释为完整导入安全验证已完成。

## 4. 本地验证状态

提交前记录：PBX 对象引用静态检查通过（未见未定义 object ID）。Android 专项 JVM 测试由 Gradle 8.0.2 临时运行，结果以交付说明记录。沙箱没有 `xcodebuild`/Swift 工具链，iOS 单测未本地执行，需 Muse CI 补验。
