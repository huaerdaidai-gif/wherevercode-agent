# Issue #6：P0 审计与实施门禁

审计基线：`origin/main` = `a9206e169b3ed0052a3245e9ca3a895290791bad`。
工作分支：`task/issue-6-p0-native-android`。仅克隆和操作本独立新仓库。

## 设计来源与验收口径

负责人在本次会话答复“需要你设计”。因此 `screens-18.csv` 是本次新设计的 18 屏信息架构，`ui-backend-70.csv` 是本次新设计的 70 个操作入口，**不是 Claude 原稿逐屏验收结果**。原始图/HTML/准备包均 MISSING。不得用这两张表声称还原了未提供的视觉稿；不得把入口数当页面数。`existing-entries.csv` 单独盘点实际注册表，IMPLEMENTED 仅为代码自报，不是设备验收。

每张表给出组件、已有调用链、权限、测试检索名、源码状态和缺口。测试名为已有验证线索，运行结果以交付报告为准。SOURCE 表示源码有实现；PARTIAL 表示部分实现；MISSING 表示尚缺产品实现。

## 分支核实

- main 已合并任务控制修复与 MCP 派单，但不是所有最新 Android 修改。
- `origin/fix/android-ux-13-20261114` = `8b46a8a`：6 个未合入 UX 提交，21 文件变化；含配对/在线文案、诊断折叠、导航等。
- `origin/fix/agent-control-dispatch-closeout-20261113` = `882d45f`：未合入的远端控制、租约、投递与签名 CI 修改。
- iOS 分支非本任务范围。不为凑“最新”混合未验收分支，基于 main 做最小修复；保留这些差异供后续集成复审。

## 构建事实

AGP 8.1.4、Kotlin 1.9.0、Chaquopy **15.0.1**（README 的 14.0 过期）、Python 3.11、compileSdk 34、minSdk 26、targetSdk 28、Java 17、arm64-v8a。无 Wrapper，无 main workflows。debug applicationId 为 `wherevercode.agent.ci`，与原包共存；不构建替换原包的 compat，不提交签名材料。target 28 是执行数据目录 CLI 的已有取舍；不扩大为 Play 支持承诺。Node 二进制未随源码提供，APK 能安装不代表 CodeBuddy/Codex CLI 已可运行。

## 真实闭环与 P0 缺陷

1. `ChatViewModel` 路由任务意图 → `ChatTaskDispatcher` 写真实 TaskStore（sessionId=null），轮次镜像不能被执行器误认领。
2. `LocalTaskRunner` / `RemoteTaskPump` → `TaskScheduler` 原子认领，attemptId/lease/epoch/goalVersion 栅栏。远端 Hand 是本仓库 server 与安装 assets；不依赖旧项目数据库。
3. `BrainEngine` Flow / Hand progress → 步骤与事件 → result/review → `PipelineOrchestrator` 审查；UI 本机机械测试 verdict 为 NOT_RUN，不能伪造测试通过。
4. 工具写入走 `AndroidApprovalGate`/ExecutionFence；pipeline plan 与 review 是另一层门禁。
5. **P0-01**：MainActivity.stopTask 直接将远端活动任务记 cancelled，未等待执行回执。必须接 ControlPlane，并把待确认与已确认分开。
6. **P0-02**：deleteTaskRecord 吞停止异常后删除执行凭证/事件。必须在停止确认后才允许删除；未确认保留任务和审计。
7. **P0-03**：详情只给当前聊天暂停，无通用继续入口；排队任务改目标直接写字段不涨 goalVersion。改用控制端口，活动远端改目标需先暂停确认，避免旧目标继续被展示为完成。
8. **P0-04**：扩展注册表只有七类身份、无统一 schema/权限清单。新增版本化 manifest 最小导入验证，不自动安装/执行扩展，不把声明当授权。
9. **P0-05**：部分 ChatViewModel 远端发送文案和布尔标记先于确认；需要后续真实连接回执联测，首轮禁止声称全闭环设备验收完成。

## 自动化、唤醒、网络能力与缺口

已有：单 Agent runner、多成员并发认领、租约回收、attempt 去重/防迟到、重试副本、流水线返工上限、控制审计与执行栅栏。ExtensionRegistry 内 wakeup 只是登记，不能推导出耐久定时器。

MISSING：统一事件/定时/被动/主动规则服务、跨重启触发去重与 nextRun、预算预留/结算、规则审批页面、统一断点恢复。后续自动化设计：triggerId+ruleVersion+eventId 幂等键，事务写触发账本与 queued 任务；指数退避并限定次数/截止时间；进程恢复只重放未确认投递；达到预算或需审批时 blocked，不绕过现有控制权。

已有网络：本机执行、Direct/LAN/mDNS、Hand 密封帧、HTTPS 模型 HTTP/SSE、控制台 WS、MCP stdio/HTTP/SSE。VPN 是用户外部路由，不提供 VPNService。MISSING：统一网络策略页、HTTPS 中继端到端确认、耐久离线发件箱/幂等重放。降级设计：本地→用户授权直连/LAN/VPN→用户配置 HTTPS 中继；离线排队只保存已授权任务意图，过期/权限版本变化需重新审批，不把断网当成功。

## 18 屏统一交互与视觉设计

原生 Activity + XML/Android Views，沿用 UiKit 色彩/字号、浅深主题与抽屉导航。首页是对话入口；项目/任务/团队/设备/成果可从抽屉返回；详情有明确返回列表。自动化与网络在完整实现前显示缺失说明，不伪造开关。

所有 18 屏共同验收清单：首次加载有加载提示；空状态给真实创建入口；失败显示可重试且不清旧结果；离线显示最后更新时间并禁用需网络的提交；返回不丢项目/会话绑定；旋转/主题切换不重发任务；48dp 点击目标、可读对比度、TalkBack 标签。**以上是设计要求，尚未逐屏真机验证。**

## 实施顺序与验收门禁

A. 先提交本审计与矩阵，记录未提供稿件和分支事实。
B. 原生任务控制接 ControlPlane，补待确认文案、暂停/继续与安全删除回归。
C. manifest v1 支持 Agent/Model/Harness/MCP/Skills/Automation/Network/Device/UI Extension 九类；验证未知版本、权限、兼容映射，默认不执行。
D. 配置本地工具链，跑 Python Hand 与 Android 单测，构建隔离 debug APK；记录失败也保留日志。
E. APK 检查签名、包名、ABI、安装与原生启动（有模拟器才执行）；没有 arm64 真机时明确列未验证项。生产凭据、部署、公开发布不在授权范围。

剩余完整自动化/网络实现与 18 屏设备验收没有完成时，Issue 必须保持开放。

## 第一轮原生实现（审计后）

- `TaskControlActions` 将任务页分派任务控制接到已有 ControlPlane；请求与状态检查持有 TaskStore 锁，避免 queued 检查与认领之间竞态。聊天镜像仍由原会话控制。
- 删除须已终态，或停止同时返回 ok+confirmed 且再次读到终态；等待回执时保留任务、凭证与日志。详情显示持久化控制态。
- 未认领目标修改走 ControlPlane 涨 goalVersion；活动目标暂拒绝直接替换（暂停的远端进程仍可能持有旧 prompt），指引用户停止确认后创建新目标。
- MCP 原生页面加入清单 v1 查看、JSON 导入、严格校验、权限预览、保存与重复拒绝；九类清单持久化于独立 preferences。该最小实现只管理声明，不注册执行器、不安装扩展、不授予权限，后续 Harness/Automation/Network 执行接口仍 MISSING。
- 包版本 `0.3.0-p0` / 6，debug 包继续 `.ci` 隔离。没有更改生产端点、凭据、签名策略或旧项目。
