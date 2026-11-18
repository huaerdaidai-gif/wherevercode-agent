# iOS 半边三件套对照表 (ios-triad-checklist.md)

基线分支：`main = 75c7218c`（安卓三件套合入基线）  
开发分支：`feat/triad-ios-20261119`  
审查基线：安卓端实现 (`MainActivity.kt` / `AgentScreenController.kt` / `FreeProviderPresets.kt` / `ModelRouting.kt` / `DeviceModelDiscovery.kt`)  
约束：仅修改 `ios/` 子树与对照表文档，不碰 `android/`，不合并入 `main`。

---

## 逐项对照验收清单

### ① 设备页 (Devices Screen)

| 规格要求 | 状态 | 对应代码与实现路径 |
| :--- | :---: | :--- |
| **页标题与入口同名「设备」**；页面只留「添加设备」+「已添加设备」两块 | 已做 | [DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift)<br>[FlagshipApp.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/FlagshipUI/FlagshipApp.swift)<br>[FlagshipRouteDestinations.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift) |
| **删「系统与权限说明」节**（挪到设置或去掉） | 已做 | [DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift)（已完全移除 `shortcutsSection`） |
| **安装指引改名与通用终端适配**：「在新电脑上安装 Hand」改为「在新设备上安装 Hand」；支持 Terminal / iTerm / VSCode / SSH / PowerShell / Termux，兼容适配器/连接器、新老项目、Paseo 并行 | 已做 | [DeviceInstallGuide.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Services/DeviceInstallGuide.swift)<br>[DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift) |
| **角标与副文案同源同值**：修复 MacBook-Air 角标「0 个 Agent」与副文案「2 个 Agent」自相矛盾问题，统一使用 `hostedCount` 计算 | 已做 | [DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift#L248-L260) |
| **概念分离硬规则**：模型是模型、Agent 是 Agent、设备是设备；`agy` 不得登记为一台设备；历史数据平滑迁移清理 | 已做 | [DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift)<br>[AgentRegistryStore.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Data/AgentRegistryStore.swift#L170-L195) |
| **解耦删除与确认文案**：删设备只删设备、删 Agent 只删 Agent，互不级联；删除确认文案严格对齐安卓规范（明示不删 Agent/项目/会话） | 已做 | [DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift#L145-L162)<br>[TriadFeaturesTests.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgentTests/TriadFeaturesTests.swift#L180-L210) |

---

### ② Agent 页 (Agents Screen)

| 规格要求 | 状态 | 对应代码与实现路径 |
| :--- | :---: | :--- |
| **页面布局精简**：只留「添加 Agent」+「已添加的 Agent」两块 | 已做 | [FlagshipRouteDestinations.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift#L370-L450)<br>[FlagshipApp.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/FlagshipUI/FlagshipApp.swift) |
| **单入口发现整合**：自动发现、批量扫描、从设备添加整合为一个流程，置于「添加 Agent」弹窗内；点击「自动发现」→ 勾选扫描设备（默认全不勾）→ 扫描探活 → 候选列表（可添加项预勾）→ 批量添加；已存在 Agent 不重复列出 | 已做 | [FlagshipRouteDestinations.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift#L470-L650) (`AddAgentSheet`) |
| **删设备页的「重新发现 Agent」按钮**：全 App 保持仅此一个 Agent 发现入口 | 已做 | [DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift)（移除设备卡上的独立扫描按钮） |
| **配对与发现完全解耦**：配对成功不得自动建 Agent 记录、不得触发任何扫描；发现与配对完全独立 | 已做 | [DevicesView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/DevicesView.swift#L200-L225)<br>[TriadFeaturesTests.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgentTests/TriadFeaturesTests.swift#L190-L205) |

---

### ③ 模型（设置内模型区） (Models Screen)

| 规格要求 | 状态 | 对应代码与实现路径 |
| :--- | :---: | :--- |
| **重命名与分组体系**：模型区标题与分组改名「模型」；已添加列表叫「已添加的模型」；按「模型 API / CLI 会员 / 订阅授权 / 本地模型」清晰分组展示 | 已做 | [SettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/SettingsView.swift)<br>[BrainSettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/BrainSettingsView.swift#L40-L115)<br>[FlagshipApp.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/FlagshipUI/FlagshipApp.swift) |
| **平铺顶层交互**：预设、自动发现、智能路由不藏在折叠里，置于添加区顶层，与已添加列表同页可见 | 已做 | [BrainSettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/BrainSettingsView.swift#L45-L95) |
| **双预设独立**：两个独立入口绝不合并：(a) 常用模型服务商：填服务器地址 → 自动刷新模型列表供选；(b) 免费 token 模型服务商：填注册好的 API key → 自动刷新模型列表供选 | 已做 | [BrainSettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/BrainSettingsView.swift#L130-L280)<br>[FreeProviderPresets.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Brain/FreeProviderPresets.swift) |
| **免费预设 6 家提供商严格对齐**：<br>1. Google AI Studio (`gemini-2.5-flash` / `gemini-2.5-pro`)<br>2. OpenRouter (LIVE 模式动态探活)<br>3. Groq (LIVE 模式探活)<br>4. 硅基流动（UI 明示「须实名认证」）<br>5. 智谱 GLM（FIXED 模式固定名单，无 `/models` 端点）<br>6. Cloudflare Workers AI（FIXED 模式，表单单列 Account ID） | 已做 | [FreeProviderPresets.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Brain/FreeProviderPresets.swift)<br>[BrainSettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/BrainSettingsView.swift#L240-L310)<br>[TriadFeaturesTests.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgentTests/TriadFeaturesTests.swift#L20-L75) |
| **名单外自定义扩展口**：支持用户添加自定义服务商（名称 + 地址 + LIVE/FIXED），与内置名单独立存储并打标「自定义」 | 已做 | [CustomFreeProviders.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Brain/CustomFreeProviders.swift)<br>[BrainSettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/BrainSettingsView.swift#L320-L370)<br>[TriadFeaturesTests.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgentTests/TriadFeaturesTests.swift#L80-L115) |
| **智能路由 (Model Routing)**：默认关闭；默认仅在免费模型间切换；付费模型须用户主动勾选才进入路由池；额度不足/限流/错误时按路由池顺序自动故障转移降级 | 已做 | [ModelRouting.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Brain/ModelRouting.swift)<br>[BrainSettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/BrainSettingsView.swift#L380-L460)<br>[TriadFeaturesTests.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgentTests/TriadFeaturesTests.swift#L120-L155) |
| **模型自动发现 (Device Model Discovery)**：与配对完全解耦，默认关闭且不自动扫描；勾选来源设备后按选定目标探活（支持本机 11434 / 1234 / 8000 与已配对设备）；私有局域网/Mesh 网段 (RFC 1918 / 100.64.0.0/10 Tailscale) 走 `http`，公网走 `https` | 已做 | [DeviceModelDiscovery.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Brain/DeviceModelDiscovery.swift)<br>[BrainConfig.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Brain/BrainConfig.swift#L35-L65)<br>[BrainSettingsView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/BrainSettingsView.swift#L470-L540)<br>[TriadFeaturesTests.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgentTests/TriadFeaturesTests.swift#L160-L185) |

---

### ④ 通用与工程架构规范

| 规格要求 | 状态 | 对应代码与实现路径 |
| :--- | :---: | :--- |
| **文案规范**：界面文案严格约束为「短标签 + 至多一句人话」，无长篇原样需求文档堆砌 | 已做 | 全 UI 检查落实 |
| **对话方选择器规范**：只列 Agent，不出现「已配置模型」节 | 已做 | [ChannelChatView.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Views/ChannelChatView.swift)<br>[ToolRegistry.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent/Brain/ToolRegistry.swift) |
| **Xcode 工程构建集成**：新 Swift 文件与测试套件完全登记进 `project.pbxproj` 编译组与源文件构建阶段 | 已做 | [project.pbxproj](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgent.xcodeproj/project.pbxproj) |
| **单元测试与防劣化测试集**：新增 `TriadFeaturesTests.swift` 覆盖免费预设、智能路由降级、局域网探测协议策略与设备/Agent 独立删除边界 | 已做 | [TriadFeaturesTests.swift](file:///data/data/com.termux/files/home/awm-work/wherevercode-agent/ios/WhereverCodeAgentTests/TriadFeaturesTests.swift) |

---

## 本地门禁与装配验证结果

- `verify_ios_v5_assembly.py`: **8/8 PASS** (70 路由完整，抽屉四主入口严格对齐，0 硬编码凭证)
- `run_e2e_wiring_tests.py`: **9/9 PASS** (SQLite、Keychain、MCP、Channel、Task、Model、File、Hand、Device 迁移)
- `test_device_management_regression.py`: **11/11 PASS** (设备管理、去重、动态更新、无状态泄漏)
- `test_p1_cross_device_fixes.py` & `test_agent_closed_loop.py`: **5/5 PASS** (跨设备执行闭环验收通过)
