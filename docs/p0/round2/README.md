# Issue #6 P0 第二轮交付与未完成项

日期：2026-11-14 UTC。分支 `task/issue-6-p0-native-android`，起点 `0ccd1ac5e9697957a54867e49a8a4a353bd73a99`，复审 PR #7。**第二轮未达到验收完成条件；PR 保持 Draft，Issue 保持开放。**

## 代码增量

- 保留原生工程和既有功能。聊天远端轮次与任务观察轮次的暂停/继续状态只在确认回执后更新；停止/改目标收到确认才结束观察。未接线、发送失败或未确认时保留观察并如实提示。
- 回执按 taskId 与最新 operationId 隔离，迟到/重复回执不能更新新轮次。远端运行与任务观察捕获自己的时间线；旧 job 的 finally 仅在归属仍匹配时清当前运行态，避免强制新对话被旧 job 清成空闲。该检查不等于耐久聊天控制账本，进程死亡后仍须联测。
- 聊天控制按钮点击高度由 36dp 增至 48dp；未为凑视觉对齐重写全部 UI。
- 独立 `server/hand_server.py` 修复快退出子进程 stdout 未排空导致丢失进度/result，并关闭 stdout。APK 内置 Hand 已有输出排空逻辑，本轮加其回归，不替换成较旧独立服务。
- 增加 K70 隔离包证据采集脚本。脚本只采安装/启动，不能替代人工导航、任务和审批。

## 输入与逐屏对照

已获取的原始来源、哈希及限制见 [design-sources.md](design-sources.md)。找到 Claude 18 屏与旗舰 70 页 HTML 及架构规格；仍未找到用户提及的准备包 ZIP 和其中 UI 映射表。没有声称读过这些缺失文件。

- [acceptance-18.csv](acceptance-18.csv)：按 Claude 原始 board 导航重做 C01–C18；逐屏组件/真实调用/权限/源码差异/四类状态/视觉/测试/设备证据。
- [prototype-70-pages.csv](prototype-70-pages.csv)：旗舰 HTML 真实 70 页，不冒充 70 个操作入口。
- [wiring-70.csv](wiring-70.csv)：上一轮 70 个操作入口的修订接线盘点。
- [backend-wiring.csv](backend-wiring.csv)：真正闭环段及本轮验证范围。
- [minimum-implementation.md](minimum-implementation.md)：S16 规则、S17 耐久队列/多网络、扩展激活执行器的最小实现与验收方案。三者仍未实现完整产品闭环。

状态口径：IMPLEMENTED 仅表示相应调用链有源码；PARTIAL 表示缺部分行为/页面；MISSING 表示完整产品接线缺失；VERIFIED 只出现在单独的验证栏，必须看其范围。源码测试通过不提升 UI/真机状态。所有原稿视觉与 K70 操作仍未验收。

## 实际测试

最终 Android 定向回归 **73/73 通过，0 skipped**；Python Hand **23/23 通过**。明细见 [test-results.csv](test-results.csv)。Android 测试包含 Robolectric、注入 sender/runner 和 loopback MCP HTTP；Python 使用 socket、临时目录和假 CLI，不调用生产服务或真实云端 Agent。

有意义的失败均保留在证据包：

1. Hand 首轮 21 项中 1 项失败：快速退出进程无 progress。修复并补独立/内置 Hand 排空回归后 23 项通过。
2. Android 首次测试因 Robolectric 动态 SDK 下载未继承环境代理而报 ConnectException，属于环境失败；配置测试 JVM 代理后重跑。
3. 随后 72 项中 1 项既有「force new conversation」测试失败，暴露旧 finally 清空新轮次。修复并加入可延迟旧任务完成的竞态回归后 73 项通过。

复现：Java 17、Gradle 8.2、Android SDK 34 / Build Tools 33.0.1（AGP 自动选择）、AGP 8.1.4、Kotlin 1.9.0、Chaquopy 15.0.1。构建机 Python 3.12，Chaquopy 运行目标 Python 3.11；构建提示跳过 pyc 预编译，源码已打包，真机 Python 运行尚待验证。未改 targetSdk 28 的既有执行取舍。

```bash
python -m unittest discover -s server -p 'test_*.py' -v
gradle -p android :app:testDebugUnitTest \
  --tests '*ChatViewModelRemoteAgentTest' --tests '*TaskControlActionsTest' \
  --tests '*TaskClaimControlTransactionTest' --tests '*ExtensionManifestTest' \
  --tests '*ApprovalSafetyTest' --tests '*ExecutionFenceTest' \
  --tests '*DispatchClaimExecuteTest' --tests '*McpDispatchHttpE2ETest' \
  --tests '*ExtensionToolBridgeTest' --tests '*LocalTaskRunnerAssignTest'
gradle -p android :app:assembleDebug
```

环境代理/信任库配置仅在构建机，未写入工程或提交凭据。没有运行整个 Android 测试集，也没有把这些定向测试当作全量验收。

## APK 与设备证据

交付隔离 debug 包 `wherevercode.agent.ci`，arm64-v8a，沿用版本 `0.3.0-p0` / 6；以 BuildConfig.COMMIT_SHA 和 APK SHA-256 唯一定位。本次提交后再构建最终 APK，构建 SHA、包/签名/ABI 检查、哈希与完整日志保存在交付包 `artifact-manifest.json`、`apk-identity.txt`、`apk-signature.txt`、`apk-build.log`。不把本地 debug 私钥放进包。

`adb devices -l` 无设备，**K70 安装/运行/导航/任务/控制/审批全部 NOT_RUN；真机截图 MISSING（0 张）**。详见 [device-acceptance.md](device-acceptance.md)。未用浏览器/原型截图冒充真机证据。

## 剩余问题与下一步依赖

- 必须取得准备包 ZIP/原 UI 映射表及可正常渲染的视觉稿，完成 C01–C18 的真正视觉与交互增量；当前逐屏表是差异审计，不能报 18 屏通过。
- 必须提供可从当前环境访问的 K70 ADB/远程入口，执行设备验收清单并补真实截图、脱敏日志和失败记录。
- 原稿创建任务统一表单、频道作为任务发送、整合工作台、团队空间等有明确差异；参见每行缺口。频道消息当前不能当作派单成功。
- S16 规则/触发账本、S17 outbox/中继/路由策略、manifest 激活执行器仍 MISSING。先按最小方案逐个贯通，不做假开关。
- UI 机械测试 verdict 仍 NOT_RUN，不能由工具返回文字“测试通过”替代真实测试产物。
- 真实 CLI/Node 二进制、提供方认证、后台保活、网络恢复、权限过期与进程死亡场景仍需设备联测。

仅提交此开发分支并更新 PR #7；未合并 main、未部署生产、未修改旧仓库、未发布公共 Release。
