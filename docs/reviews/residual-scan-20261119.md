# 全仓残留字符串扫描（差异 14 + D 类文案）

- 基线：main `bafe277c`（huaerdaidai-gif/wherevercode-agent）
- 日期：2026-11-19
- 范围：`android/`、`ios/` 全部已跟踪文件（`git grep` 于基线提交执行）
- 依据：《双端对齐最终实施方案（定版）》差异 14（选择器标题改「选择对话方」，全仓清理残留旧字符串）与第三节 D 类（文案微差，P3 一次性统一）
- 性质：只读扫描报告，不含代码改动。改动分别归 B1（差异 14）与 B4（D 类统一）批次。

## 汇总

| 编号 | 定版标准 | 命中总数 | 生产代码 | 测试/注释 | 结论 |
|---|---|---|---|---|---|
| 差异 14 | 旧标题「选择模型 / Agent」应清零，改「选择对话方」 | 9 | 5 | 4 | 双端均有残留，安卓为主 |
| D1 | 全部功能弹层标题「全部功能（89 项）」 | 16 | 4 | 12 | iOS 导航标题形式不一致（半角括号） |
| D2 | 已添加模型列表标题「已添加的模型」 | 18 | 2 | 16 | 安卓渲染标题仍是旧形「已添加模型」 |
| D3 | 密钥状态副文「已填 Key / 未填 Key」 | 6 | 3 | 3（无关） | 标准对仅 iOS 一处；安卓无此副文 |
| D4 | 概览卡按钮随 Token 状态显示「配置 Token / 管理连接」 | 4 | 4 | 0 | iOS 已达标；安卓只有「管理连接」 |

另计参考命中：新标题「选择对话方」5 处（iOS 已迁移）；「选择模型」家族变体 2 处（见差异 14 节末）。

---

## 差异 14 — 旧选择器标题「选择模型 / Agent」（9 处）

生产代码（5 处，必须改）：

| 文件:行 | 内容 |
|---|---|
| android/app/src/main/java/wherevercode/agent/MainActivity.kt:2322 | `.setTitle("选择模型 / Agent")` |
| android/app/src/main/java/wherevercode/agent/MainActivity.kt:2345 | `.setTitle("选择模型 / Agent")` |
| android/app/src/main/java/wherevercode/agent/MainActivity.kt:9552 | `"选择模型 / Agent ⌄"`（选择器标签） |
| android/app/src/main/res/layout/screen_chat.xml:261 | `android:text="选择模型 / Agent ⌄"` |
| ios/WhereverCodeAgent/Data/LocalData.swift:2044 | `guard let cfg else { return "选择模型 / Agent" }`（无配置时的回退标签） |

测试（4 处，随生产改动同步改）：

| 文件:行 | 内容 |
|---|---|
| android/app/src/test/java/wherevercode/agent/ui/BrainCapsuleVisibilityTest.kt:29 | 注释中提及「选择模型 / Agent」选择器 |
| android/app/src/test/java/wherevercode/agent/ui/BrainCapsuleVisibilityTest.kt:100 | 断言字符串 `"选择模型 / Agent ⌄"` |
| android/app/src/test/java/wherevercode/agent/ui/MainLayoutTest.kt:117 | 注释中提及默认文案 |
| android/app/src/test/java/wherevercode/agent/ui/MainLayoutTest.kt:120 | 断言字符串 `"选择模型 / Agent ⌄"` |

参考 — 新标题「选择对话方」现有命中（5 处，均为 iOS，安卓 0 处）：

| 文件:行 | 内容 |
|---|---|
| ios/WhereverCodeAgent/Data/LocalData.swift:2023 | `.navigationTitle("选择对话方")`（生产，sheet 标题已迁移） |
| ios/WhereverCodeAgentUITests/WhereverCodeAgentUITests.swift:223 | UI 测试引用 |
| ios/WhereverCodeAgentUITests/WhereverCodeAgentUITests.swift:224 | UI 测试引用 |
| ios/WhereverCodeAgentUITests/WhereverCodeAgentUITests.swift:225 | UI 测试说明 |
| ios/WhereverCodeAgentUITests/WhereverCodeAgentUITests.swift:227 | UI 测试断言（选择器不得含「已配置模型」节） |

同族变体（非旧标题原文，B1 清理时一并核对）：

| 文件:行 | 内容 |
|---|---|
| android/app/src/main/res/layout/screen_chat.xml:267 | `android:contentDescription="选择模型或 Agent"`（无障碍描述，与 261 行同控件） |
| android/app/src/main/res/layout/screen_settings.xml:219 | `android:text="选择模型"`（设置页模型选择行，语境不同，仅登记） |

---

## D1 — 全部功能弹层标题（16 处）

定版：弹层标题「全部功能（89 项）」。实际渲染标题均为动态计数，渲染形为「全部功能（N 项全表）」，与定版字面差「全表」后缀，B4 统一时一并定夺。

生产渲染标题（4 处）：

| 文件:行 | 形式 | 判定 |
|---|---|---|
| android/app/src/main/java/wherevercode/agent/MainActivity.kt:1661 | `"全部功能（${EntryRegistry.ENTRIES.size} 项全表）"` | 动态计数，全角括号 |
| android/app/src/main/java/wherevercode/agent/ui/DrawerView.kt:563 | `"全部功能（${totalCount} 项全表）"` | 动态计数，全角括号 |
| ios/WhereverCodeAgent/FlagshipUI/FlagshipApp.swift:485 | `Text("全部功能（\(EntryRegistry.ENTRIES.count) 项全表）")` | 动态计数，全角括号 |
| ios/WhereverCodeAgent/FlagshipUI/FlagshipApp.swift:808 | `.navigationTitle("全部功能 (\(EntryRegistry.ENTRIES.count) 项)")` | **形式不一致**：半角括号+空格、无「全表」，与同文件 485 行弹层标题不同形 |

注释/说明/测试（12 处）：

| 文件:行 | 备注 |
|---|---|
| android/app/src/main/java/wherevercode/agent/MainActivity.kt:1638 | 帮助文案中提及「全部功能（89 项全表）」 |
| android/app/src/main/java/wherevercode/agent/ui/DrawerView.kt:26 | 注释 |
| android/app/src/main/java/wherevercode/agent/ui/DrawerView.kt:29 | 注释 |
| android/app/src/main/java/wherevercode/agent/ui/DrawerView.kt:224 | 注释 |
| android/app/src/main/java/wherevercode/agent/ui/EntryRegistry.kt:386 | 注册表条目描述句中含「全部功能」，非标题 |
| ios/WhereverCodeAgent/FlagshipUI/FlagshipApp.swift:477 | 注释，写「83 条全功能目录」——计数已过期（现 89 项），注释与现实不符 |
| android/app/src/test/java/wherevercode/agent/ui/DeviceTapRefreshTest.kt:111 | 注释 |
| android/app/src/test/java/wherevercode/agent/ui/DeviceTapRefreshTest.kt:112 | 注释 |
| android/app/src/test/java/wherevercode/agent/ui/DeviceTapRefreshTest.kt:121 | 断言说明 |
| android/app/src/test/java/wherevercode/agent/ui/DeviceTapRefreshTest.kt:124 | 断言说明 |
| android/app/src/test/java/wherevercode/agent/ui/DrawerViewTest.kt:74 | 断言（contains 全部功能） |
| android/app/src/test/java/wherevercode/agent/ui/EntryRegistryTest.kt:36 | 注释 |

---

## D2 — 已添加模型列表标题（18 处）

定版：「已添加的模型」。旧形「已添加模型」（无「的」）仍是安卓的实际渲染标题。

标准形「已添加的模型」（6 处）：

| 文件:行 | 判定 |
|---|---|
| ios/WhereverCodeAgent/Views/BrainSettingsView.swift:378 | `Section("已添加的模型")` — 唯一以标准形渲染的列表标题（iOS） |
| ios/WhereverCodeAgent/Views/BrainSettingsView.swift:3 | 注释 |
| ios/WhereverCodeAgent/Views/BrainSettingsView.swift:210 | 注释 |
| ios/WhereverCodeAgent/Views/BrainSettingsView.swift:361 | 注释（MARK） |
| android/app/src/main/res/layout/screen_models.xml:35 | 副标题句「添加模型，管理已添加的模型。」— 句中含标准形，非列表标题 |
| android/app/src/test/java/wherevercode/agent/ui/ModelPageLayoutTest.kt:115 | `assertFalse(joined.contains("已添加的模型"))` — 红线守卫断言（设置页大脑分节不得出现），非 UI 文案 |

旧形「已添加模型」（12 处）：

| 文件:行 | 判定 |
|---|---|
| android/app/src/main/res/layout/screen_models.xml:250 | `android:text="已添加模型"` — **安卓模型页实际渲染的列表标题，缺「的」，D2 主要残留** |
| android/app/src/main/res/layout/screen_models.xml:4 | 布局注释 |
| android/app/src/main/res/layout/screen_models.xml:244 | 布局注释 |
| android/app/src/main/java/wherevercode/agent/MainActivity.kt:11160 | 文档注释 |
| android/app/src/main/java/wherevercode/agent/MainActivity.kt:11200 | 文档注释 |
| android/app/src/main/java/wherevercode/agent/ui/DrawerController.kt:83 | 注释 |
| android/app/src/main/java/wherevercode/agent/ui/ModelListRows.kt:10 | 文档注释 |
| android/app/src/main/java/wherevercode/agent/ui/ModelListRows.kt:28 | 文档注释 |
| android/app/src/main/java/wherevercode/agent/ui/ModelListRows.kt:135 | 文档注释 |
| android/app/src/test/java/wherevercode/agent/ui/ModelListRowsTest.kt:15 | 注释 |
| android/app/src/test/java/wherevercode/agent/ui/ModelPageLayoutTest.kt:73 | 断言 `contains("已添加模型")`（随 250 行改动同步改） |
| android/app/src/test/java/wherevercode/agent/ui/ModelPageLayoutTest.kt:80 | 断言顺序比较用 `"已添加模型"`（随 250 行改动同步改） |

---

## D3 — 密钥状态副文「已填 Key / 未填 Key」（6 处）

标准形（1 处）：

| 文件:行 | 内容 |
|---|---|
| ios/WhereverCodeAgent/Views/BrainSettingsView.swift:505 | `hasKey ? "已填 Key" : "未填 Key"` — 唯一使用标准对的生产代码 |

安卓侧标准形 0 处：安卓模型/大脑列表没有「已填 Key / 未填 Key」副文，B4 统一时需新增或明确豁免。

同语境变体（2 处）：

| 文件:行 | 内容 |
|---|---|
| ios/WhereverCodeAgent/Views/BrainSettingsView.swift:67 | `"尚未填写 API Key"` — 无 Key 状态的另一种措辞，与 505 行标准对并存 |
| ios/WhereverCodeAgent/Brain/BrainEngine.swift:954 | 对话提示句中「选择已填写 API Key 的大脑」— 散文形「已填写」，非副文 |

无关命中（3 处，「未填写」但与 Key 状态无关，仅登记排除）：

| 文件:行 | 内容 |
|---|---|
| android/app/src/main/java/wherevercode/agent/brain/AgentChatRouter.kt:151 | 「未填写设备信息」 |
| android/app/src/main/java/wherevercode/agent/brain/cli/CliMembershipBrainAdapter.kt:102 | 「未填写 CLI 二进制名」 |
| android/app/src/test/java/wherevercode/agent/ui/PlatformAgentFormTest.kt:53 | 注释「未填写频道时…」 |

---

## D4 — 控制台概览卡按钮「配置 Token / 管理连接」（4 处）

定版：概览卡展示规范化根地址，按钮随 Token 状态切换（未配显示「配置 Token」，已配显示「管理连接」）。

| 文件:行 | 内容 | 判定 |
|---|---|---|
| ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:4806 | `Text(appState.hasToken ? "管理连接" : "配置 Token")` | **已达标**：与定版规则完全一致 |
| android/app/src/main/java/wherevercode/agent/ui/ConsoleScreenController.kt:276 | `"管理连接"` 写死 | **残留**：无「配置 Token」状态，未配 Token 时按钮文案与定版不符 |
| ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:2016 | 凭证状态行 `"已保存 Token (已授权)" / "未配置 Token (未授权)"` | 不同语境（凭证状态行），半角括号，仅登记 |
| ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift:4864 | 角标 `"已配置 Token" / "未配 Token"` | MCP 卡角标语境（关联差异 6），仅登记 |

---

## 扫描方法（可复现）

于基线 `bafe277c` 执行（`git grep` 只覆盖已跟踪文件）：

```sh
git grep -n -E '选择模型[[:space:]]*/[[:space:]]*Agent' bafe277c -- android ios
git grep -n -F '选择对话方' bafe277c -- android ios
git grep -n -F '全部功能' bafe277c -- android ios
git grep -n -E '已添加模型|已添加的模型' bafe277c -- android ios
git grep -n -E '已填[[:space:]]*Key|未填[[:space:]]*Key|已填写|未填写' bafe277c -- android ios
git grep -n -E '配置[[:space:]]*Token|管理连接' bafe277c -- android ios
```

注：D2 必须用交替式 `已添加模型|已添加的模型` 检索；`已添加的?模型` 在 git grep 的正则实现下会漏检无「的」旧形（本次扫描已实测纠正）。
