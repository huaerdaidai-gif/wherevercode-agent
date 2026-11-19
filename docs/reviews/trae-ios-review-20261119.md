# 审查-TRAE-iOS复核-20261119

> 独立只读复核报告。复核对象：`feat/ios-model-decouple-20261119` 的 `4bca0663`（脱钩基线 `51ccfa46` + B1 `e935d37e` + 合并 Kiro B2 `ab98c3f3`）；对照基线：`main = bafe277c`。

## 0. 复核基线与范围

- 仓库：`github.com/huaerdaidai-gif/wherevercode-agent`（公开，已 fetch 全量 refs）
- 对照基线：`main = bafe277c`（与 `origin/HEAD` 一致）
- 被审对象：`4bca0663` 的树（脱钩基线 `51ccfa46` + B1 `e935d37e` + B2 `ab98c3f3` 的合并）
- 重要范围提示：远程分支 `feat/ios-model-decouple-20261119` 的实际头已是 `f3dfe06c`（B3「diff 9 model decouple and runtime composite」），**比送审的 `4bca0663` 多 1 个提交**（改动 AppState +56、ModelRegistry +261、测试 +319，并新增 `scripts/test_ios_b3_regression.py`）。若合并包取分支头，会带入未审的 B3；本次结论仅覆盖 `4bca0663`。
- 环境限制：Linux 无 Xcode，无法执行 iOS 单测；测试类结论为静态阅读判定。Python 回归脚本含真机 Mac 探测与 `cryptography` 依赖，也未执行。

---

## 一、红线判定

| 红线 | 结论 | 严重程度 |
|---|---|---|
| R1 设置「大脑」分节与 main 逐字节一致 | **不通过** | 高（红线） |
| R2 brain 核心文件零差异 | 通过 | — |
| R3 删设备只删设备记录；删 Agent 只删 Agent | 通过 | — |
| R4 模型存储独立、大脑本体不变 | **部分不通过**（两个具名键通过，整体独立性不成立） | 高 |

### R1：不通过（高）

main 与分支的该分节实际是反的，**不是「不动」，而是把 main 的文案整体改掉了**：

- main `bafe277c` `ios/WhereverCodeAgent/Views/SettingsView.swift`
  - `:106` `Section("模型")`
  - `:111` `Text("管理模型")`
  - `:117` `Text("模型是可替换的推理后端…)`
- 分支 `4bca0663` 同文件
  - `:106` `Section("大脑")`
  - `:111` `Text("管理大脑")`
  - `:117` `Text("大脑是可替换的推理后端…)`

引入提交：`9b32e8f`（"…and rollback settings copy"）。分支自带报告 `docs/reviews/agy-ios-model-decouple.md` 明确写这是「回滚 24522af 的三处改名」。

关键事实：`24522af` 本身就是 main 上的提交，其 message 写明「Align Models screen in Settings: rename to '模型'」，且已在 `bafe277c` 内。因此相对你给的基线 `bafe277c`，这是**把 main 的三权分立改名整体回退**，不是「与 main 一致」。R1 的字面前提（main 里是「大脑」）与实际基线不符——需要先裁决：以 main 的「模型」为准（则分支必须删除这次回滚），还是产品确要「大脑」（则应先改 main，再让本分支 rebase，而不是在脱钩分支里暗改）。

### R2：通过

`git diff bafe277c..4bca0663` 对下列路径均为空：`ios/WhereverCodeAgent/Brain/**`（含 `BrainConfig.swift`、`BrainBootstrap.swift`、`BrainSettingsView.swift` 等 33 个文件）。仓库 iOS 侧不存在 `BrainLoginRows` 对应文件（全仓 `LoginRow/BrainLogin` 零命中），无可比对象。Brain 相关零改动成立。

### R3：通过

- `AgentRegistryStore.swift:201-207`：`deleteDeviceCascade` 仅 `markDeviceDeleted` + 删除 `agentId == deviceId` 的宿主记录，旧的「解绑/置空 deviceId/baseUrl/memberConnectionState」循环已删除。
- `:203` 新增 `HandIdentityManager.deleteDeviceCredentials`，实现见 `HandClient.swift:350-357`：只删该设备的 peer public key（`device:<id>:public_key`），**不触碰任何 AgentRecord**。测试 `HandClientTests.swift:388-392` 也断言 Agent 的 `deviceId` 保留、状态字段不变。
- `AgentRegistryStore.swift:192-196` `deleteAgent` 仅按 `agentId` 删除单条，无级联。

### R4：部分不通过（高）

通过的部分：

- `ModelRegistry.swift:76-77`：`model.configs_json` / `model.current_model_id`，与 `BrainConfig.swift:355` 的 `brain.configs_json` 分离。
- 全分支 `brain.configs_json` 仅出现在注释与测试，运行代码零读写。
- `ModelSettingsView` 的增删改查走 `ModelSettingsModel(configStore: KeyValueModelConfigStore)`。

不通过的部分（模型页与大脑仍共用可变状态）：

- `ModelRegistry.swift:224-234`：`ModelSettingsModel` 默认注入 `routingStore = UserDefaultsModelRoutingStore()`、`customProviderStore = KeyValueCustomFreeProviderStore()`。
- 二者与大脑默认实例完全相同：`BrainSettingsView.swift:34-35`；存储键 `wherevercode_model_routing`（`ModelRouting.swift:63`）与 `wherevercode_custom_free_providers`（`CustomFreeProviders.swift:44`）。
- 大脑运行时直接读这个共享路由：`BrainBootstrap.swift:287-292` `UserDefaultsModelRoutingStore().load()` 参与 failover policy。
- 模型页会写它：`ModelSettingsView.swift:137/150/738`（路由开关、允许付费、优先级池）。

后果：在模型页配置的路由开关/池（模型 ID）会直接改变大脑的 failover 行为，自定义免费服务商也会串到大脑设置里。这与「模型存储独立 / 大脑本体行为不变」的红线意图冲突。建议模型页使用独立 key（例如 `model.routing`、`model.custom_free_providers`），或在 `AppState` 注入独立实例。

---

## 二、逐项核查点

### 1. 差异 4+5+13 控制台计数 —— 通过（低风险观察）

- `FlagshipRouteDestinations.swift:4828` `agentsCount = allAgents.filter { !$0.isDeviceHost && $0.kind != .localModel }.count`
- `:4829` `devicesCount = allAgents.filter { $0.isDeviceHost }.count`
- 两处共用 `AgentRegistryStore.swift:73-75` 的 `isDeviceHost`（`kind == .device && access == .app && agentId == deviceId`）。寄生在设备上的 `.cli` 子 Agent 不再计为设备；设备宿主不再计为 Agent。达成。

观察（低）：`agentsCount` 相对 main 的 `allAgents.count` 额外排除了 `.localModel`，属于清单外的口径变化（方向合理，但未被要求）；另外 `migrateLegacyDeviceRecords`（`:217-228`）的墓碑过滤也收紧为 `isDeviceHost`，`deviceId == nil` 的历史宿主记录在回填前不会命中墓碑（仅历史备份恢复场景可触发）。

### 2. 差异 8 删设备条件 —— 通过

- `AgentRegistryStore.swift:205` `agents.removeAll { $0.agentId == deviceId }`，条件即 `agentId == deviceId`。
- 旧解绑循环已删除；删后不写任何其它 Agent 记录。
- 未加 `kind == .device` 守卫不构成风险：`agentId` 是唯一主键，配对设备均由 `DevicesView.swift:679-690` 以 `agentId = hello.deviceId, deviceId = hello.deviceId` 写入。

### 3. 差异 16 频道详情状态行 —— 不通过（中）

真实连接状态已接入（`FlagshipRouteDestinations.swift:3157-3158`、`4245-4276` 的 `startFeed/stopFeed`、`3291-3300` 的 `connectionStateText`），假在线文案已移除，`realOnlineAgentCount` 从未赋值（`:3158` 声明、`:3305` 读取）所以不会伪造在线数——这部分达成。

但「四种状态文案与安卓逐字一致」不成立：轮询态少了后缀与错误原文。

- 安卓 `android/.../ui/TeamChannelController.kt:258-260`
  `"○ 轮询兜底（推送已断）" + (error?.let { "：$it" } ?: "")`
- iOS `FlagshipRouteDestinations.swift:3296-3297`
  仅 `"○ 轮询兜底"`，也未把 `connectionError` 拼进去。

其余三态（直播/连接中/未连接）逐字一致。属文案与错误可观测性缺失，不影响连接真伪判定，故为中。

### 4. Kiro B2 合入 —— 通过

- N1：`FlagshipRouteDestinations.swift:817-820` `loadProjects()` 已无自动种项目；`@@ -761` 三行演示入口已删除；空状态 `:715` `"还没有创建任何项目"` 可达。全仓无其它 `saveProject` 自动种子。
- N5：`LocalData.swift:342` / `:348` / `:655` 在 `selectedAgent != nil` 时写 `brainId = nil`，改为只记 `agentId`（`:343`）；纯大脑路径 `:828` 仍写 `selectedBrain`，未误伤大脑会话。
- 观察（低）：已存在的历史污染会话没有迁移；`:321-323` 仍把 `conversation.brainId` 当作大脑会话识别，旧的 Agent 会话会被误判为大脑会话（仅影响历史数据）。

### 5. CI 修复（HandClientTests 差异 8 断言）—— 通过（静态）

- `HandClientTests.swift:388-392`、`:708-711`：断言由「deviceId 置空 + NOT_CONNECTED」改为「deviceId 保留 + 与保存前的记录逐字段相等」，`:711` 直接用 `XCTAssertEqual(preservedAgent, agent)` 全结构比对，是加强而非放水。
- 合并提交确实只补了 B1 漏改的第三处：`e935d37e^..e935d37e` 只改了第 226、705 两处，`e935d37e..4bca0663` 补的是第 385 处。与提交说明「fix CI #178 diff 8 assertion」一致。
- 未执行 iOS 测试（无 Xcode），此结论为静态判定。

---

## 三、总评

**不可进合并包。**

阻断项：

1. **R1（高）**：`SettingsView.swift:106/111/117` 把 main 的「模型」整体改回「大脑」，与基线 `bafe277c` 不是逐字节一致，且回退了 main 上 `24522af` 的三权分立改名。合并即回归。
2. **R4（高，部分）**：路由与自定义免费服务商仍共用大脑的 `UserDefaults` 键，模型页可改写大脑 failover 行为（`ModelSettingsView.swift:137/150/738` → `BrainBootstrap.swift:287-292`）。
3. **核查点 3（中）**：轮询态文案与安卓不一致，且丢失断线原因。

建议放行前必须：删除/裁决 R1 回滚；给模型页配独立路由与自定义 provider 存储键；补齐轮询态文案。另请把合并包钉在 `4bca0663`，或先补审分支头 `f3dfe06c`（B3）。
