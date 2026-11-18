# AGY 审查与实施报告：iOS 控制台补「模型」卡

- **实施任务**：iOS 控制台补齐「模型」卡，与「设备」「Agent」并列，点进现有模型页，角标为真实已添加模型数
- **执行分支**：`feat/ios-model-card-20261119`
- **基线分支与 HEAD**：`origin/main` @ `b640601bde72c1984138604992aa1c0596329d90`
- **派发与审批**：Funk 2026-11-19 批示「合 + 抓紧下一步」；Muse 派发简报 `~/agy-ios-model-card-brief.md`

---

## 一、main 分支头核对结果

- **预期 HEAD**：`b640601b`（含 merge `ad2863cf` 模型页分支 + merge `b640601b` iOS 三件套分支，`408f59f3` 已合入）
- **实际 HEAD**：`b640601bde72c1984138604992aa1c0596329d90`
- **核对结论**：完全一致。新分支 `feat/ios-model-card-20261119` 严格基于该 commit 拉出。

---

## 二、改动文件清单与接线说明

本任务严格遵循规范：**只改 `ios/`，只加卡 + 接线，不碰大脑分节、不改模型页本体、不动 `android/`**。

### 1. 改动文件列表
1. `ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift`
2. `ios/WhereverCodeAgentTests/TriadFeaturesTests.swift`

### 2. 接线说明
- **控制台主页卡片并列**：
  在 `ControlHomeView` 的 `controlModulesList` 中，在「设备」与「Agent」卡之后新增「模型」卡，顺序严格对齐安卓定版口径：
  1. `controlCard(title: "设备", ...)`
  2. `controlCard(title: "Agent", ...)`
  3. `controlCard(title: "模型", subtitle: "添加模型服务商与免费 Token，配置智能路由", icon: "cube", badge: modelsBadge, routeId: "models")`
  4. `controlCard(title: "MCP", ...)`
- **页面跳转接线**：
  点击「模型」卡触发 `onNavigateRoute("models")`，进入既有 Flagship 路由分派 `FlagshipRouteDestinations`（L105）：
  ```swift
  case "models", "connectmodel", "modelroute":
      BrainSettingsView(model: appState.brainSettingsModel)
  ```
  直接复用既有完整的 `BrainSettingsView` 模型页，不重做、不改本体。
- **单元测试防劣化**：
  在 `TriadFeaturesTests.swift` 新增 `testControlModulesListIncludesModelsCard`，使用工程内置的 `try LocalData.inMemory()` 构建内存隔离的 `KeyValueBrainConfigStore`，验证配置增减与累加时模型数角标真实变动，且完全解耦主线程 `@MainActor` 与测试执行器。

---

## 三、角标计数口径核查

- **供数源**：
  通过 `appState.brainConfigStore.listConfigs().count` 实时读取本地配置存储中已添加的模型总数。
- **文案呈现**：
  - 当 `modelsCount > 0` 时，呈现为 `"\(modelsCount) 个模型"`；
  - 当 `modelsCount == 0` 时，呈现为 `"模型"`。
- **安卓对齐验证**：
  完全对齐安卓基线 `MainActivity.kt`：
  ```kotlin
  modelsCount = { brainComponents?.configStore?.listConfigs()?.size ?: 0 }
  ```
  以及 `ConsoleScreenController.kt`：
  ```kotlin
  val modelsBadge = modelsCount().let { if (it > 0) "$it 个模型" else "模型" }
  ```
  数据真实透明，不伪造假数据，不预设假值。

---

## 四、本地与 CI 自动化门禁结果

### 1. 本地轻量化门禁验证
- `scripts/verify_ios_v5_assembly.py`: **8/8 PASS** (70 路由完整，抽屉四主入口与 70 屏功能领域对齐，0 凭证泄漏)
- `scripts/run_e2e_wiring_tests.py`: **9/9 PASS** (SQLite、Keychain、MCP、Channel、Task、Model、File、Hand、Device 迁移)
- `scripts/test_device_management_regression.py`: **11/11 PASS**

### 2. GitHub Actions iOS Workflow CI 结论
- **Workflow**：iOS (`.github/workflows/ios.yml`)
- **Run ID**：`44401007200`
- **Run URL**：https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44401007200
- **Commit**：`7d99cae` (`test(ios): verify multiple model config count accumulation`)
- **结论**：**SUCCESS (All 17 steps passed, test navigation & unit tests 100% green)**
