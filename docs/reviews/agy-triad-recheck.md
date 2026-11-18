# AGY 独立复核报告：iOS 三件套清单（只审查不改代码）

- **复核目标**：复核 `feat/triad-ios-20261119`（头应为 `408f59f365c2e317560c9fe6c2bb26698d14cb03`）上 `docs/ios-triad-checklist.md` 的自评是否属实。
- **审查基线**：`main`（`75c7218c`）的安卓实现 (`MainActivity.kt` / `AgentScreenController.kt` / `FreeProviderPresets.kt` / `ModelRouting.kt` / `DeviceModelDiscovery.kt`)。
- **复核原则**：代码证据驱动，每项给出文件路径 + 函数/类型名证据；发现假对齐单列；只审查不改代码。

---

## 一、分支头核对结果

- **预期 HEAD**：`408f59f365c2e317560c9fe6c2bb26698d14cb03`
- **实际 HEAD**：`408f59f365c2e317560c9fe6c2bb26698d14cb03`
- **核对判定**：**完全一致**。
- **最新合入状态**：该分支后续已合入 `main`（merge commit `b640601bde72c1984138604992aa1c0596329d90`），且其在 GitHub Actions 的对应 CI 运行（Run ID: 44376516179）已绿（success）。

---

## 二、重点三项逐项判定与代码证据

### 1. 配对路径会不会自动创建 Agent
- **自评状态**：已做（配对成功不得自动建 Agent 记录、不得触发任何扫描；发现与配对完全独立）
- **复核判定**：**属实**
- **代码调用链追踪与证据**：
  1. **配对执行点**：
     - 文件：`ios/WhereverCodeAgent/Views/DevicesView.swift`
     - 函数：`executePairConfirm()`（L662-L721）
     - 行为：配对确认成功（`.approved`）后，仅保存宿主设备自身的记录：
       ```swift
       let rec = AgentRecord(
           agentId: hello.deviceId,
           displayName: cleanName,
           kind: .device,
           access: .app,
           baseUrl: fullHost,
           authRef: "device:\(hello.deviceId):public_key",
           lastTestSummary: "已连接（在线）",
           memberConnectionState: "CONNECTED",
           deviceId: hello.deviceId,
           deviceFolder: selectedFolder
       )
       try? LocalData.shared.agents.saveAgent(rec)
       onSaved(rec)
       ```
     - 这里的记录明确标记为 `kind: .device, access: .app`，`agentId == deviceId`。
  2. **后续回调无 Agent 扫描/写入**：
     - 回调 `onSaved` 触发 `reload()` 与 `startLanScan()`。
     - `startLanScan()`（L277-L345）仅调用 `HandClient.shared.discoverLanDevices` 探测网络，并调用 `syncDiscoveredPeersWithRegisteredDevices` 更新已登记设备 IP 或刷新已发现设备列表，没有任何创建 Agent 记录的逻辑。
  3. **Agent 列表严格过滤设备记录**：
     - 文件：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift`
     - 函数：`CollaborationAndAgentsScreen.reload()`（L1566-L1570）
     - 过滤规则：`agents = all.filter { !($0.kind == .device && $0.access == .app && $0.agentId == $0.deviceId) && $0.kind != .localModel }`，宿主设备记录绝对不会混入 Agent 列表。
  4. **Agent 添加入口收敛**：
     - 全应用仅保留 Agent 页内「添加 Agent」弹窗（`AddAgentSheet`）的手动添加（`saveManualAgent`，L1882）与勾选后批量发现添加（`saveDiscoveredAgents`，L1857）。
  - **结论**：配对成功后完全没有写 Agent 存储、建 Agent 记录的代码，配对与 Agent 创建彻底解耦。

---

### 2. 智能路由是否真接入 BrainEngine
- **自评状态**：已做（408f59f3 将 ModelRouting 接入 BrainEngine failoverPolicyProvider）
- **复核判定**：**属实**
- **代码接线与运行时执行证据**：
  1. **接线缝隙 (Seam) 对齐安卓基线**：
     - 文件：`ios/WhereverCodeAgent/Brain/BrainBootstrap.swift`（L286-L295）
     - 在 `buildEngine` 中注入 `failoverPolicyProvider` 闭包：
       ```swift
       failoverPolicyProvider: { brainId in
           let routingSettings = UserDefaultsModelRoutingStore().load()
           guard routingSettings.enabled else { return nil }
           guard let config = configStore.getConfig(brainId: brainId),
                 config.kind == .modelApi else { return nil }
           return ModelRouting.policyFor(
               settings: routingSettings,
               configs: configStore.listConfigs(),
               currentBrainId: brainId)
       }
       ```
     - 契约与安卓 `BrainBootstrap.kt`（L1359-L1368）完全一致：设置开关未开启或非 MODEL_API 时返回 `nil` 维持既有静态政策；开启时动态实时加载 `UserDefaultsModelRoutingStore`。
  2. **运行时执行引擎读取与故障切换**：
     - 文件：`ios/WhereverCodeAgent/Brain/BrainEngine.swift`
     - 属性与构造参数：`private let failoverPolicyProvider: ((String) -> FailoverPolicy?)?`（L235, L269, L289）
     - 轮次调度循环（L1029-L1055）：
       ```swift
       let policy = failoverPolicyProvider?(brainId) ?? failoverPolicy
       if Failover.shouldRetry(
           error: err, attemptsSoFar: sameBrainAttempts, policy: policy) {
           sameBrainAttempts += 1
           log(conversation.id, "failover_retry", "同大脑重试第 \(sameBrainAttempts) 次")
           continue outer
       }
       ...
       let next = (allowFailover && err.retryable)
           ? Failover.nextFallback(
               currentBrainId: brainId, policy: policy, tried: triedBrains)
           : nil
       if let next {
           brainId = next
           switched = true
           sameBrainAttempts = 0
           try? conversations.updateConversationBrain(id: conversation.id, brainId: brainId)
           log(conversation.id, "brain_switched", "失败后切换大脑为 \(brainId)")
           continue outer
       }
       ```
     - 引擎在捕获到重试性错误（`roundError` 且 `retryable`）时，运行时调用 `failoverPolicyProvider?(brainId)` 获取降级链，并执行 `Failover.nextFallback` 切换大脑重新进入 `outer` 循环执行。
  3. **单元测试验证**：
     - 文件：`ios/WhereverCodeAgentTests/BrainLayerTests.swift`
     - 测试用例：`testEngineFailoverPolicyProviderOverridesStaticPolicy`（L661-L689）真实模拟前序模型报错并由路由池备用模型接手成功。
  4. **路由占位清理**：
     - `FlagshipRouteDestinations.swift`（L105）已将 `modelroute` 映射到 `BrainSettingsView`；
     - `EntryRegistry.swift`（L129）状态变更为 `.implemented`，原【待开发】占位代码已完全删除。
  - **结论**：并非仅在设置层存值，而是运行时完整读取并执行故障降级切换。

---

### 3. Agent 删除是否有确认
- **自评状态**：已做（删除确认文案严格对齐安卓规范）
- **复核判定**：**属实**
- **代码调用与 UI 交互证据**：
  1. **删除入口与暂存**：
     - 文件：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift`
     - 组件：`CollaborationAndAgentsScreen`
     - 代码（L1405-1406, L1482-1485）：
       ```swift
       @State private var agentPendingDelete: AgentRecord? = nil
       @State private var showDeleteAgentConfirm = false
       ...
       Button("删除", role: .destructive) {
           agentPendingDelete = ag
           showDeleteAgentConfirm = true
       }.font(.caption2)
       ```
       点击列表中的「删除」按钮，不直接触发删除，而是先记录 `agentPendingDelete` 并激活弹窗标志。
  2. **二次确认 Alert 弹窗**：
     - 代码（L1498-L1511）：
       ```swift
       .alert("移除 Agent", isPresented: $showDeleteAgentConfirm) {
           Button("取消", role: .cancel) { agentPendingDelete = nil }
           Button("移除", role: .destructive) {
               if let ag = agentPendingDelete {
                   try? LocalData.shared.agents.deleteAgent(agentId: ag.agentId)
                   agentPendingDelete = nil
                   reload()
               }
           }
       } message: {
           if let ag = agentPendingDelete {
               Text("确定移除 \(ag.displayName) 吗？它的独立会话与已指派任务不受影响。")
           }
       }
       ```
     - 取消操作安全清空暂存；确认移除后才调用 `LocalData.shared.agents.deleteAgent`。提示文案明示不影响独立会话与已指派任务，严格对齐安卓规范。
  - **结论**：Agent 删除入口点击后有完整的二次确认对话框，非直接删除。

---

## 三、模型页已添加列表数据源核查（Funk 04:27 追加核查项）

经追踪模型页及其跳转入口的完整实现链路，对「已添加模型」列表的数据源核查判定如下：

### 1. 判定结论
**【(b) 借用了大脑 Brain 那套注册表/列表，只是换了标题与分类文案】**。
iOS 端未建立独立的纯模型存储（没有单独的 ModelRecord / ModelStore 数据表），其实体结构、底层 Key-Value 键名、存储层服务全部直接复用既有大脑 `BrainConfigStore`。

### 2. 文件级代码证据与借用链路
1. **列表渲染文件与视图**：
   - **渲染文件**：`ios/WhereverCodeAgent/Views/BrainSettingsView.swift`
   - **渲染视图**：`BrainSettingsView` 中的 `addedModelsSection`（L377-L413）及其子方法 `modelRow(config: BrainConfig)`。
   - **路由入口**：`ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift`（L105）：
     ```swift
     case "models", "connectmodel", "modelroute":
         BrainSettingsView(model: appState.brainSettingsModel)
     ```
     控制台（`ControlHomeView`）的「模型」卡点击后执行 `onNavigateRoute("models")`，进入的即是该 `BrainSettingsView`。
2. **数据读取 Store 与函数**：
   - **ViewModel**：`BrainSettingsModel`（`BrainSettingsView.swift` L14-L70）
   - **读取函数**：`BrainSettingsModel.reload()`（L44-L48）：
     ```swift
     public func reload() {
         configs = configStore.listConfigs()
         currentBrainId = configStore.currentBrainId()
         routingSettings = routingStore.load()
     }
     ```
   - **过滤分组**（L363-L374）：
     直接在 `model.configs` 上按 `BrainConfig.kind` 进行过滤展示：
     - `modelApis`: `config.kind == .modelApi` 且非本地 URL
     - `localModels`: `config.kind == .modelApi` 且为本地 URL
     - `cliBrains`: `config.kind == .agentRuntime`
     - `subscriptions`: `config.kind == .subscription`
3. **底层 Store 存储内容核实**：
   - **Store 文件**：`ios/WhereverCodeAgent/Brain/BrainConfig.swift`
   - **Store 类型**：`BrainConfigStore`（L355-L400）
   - **持久化键名**：
     - `keyConfigs = "brain.configs_json"`
     - `keyCurrent = "brain.current_brain_id"`
     - `keyDeleted = "brain.deleted_brain_ids_json"`
   - **数据对象类型**：`BrainConfig`（L35-L72），字段包含 `brainId`、`kind: BrainKind`、`adapterId`、`label`、`baseUrl`、`model`、`authRef`、`fallbackBrainIds` 等。
4. **架构与安卓基线对齐说明**：
   - 该借用设计并非 iOS 私自偏差，而是严格对齐安卓基线（`MainActivity.kt` L1559, L1852-L1910）：
     安卓端控制台模型卡亦直接读取 `brainComponents?.configStore?.listConfigs()?.size ?: 0`，且模型页（`configureModelsMode`）同样直接复用 `screenSettings` 与大脑存储体系。

---

## 四、清单其余项逐项判定

### ① 设备页 (Devices Screen)
| 清单项 | 自评状态 | 复核判定 | 代码证据路径与关键类型/函数 |
| :--- | :---: | :---: | :--- |
| **页标题与入口同名「设备」；只留「添加设备」+「已添加设备」两块** | 已做 | **属实** | `DevicesView.swift#L42-L45` (`discoveryAndInstallSection`, `registeredDevicesSection`, `.navigationTitle("设备")`); `FlagshipApp.swift#L105` |
| **删「系统与权限说明」节** | 已做 | **属实** | `DevicesView.swift` 中原有 `shortcutsSection` 已完全移除（全文 grep 0 命中） |
| **安装指引改名与通用终端适配** | 已做 | **属实** | `DeviceInstallGuide.swift#L8-L135` (`DeviceTargetOs`, `stepInstructions`, `v5-install-hand.sh` 脚本 SHA-256 校验); `DevicesView.swift#L122`（文案改为「在新设备上安装 Hand」） |
| **角标与副文案同源同值** | 已做 | **属实** | `DevicesView.swift#L183, L200, L207, L232-L243`：右上角标与副文案均使用 `hostedCount` 计算，彻底消除 MacBook-Air 冲突 |
| **概念分离硬规则**（agy 不得为设备、历史迁移清理） | 已做 | **属实** | `AgentRegistryStore.swift#L231-L236` (`migrateLegacyDeviceRecords` 强制清除 agy 设备记录); `TriadFeaturesTests.swift#L180-L194` (`testAgyCannotBeRegisteredAsDevice`) |
| **解耦删除与确认文案**（删设备只删设备、文案对齐） | 已做 | **属实** | `DevicesView.swift#L54-L65` (`.alert("确认删除设备")`, 文案字字对齐安卓 `MainActivity.kt#L3072`); `AgentRegistryStore.swift#L197-L215` (`deleteDeviceCascade` 保留关联 Agent 仅标记解绑) |

### ② Agent 页 (Agents Screen)
| 清单项 | 自评状态 | 复核判定 | 代码证据路径与关键类型/函数 |
| :--- | :---: | :---: | :--- |
| **页面布局精简**（只留「添加 Agent」+「已添加的 Agent」两块） | 已做 | **属实** | `FlagshipRouteDestinations.swift#L1426-L1490` (`Section("添加 Agent")`, `Section("已添加的 Agent（\(agents.count)）")`, 路由限定清理冗余分区) |
| **单入口发现整合**（自动发现整合入添加弹窗，默认不勾，扫描探活，预勾批量添加） | 已做 | **属实** | `FlagshipRouteDestinations.swift#L1573-L1880` (`AddAgentSheet`, `startDiscoveryFlow`, `scanDevicePickerSheet`, `executeDeviceScan`, `discoveredAgentsPickerSheet`) |
| **删设备页的「重新发现 Agent」按钮** | 已做 | **属实** | `DevicesView.swift` 移除原有设备卡独立扫描按钮，全局 grep "重新发现" 0 命中 |
| **配对与发现完全解耦** | 已做 | **属实** | `DevicesView.swift#L677-L697` (`executePairConfirm` 仅保存 device 记录，不扫描、不建 Agent)；`TriadFeaturesTests.swift#L196-L232` |

### ③ 模型（设置内模型区） (Models Screen)
| 清单项 | 自评状态 | 复核判定 | 代码证据路径与关键类型/函数 |
| :--- | :---: | :---: | :--- |
| **重命名与分组体系**（改名「模型」，四分类展示） | 已做 | **属实** | `SettingsView.swift#L106-L111` (`Section("模型")`); `BrainSettingsView.swift#L237, L363-L413` (`.navigationTitle("模型")`, 分类：模型 API / CLI 会员 / 订阅授权 / 本地模型) |
| **平铺顶层交互**（预设、自动发现、智能路由不折叠，与列表同页） | 已做 | **属实** | `BrainSettingsView.swift#L273-L359` (`addSection`, `routingSection`, `addedModelsSection` 顶层平铺展开) |
| **双预设独立**（常用服务商 vs 免费 token 服务商不合并） | 已做 | **属实** | `BrainSettingsView.swift#L276-L294, L525-L585` (`CommonProviderPickerSheet` 与 `FreeProviderPickerSheet` 独立入口与 Sheet) |
| **免费预设 6 家提供商严格对齐** | 已做 | **属实** | `FreeProviderPresets.swift#L75-L142`（1. Google AI Studio, 2. OpenRouter, 3. Groq, 4. 硅基流动 [注明实名认证], 5. 智谱 GLM [FIXED 6款], 6. Cloudflare Workers AI [FIXED 4款+Account ID]）; `TriadFeaturesTests.swift#L7-L77` |
| **名单外自定义扩展口** | 已做 | **属实** | `CustomFreeProviders.swift#L1-L70` (`CustomFreeProviders`, `KeyValueCustomFreeProviderStore`); `BrainSettingsView.swift#L868` (`AddCustomFreeProviderSheet`); `TriadFeaturesTests.swift#L80-L101` |
| **智能路由 (Model Routing)**（默认关，仅限免费，付费需勾选，顺序故障转移） | 已做 | **属实** | `ModelRouting.swift#L1-L83` (`ModelRoutingSettings.enabled == false`, `fallbackOrder`, `UserDefaultsModelRoutingStore`); `TriadFeaturesTests.swift#L104-L151` |
| **模型自动发现 (Device Model Discovery)**（默认关，支持 11434/1234/8000，私网/Tailscale 走 http，公网走 https） | 已做 | **属实** | `DeviceModelDiscovery.swift#L1-L82` (`endpoints`, `candidateBaseUrls`, `isPlainHttpHost`); `BrainConfig.swift#L210-L224` (`BrainConfigValidator.isPrivateLanIpv4` 覆盖 RFC1918 与 100.64/10); `TriadFeaturesTests.swift#L154-L177` |

### ④ 通用与工程架构规范
| 清单项 | 自评状态 | 复核判定 | 代码证据路径与关键类型/函数 |
| :--- | :---: | :---: | :--- |
| **文案规范**（短标签 + 至多一句人话） | 已做 | **属实** | `BrainSettingsView.swift`, `DevicesView.swift`, `FlagshipRouteDestinations.swift` 全文核对无长篇需求文档堆砌 |
| **对话方选择器规范**（只列 Agent，无已配置模型节） | 已做 | **属实** | `LocalData.swift#L1945-L1962` (`ModelAndAgentSheet` 仅列 `availableAgents`); `WhereverCodeAgentUITests.swift#L227` (`XCTAssertFalse(staticTexts["已配置模型"].exists)`) |
| **Xcode 工程构建集成** | 已做 | **属实** | `project.pbxproj#L372-L381, L214-L217, L288`（新源文件与测试套件完整登记在 Sources 构建阶段） |
| **单元测试与防劣化测试集** | 已做 | **属实** | `TriadFeaturesTests.swift`（共 233 行，6 组用例覆盖免费预设、智能路由、局域网探测、概念隔离与删除边界） |

---

## 五、「假对齐」清单

经深入审查所有接线与调用链路：
**【无】**。所有在 `docs/ios-triad-checklist.md` 中标明「已做」的功能项均具备真实代码支撑与调用闭环，不存在虚假接线或未使用的存值逻辑。

---

## 六、总体复核结论

`docs/ios-triad-checklist.md` 的各项自评**全部属实**。
尤其重点审查的三项：
1. **配对与 Agent 创建完全解耦**：配对成功只存设备本身，不自动建 Agent；
2. **智能路由真正进入运行时调度**：通过 `failoverPolicyProvider` 缝隙无缝接入 `BrainEngine`，遇重试性故障真实执行池内降级；
3. **Agent 删除具备二次确认交互**：点击删除后弹出系统 Alert 明确提示风险，确认后方执行删除。

本地 Python 门禁套件全部一次性通过：
- `verify_ios_v5_assembly.py`: 8/8 PASS
- `run_e2e_wiring_tests.py`: 9/9 PASS
- `test_device_management_regression.py`: 11/11 PASS
- `test_p1_cross_device_fixes.py`: 5/5 PASS
该分支 `408f59f3` 现已成功合入主干 `main`（`b640601b`）。
