# AGY 实施与审查报告：iOS 模型页与大脑彻底脱钩

- **实施任务**：iOS 模型页另起炉灶与大脑彻底脱钩；回滚 SettingsView 3 处大脑文案；新建独立存储与真模型页；修复 AppState 初始化时序
- **执行分支**：`feat/ios-model-decouple-20261119`
- **拉分支基线 HEAD**：`origin/main` @ `acaa60d34e258af3268f59bf8f3b1b189f0b9854` (Merge feat/model-page-decouple-20261119)
- **定点绿标提交**：`b867fc25e8a0a177748a3dfd0593078a15e3c2f6`
- **iOS CI 门禁结果**：**#175 绿标通过**（Run ID: [44419590620](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44419590620)）
- **依据与规范**：Funk 2026-11-19 批示「大脑已砍开放一边，不许改名、不许借用」；Grok 独立审查报告 (`review/grok-ios-model-audit-20261119`)；派发简报 `~/agy-ios-model-decouple-brief.md` 及第七修定点指令

---

## 一、改动总体概览（改了什么）

本次脱钩任务严格守住「大脑红线（大脑本体零改动）」，仅在 `ios/` 目录下完成改名回滚与独立模型体系建设：

1. **改名回滚（24522af0 三处改名精准还原）**：将 `SettingsView.swift` 中被篡改的 3 处文案（分节标题、按钮标题、说明文字）严格按历史 diff 还原为「大脑」，分节顺序与布局保持原状。
2. **ModelRegistry 独立存储**：新建 `ModelConfig` 实体与 `KeyValueModelConfigStore`，使用独立的持久化键 `model.configs_json` 与 `model.current_model_id`，独立 Keychain 凭据前缀 `model:\(modelId):key`，绝不读写 `brain.configs_json`。
3. **模型卡角标改数独立存储**：模型卡与控制台的数据源直接绑定独立的 `ModelConfigStore`，角标真实展示已添加独立模型数，杜绝借用大脑注册表数据。
4. **大脑零改动（严格坚守红线）**：
   - `BrainSettingsView` 大脑列表增删改查功能保持原样；
   - `brain.*` 存储读写逻辑、`BrainConfigStore`、`KeyValueBrainConfigStore` 零改动；
   - `BrainEngine` 依赖的大脑堆栈装配及单兵测试完全不受脱钩影响。
5. **AppState 初始化重排定点修复**：在 `WhereverCodeAgentApp.swift` 中将 `self.modelConfigStore` 与 `self.modelSettingsModel` 赋值前移，与 `self.brainSettingsModel` 并列完成 Phase 1 阶段所有存储属性初始化，再挂载弱引用 `self` 的闭包，彻底清零 `used before being initialized` 编译器错误。

---

## 二、改名回滚证据（24522af0 引入的三处还原）

在历史提交 `24522af0` 中，`SettingsView.swift` 的 3 处大脑文案被改为模型口径。本次严格核对历史 diff，完成精准还原：

### 1. 回滚 Diff 证据

```diff
--- a/ios/WhereverCodeAgent/Views/SettingsView.swift
+++ b/ios/WhereverCodeAgent/Views/SettingsView.swift
@@ -103,18 +103,18 @@ struct SettingsView: View {
     var body: some View {
         NavigationStack {
             Form {
-                Section("模型") {
+                Section("大脑") {
                     NavigationLink {
                         BrainSettingsView(model: appState.brainSettingsModel)
                     } label: {
                         HStack {
-                            Text("管理模型")
+                            Text("管理大脑")
                             Spacer()
                             Text(currentBrainSummary)
                                 .foregroundStyle(.secondary)
                         }
                     }
-                    Text("模型是可替换的推理后端（DeepSeek、Claude、Grok、Muse Spark、Cloudflare、通用模型 API、ChatGPT 会员）。Key 只存本机 Keychain。没有当前模型时设备 Agent 与单兵对话不受影响。")
+                    Text("大脑是可替换的推理后端（DeepSeek、Claude、Grok、Muse Spark、Cloudflare、通用模型 API、ChatGPT 会员）。Key 只存本机 Keychain。没有当前大脑时设备 Agent 与单兵对话不受影响。")
                         .font(.caption)
                         .foregroundStyle(.secondary)
                 }
```

### 2. 回滚项逐条核验
- **分节标题**：`Section("模型")` → 改回 `Section("大脑")`
- **入口按钮**：`Text("管理模型")` → 改回 `Text("管理大脑")`
- **说明文字**：`Text("模型是可替换的推理后端...")` → 改回 `Text("大脑是可替换的推理后端...")`

---

## 三、ModelRegistry 独立存储与大脑存储完全隔离证据

### 1. 独立存储架构设计
在 `ios/WhereverCodeAgent/Models/ModelRegistry.swift` 中建立完全平行的独立模型体系：
- **独立实体**：`ModelConfig`（实现 `Identifiable`, `Codable`, `Sendable`）
  - 属性包括：`modelId`, `kind`, `adapterId`, `label`, `baseUrl`, `model`, `authRef`, `extra`, `fallbackModelIds`, `lastTestedAtEpochSeconds`, `lastTestSummary` 等。
- **存储协议**：`ModelConfigStore`
  - `listConfigs() -> [ModelConfig]`
  - `getConfig(modelId: String) -> ModelConfig?`
  - `saveConfig(_ config: ModelConfig)`
  - `deleteConfig(modelId: String)`
  - `currentModelId() -> String?`
  - `setCurrentModelId(_ modelId: String?)`
- **存储实现**：`KeyValueModelConfigStore`
  - 存储键名：
    - `public static let keyConfigs = "model.configs_json"`
    - `public static let keyCurrent = "model.current_model_id"`
  - 凭据隔离：Key 存入系统 Keychain，引用格式为 `"model:\(modelId):key"`，与大脑凭据彻底分离。

### 2. 与 `brain.configs_json` 彻底隔离的代码证据
- **源码排查**：
  在 `ModelRegistry.swift` 与 `ModelSettingsView.swift` 中搜索 `"brain.configs_json"`，命中数均为 **0**。
- **单元测试隔离断言**（见 `ios/WhereverCodeAgentTests/ModelDecoupleTests.swift#testStorageKeysAreCompletelyIsolated`）：
  ```swift
  // 保存模型到新模型 store
  modelStore.saveConfig(testModel)

  // 断言：新键写入成功，但大脑存储键为 nil，两者物理隔离
  XCTAssertNotNil(try data.kv.get("model.configs_json"))
  XCTAssertNil(try data.kv.get("brain.configs_json"))
  ```

---

## 四、模型页结构与模型卡落点接管

### 1. 模型页结构（对齐「添加 ＋ 已添加」两块）
实现于 `ios/WhereverCodeAgent/Views/ModelSettingsView.swift`：
1. **添加块**：
   - **预设服务商添加**：常用服务商预设（套用通用 OpenAI 兼容端点）；
   - **免费服务商添加**：6 家官方核验免费 provider + 自定义提供商扩展；
   - **智能路由配置**：路由总开关、允许付费模型进池开关、降级优先池选择（`ModelRoutingPoolPickerSheet`）；
   - **局域网/通用端点**：局域网模型探测、手动添加通用模型 API。
2. **已添加块**：
   - 沿用原 `addedModelsSection` 业务分法：模型 API、本地模型、CLI 会员、订阅授权；
   - 列表数据 100% 绑定 `ModelSettingsModel` (`modelConfigStore`)；
   - 新增、修改、测试、删除所有交互完全作用于 `model.configs_json`，绝不污染 `brain.configs_json`。

### 2. 模型卡路由落点平滑接管
- 在路由分发层 `FlagshipRouteDestinations.swift` 中：
  ```swift
  case "models", "connectmodel", "modelroute":
      ModelSettingsView(model: appState.modelSettingsModel)
  ```
- 抽屉设置分节（`AccountSettingsView`）的「模型配置」亦直接跳转至 `ModelSettingsView`。
- 模型卡（`feat/ios-model-card-20261119`）配置的卡片跳转目的地为 `routeId: "models"`，因此卡分支合入 main 后将天然、无缝落在脱钩后的真模型页上。

---

## 五、第七修定点修复说明（AppState 初始化时序）

- **问题现象**：在 CI #173 中，`AppState.init` 报 4 处 `variable 'self.modelConfigStore' used before being initialized`。
- **根因分析**：Swift 编译器要求类实例的所有存储属性在 Phase 1 全部完成赋值后，方可引用 `self`。原代码在 `self.modelConfigStore` 与 `self.modelSettingsModel` 赋值前，提前挂载了 `model.registryProvider = { [weak self] in ... }` 与 `model.onChange = { [weak self] in ... }`，提前捕获了 `self`。
- **定点修复**：在 [WhereverCodeAgentApp.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/App/WhereverCodeAgentApp.swift#L89-L105) 中调整顺序，先对 `self.modelConfigStore` 和 `self.modelSettingsModel` 赋值，在 Phase 1 阶段所有存储属性就绪后，再挂载两个弱引用闭包并执行 `repairCurrentBrainReference()`。
- **自查结果**：彻底消除 Swift 编译器错误，未引入任何新时序风险。

---

## 六、CI 自动化门禁结果（CI #175 绿标）

- **工作流**：iOS (`.github/workflows/ios.yml`)
- **轮次号**：**#175**
- **Run ID**：`44419590620`
- **Run URL**：https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44419590620
- **触发 Commit**：`b867fc25e8a0a177748a3dfd0593078a15e3c2f6`
- **执行结果**：**completed success（100% 绿标）**
- **步骤全量通过明细**：
  1. `Set up job` — success
  2. `Check out` — success
  3. `Verify 70-Screen Contract & E2E Wiring` — success
  4. `Select latest stable Xcode on the runner` — success
  5. `Pick an available iPhone simulator` — success
  6. `Build (unsigned, generic iOS device)` — success
  7. `Package unsigned IPA` — success
  8. `Verify IPA version matches pbxproj (D5 gate)` — success
  9. `Upload unsigned IPA` — success
  10. `Build and capture live simulator launch` — success
  11. `Upload live simulator launch` — success
  12. `Package simulator app` — success
  13. `Upload simulator app` — success
  14. `Test navigation on iOS Simulator` — success
  15. `Test on iOS Simulator` — success
  16. `Export simulator screenshots` — success
  17. `Upload simulator screenshots` — success

同 commit 对应的 Android CI 工作流（Run #410, ID: `44419590534`）亦同步 100% 绿标。

---

## 七、改动文件清单

1. `ios/WhereverCodeAgent/Views/SettingsView.swift`：精准回滚 3 处大脑文案。
2. `ios/WhereverCodeAgent/Models/ModelRegistry.swift`：新增模型独立存储实体 `ModelConfig`、`ModelConfigStore`、`KeyValueModelConfigStore` 与 `ModelSettingsModel`。
3. `ios/WhereverCodeAgent/Views/ModelSettingsView.swift`：新增脱钩真模型页（添加块＋已添加块）。
4. `ios/WhereverCodeAgent/App/WhereverCodeAgentApp.swift`：注入模型存储并修复初始化顺序。
5. `ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift`：接管 `"models"`, `"connectmodel"`, `"modelroute"` 路由至真模型页。
6. `ios/WhereverCodeAgent.xcodeproj/project.pbxproj`：注册新模型源码与测试文件。
7. `ios/WhereverCodeAgentTests/ModelDecoupleTests.swift`：脱钩防劣化单元测试（存储物理隔离、CRUD 闭环、分类分组）。
8. `docs/reviews/agy-ios-model-decouple.md`：本审查报告。
