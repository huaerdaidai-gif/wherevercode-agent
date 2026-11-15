# 最终 APK 原生验收

验证时间：2026-11-15 UTC。App 源码提交 `67c11024dd66c11ef2efece611b387f1d9e43f2c`，原生导航测试提交 `a5965440240a03fd67cf2ea729ebd1d237183060`。APK SHA-256 见 [摘要](evidence/apk-manifest.json)。

环境：Linux 云容器；Android 11 / API 30 Google ATD，x86_64，360×800、160 dpi；无 KVM，使用软件模拟与 SwiftShader。ATD 默认关闭绘制，本次在启动 App 前设 `debug.hwui.drawing_enabled=1`；下列截图均为原生 App 的真实屏幕抓取，没有 HTML、mock 运行数据或图片修饰。

| 验证 | 结果 | 证据 |
|---|---|---|
| 双 ABI APK / 测试 APK 安装 | 成功 | ARM64 + x86_64；`.ci` 独立 Debug 包；v2 签名验证通过 |
| 70 唯一 route_id / 四父级导航 | 70/70 通过 | [逐路由 CSV](evidence/device/traversal.csv)、[原生测试输出](evidence/native-navigation.txt) |
| 原生导航方法整体及进入已有任务库 | 通过，319.309 秒 | `FlagshipNavTest`，`OK (1 test)` |
| 表单创建 → 真实领取 → 结果持久化 → 详情状态同步 | 通过，80.976 秒 | [原生测试输出](evidence/native-execution.txt)、[真实记录](evidence/device/execution-record.txt) |
| 控制台任务行 → 对应任务详情层 | 手动 ADB/UIAutomator 核对通过 | [断言记录](evidence/console-drilldown.json)、[截图](evidence/device/console-task-detail.png) |

真实任务 `e9234fcf-73ec-4082-a722-fc79d6ca4783`：`queued → assigned → working → failed`，领取 ID `ac6b5bd4fff1460fb16adae0e0a140f6`。失败原因是实际的 `brain_not_configured`。测试没有配置模型或生成假模型回复；测试通过只证明本地创建、领取、失败落库及可见详情同步，不证明模型成功执行、远端控制或审查集成链路通过。

本次实测发现并修复了详情停留在旧状态的问题；新增用例必须找到与数据库终态一致的“状态：失败”后才截图。另验证控制台打开任务详情时，详情层处于控制台之上。前期黑屏、旧状态与测试等待竞争失败记录保留于 `evidence/diagnostics/` 和 `native-first-run.txt`，不算作有效界面验收。

## 有效截图

- [极简首页](evidence/device/home.png)
- [四个同级抽屉入口](evidence/device/drawer.png)
- [原生任务库](evidence/device/native-tasks.png)
- [与持久化终态一致的任务详情](evidence/device/task-detail-result.png)
- [读取本机真实任务记录的控制台](evidence/device/console-overview.png)
- [从控制台打开的同一任务详情](evidence/device/console-task-detail.png)
- [未接通能力明确说明：成本与额度](evidence/device/usage.png)

导航可达并不等于 70 项业务完成。33 个路由是局部已有能力适配，37 个仍为诚实缺口页。真实模型/账号、远端设备、网络断线控制、审批/返工/集成、权限拒绝和旧正式包迁移没有在真实配置环境联测。首次原图 PNG/JPEG 未提供，不能声明像素级对齐；360dp 控制台第 5 档自由度按钮存在换行，仍需后续视觉适配。

## 复现设备验证

先构建 APK 和测试 APK，停止构建进程后启动模拟器（本容器 8 GiB 内存不适合同时编译和软件模拟）。

```bash
adb shell setprop debug.hwui.drawing_enabled 1
adb shell svc power stayon true
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class wherevercode.agent.ui.FlagshipNavTest \
  wherevercode.agent.ci.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e allowTaskCreation true \
  -e class 'wherevercode.agent.ui.NativeTaskCreationTest#localRunnerClaimsCreatedTaskAndRecordsOutcome' \
  wherevercode.agent.ci.test/androidx.test.runner.AndroidJUnitRunner
```
