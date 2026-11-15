# WhereverCode V5 Android — 交接说明（2026-11-15）

> 本文只写当前真实状态。未完成的项一律标注「未完成」，不写“看起来做了”。

## 0. 一句话现状

Android 收尾线在 `feature/v5-android-cloud` 上推进：入口注册表已与 GPT
《双端唯一功能主清单 v1.0》对齐为 **89 条**，四主入口（对话 / 项目 /
频道 / 控制）真实可用，Android CI 全绿。剩余阻塞主要是服务端接口
缺失（见 §5），不在 Android 客户端范围内。

## 1. 仓库与分支

| 项 | 值 |
|---|---|
| 仓库 | `huaerdaidai-gif/wherevercode-agent`（**不是**旧的 `wherevercode`） |
| 分支 | `feature/v5-android-cloud` |
| 本轮提交 | 见本提交（父提交 `789bb383`） |
| GitHub 访问 | 读写走 custom.github-agent connector；CI 读取走 custom.github |

## 2. 入口注册表与主清单

- `ui/EntryRegistry.kt` 共 **89 条**：已实现 30 / 待开发 59。
- 本轮补登 3 条（主清单 v1.0 已裁决采纳、审计证实页内已实现但未登记）：
  `feature_code_editor`（代码编辑器，落项目页）、`feature_autonomy`
  （自主度，落设置页）、`feature_app_update`（应用更新，落设置页），
  均为 SCREEN 入口，`MainActivity.openRegistryEntry` 已加对应落点。
- 注册表是双端共用入口清单的 Android 事实来源；状态口径与主清单
  v1.0 一致：待开发点开只给诚实提示，绝不伪装已实现。
- `EntryRegistry.validate()` 自检须为空；`EntryRegistryTest` 断言总数 89。

## 3. 四主入口现状

| 入口 | 状态 |
|---|---|
| 对话 | 已实现：大脑对话 / 单兵对话，输入框操作入口真实可用 |
| 项目 | 已实现：项目列表与项目页（任务 / 代码 / 文件 / 成果）；代码编辑已接线（WorkspaceEditSession） |
| 频道 | 部分可用：已配置频道的消息协作真实；完整频道列表、频道管理、频道文件、私聊待服务端接口（§5） |
| 控制 | 已实现：本机真实快照（计数 / 事件 / 日志 / 审批深链），非空壳 |

设置从抽屉底部账号进入，不是一级菜单；「全部功能」是跨导航索引。

## 4. CI 与证据口径

- 验证只走 GitHub Actions 的 Android CI（本机无 Java 环境）。
- 测试总数以最近一次 CI 的 test-reports 工件为准：本轮基线为 JVM
  测试 2730 个（2729 通过 / 1 跳过 / 0 失败，CI #235）；本轮提交后
  以新一轮 CI 结果为准，只许增、不许减、0 失败。
- 模拟器证据：v5-runtime 工件 7 张截图 + `V5_RUNTIME_OK`。
- 本轮只有 CI 模拟器证据，**没有真机验证**；真机验收归 Funk 本人。

## 5. 未解阻塞（非 Android 客户端可解）

- 服务端缺接口：完整频道列表、频道管理 CRUD / 成员权限、频道文件
  （上传 / 网盘引用 / 预览 / 下载）、私聊服务（含服务端禁
  Agent↔Agent 权限）、用量与成本数据。客户端条目已登记为诚实待开发。
- iOS 侧对齐与 ADAPTER_PENDING 项归 AGY；本分支不动 iOS 文件。

## 6. 关键文档

- 双端唯一功能主清单 v1.0（GPT 裁决版）：https://drive.google.com/file/d/1_FJWWFAGyMCl-S_Hvcyk3P9V9xv3P1-TwV6NTrpjRQ4/view
- Android 入口清单独立优化审计：https://drive.google.com/file/d/1u17rMfsWeBW58E01Bwx89GRNPzT2sc8J/view
- Android 路由回填表（对 iOS 70 屏）：https://drive.google.com/file/d/10oGrF4gs7Px53fk2svi9DXqMe9IUv0Yv/view

## 7. 红线

- 不合 main、不部署生产、不动旧仓库 `huaerdaidai-gif/wherevercode`。
- 安全四项中危 + Hand stdio 零认证未过前，**不得宣布正式发布就绪**；
  CI 产出的 debug APK 不是正式升级包。
- 未接通的能力如实标待开发，不用假数据模拟成功。
