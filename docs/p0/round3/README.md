# Issue #8：Claude 原生 UI 增量与云端验收

分支 `task/issue-6-p0-native-android`，复审 Draft PR #7。用户已明确取消 K70 连接与真机验收；本轮设备证据仅来自云端 Android 9 x86_64 软件模拟器。无 main 合并、生产部署或旧仓库修改。

## 交付与范围

- `368413c`：首页问候/模型连接条/项目与建议，抽屉主次分组，频道讨论与真实任务分流，原生创建任务表单，详情折叠，审批真实 deadline 倒计时。
- `a3b03e4`：模拟器启动发现 `BigInteger.TWO` 在 Android 9 不可用；改用 `BigInteger.valueOf(2)`，保持加密计算不变。修复后已见欢迎、首页和创建表单。
- `cacca58`：任务库优先、当前会话调试信息默认折叠；增加需显式 opt-in 的 UI instrumentation。最终 APK 基于此源码提交，debug 包 `wherevercode.agent.ci`，版本 `0.3.0-p0-r3` / 7。

[18 屏验收表](acceptance-18.csv)逐行记录原稿定位、组件、事件、增量及剩余差异。18 屏均未完成全量视觉/交互签收，不能声称完整还原。原稿已找到，准备包 ZIP 不再作为阻塞。Claude C16 异常/C17 欢迎与旧能力编号 S16 自动化/S17 网络不同。

## 验证结果

UI/控制/审批相关 JVM/Robolectric 12 suites、115 tests 全通过；加密 2 suites、12 tests 全通过，合计127个不同测试。最后任务页折叠变更再跑 MainLayoutTest 5、MainActivitySmokeTest 2、TaskComposerTest 4，11项通过（为前述测试的重复回归，不额外计入127）。结果见 test-results.csv、layout-test-results.txt 和证据包 XML/日志。

云端旧版修复 APK 安装成功；实际截图包含 C01 首页、C13 创建表单、C17 欢迎。截图来自 a3b03e4，不代表 cacca58 最新任务页布局已运行签收。误命名且画面不符的截图不纳入交付。

原生 instrumentation 1项运行失败：System UI ANR 遮挡页面，找不到 buttonMenu，在创建任务前失败。环境另出现 SurfaceFlinger SIGABRT/ubsan 以及 goldfish RIL 崩溃。已记录系统故障，不据此推断任务业务缺陷或通过。没有真实模型请求、任务完成、远端控制回执、实际工具写入审批的完整运行证据。Robolectric 的 Room 建任务和审批测试不替代该闭环。

## 真实接线与剩余问题

[接线矩阵](backend-wiring.csv)分别列实现与验证状态。IMPLEMENTED 指存在源码路径；PARTIAL 指缺少部分环节；MISSING 指无实现；VERIFIED 仅限列明的测试边界。本轮未重复的上轮验证保留链接，不升级为本轮通过。

1. 原稿尚存差异：内联 Diff、最近会话布局、筛选/重派、文件树与工作台、设备/成员向导、欢迎/模型连接流程等，详见18屏表。
2. 云端完整导航、提交真实任务、执行、控制回执、批准/拒绝写入尚未通过。需稳定模拟器与明确的测试执行端后复验；K70不在当前要求内。
3. 自动化规则账本、耐久离线 outbox、HTTPS中继、manifest到运行时激活仍 MISSING。[最小实现方案](minimum-implementation.md)明确首个真实闭环与验收，不将规划当实现。
4. 频道创建已接本地 TaskStore，尚无服务端任务关联回写或耐久离线消息队列；queued不等于已送达或已执行。
5. 最终 arm64 APK 仅构建/签名验证；没有 arm64运行证据。

Issue #8与相关P0验收继续开放，PR保持Draft。APK和证据私有分享地址及SHA256见 PR #7；不包含手机配对截图、密钥、签名材料或真实凭据。
