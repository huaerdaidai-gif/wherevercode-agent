# K70 写轮次泄漏 + 工具审批安全 + 自主开发闭环（2026-11-09）

分支：`fix/android-tool-approval-20261109`
范围：只改独立 Android 仓库；不连旧 WhereverCode 平台、不改生产服务、不合并 main、不部署、不更新 K70。

## 1. 现象与根因

### 1.1 `workspace_round_active` / `hand_run_failed`（最高优先级）

K70 真机第一轮 `write_file` → `read_file` 成功；第二轮任务失败：

- `workspace_round_active`：`FileWorkspaceAdapter.beginRound()` 拒绝新建轮次；
- 遗留轮次 id：`542ee5a8-1cba-43e1-afae-1c74f0ca1a45`；
- 之后 `write_file` / `run_command` 都被阻止。

根因在 `ToolRegistry`：

1. `handleWrite()` / `handleRun()` 只调用 `beginRound(taskId)`，**全文件没有
   任何 `finishRound()`**。`rounds` map 按 taskId 记录轮次 id，但从不结束，
   轮次在 `ToolRegistry`（≈App 进程）生命周期内永久活动。
2. `FileWorkspaceAdapter` 的并发保护正确：同一时间只允许一个活动轮次，
   不同 taskId 直接抛 `workspace_round_active`。所以第一轮成功后，第二轮
   （新会话=新 taskId）必然被自己的旧轮次挡住。
3. `handleRun()` 的兜底 `catch (e: Exception)` 把 `WorkspaceException` 也吞成
   `hand_run_failed`，导致真机日志看起来像"命令本身失败"，掩盖了锁轮次的
   真实原因。
4. 没有异常/取消清理，也没有启动恢复：审批拒绝后不会开轮次（审批在
   begin 之前），但写失败、命令异常、协程取消都会留下活动轮次。

### 1.2 审批安全缺口

- 弹窗只显示 path；`run_command` 不显示命令，`write_file` 无法判断内容规模；
  反过来，直接拼接原始参数也可能把 `sk-…` / `Bearer …` / `--api-key …`
  带进弹窗。
- 同一逻辑调用可能重复弹窗；并发调用时旧实现直接把第二个请求判成
  `user_denied`（用户根本没看到第二个弹窗）。
- 授权与执行之间没有第二道检查：参数被改动、Activity 在"允许"后被销毁，
  仍然可能执行。
- `AndroidApprovalGate` 是 Activity 级、`onDestroy()` 里 `close()`：旋转屏幕
  后引擎仍持有已永久关闭的旧门，后续所有审批被永久拒绝。

## 2. 修复内容

### 2.1 写轮次生命周期（`core/workspace/WorkspaceAdapter.kt`、`brain/ToolRegistry.kt`）

- 删除 `ToolRegistry` 的长期 `rounds` map；新增 `withWriteRound()`：
  - 用进程内 `ReentrantLock` 串行化所有"可能写工作区"的工具调用；
  - `beginRound(owner="brain-tool")` → 执行 → `finally` 中**必然**
    `finishRound()`（保留改动并释放锁）；
  - `finishRound()` 失败 → `abandonRound()` 释放锁，并写诊断事件；
  - 只读命令（ls/cat/git/`python3 --version` 等）不再创建写轮次。
- `beginRound()` 前定点回收**同属主**遗留轮次（上一次调用没走到 finally 的
  残留）；`owner` 不同（如 `hand` 通道）一律拒绝回收，只报
  `workspace_round_active` —— 并发保护保留，没有删锁、没有绕过审批。
- `WorkspaceAdapter` 新增：`abandonRound(roundId, reason)`（只回收点名的
  轮次）、`recoverStaleRounds()`（启动恢复）、`RoundRecoveryReport`。
  轮次目录写 `round.json` 标记；启动时只有在能拿到工作区锁、没有活动写入者
  时才清理带标记目录，没有标记的目录一律保留。
- `finishRound()` 幂等：重复调用返回第一次的 `ChangeSet`，不再重算 diff。
- 已结束轮次保留上限 32，超出淘汰最旧备份目录，避免长期运行磁盘/内存增长。
- `handleRun()` 不再把 `WorkspaceException` / `LimitError` 吞成
  `hand_run_failed`，机器码原样返回。

### 2.2 审批安全（`brain/ApprovalFingerprint.kt`、`brain/ApprovalBroker.kt`、`AndroidApprovalGate.kt`、`brain/ToolRegistry.kt`）

- 参数指纹（SHA-256，Map 键序无关）+ 深冻结：审批与执行使用同一份不可变
  快照；执行前重算指纹，不一致 → `approval_action_changed`。
- 审批通过后重跑结构与权限校验；再调用
  `ApprovalGate.isApprovalStillValid()`，Activity 销毁/用户停止后
  → `approval_revoked`，不执行。
- `ApprovalBroker`：Mutex 串行化（永远只有一个弹窗）、同一
  `actionId + idempotencyKey + 参数指纹` 只批一次、参数变化必须重新审批、
  每次展示 60 秒超时即拒绝、UI 异常默认拒绝、`invalidate()/close()` 立即
  作废已批准决定。并发请求排队而不是被误判为拒绝。
- `AndroidApprovalGate` 改成进程级单例，`attach/detach(Activity)`；旋转不再
  永久关闭审批能力；`onDestroy` 只解绑当前 Activity、拒绝挂起审批、作废
  已批准决定。
- `ApprovalPromptFormatter`：只展示工具、工作区相对路径、`run_command` 的
  脱敏 argv、写入内容字节数；**不展示**文件正文、Key、环境变量、命令输出；
  所有字段先过 `SecretRedactor` 再截断。

### 2.3 真实 Python 执行（自主开发闭环）

新增：

- `core/python/PythonRuntime.kt`：`PythonRuntime` 接口 +
  `ProcessPythonRuntime`（系统 python3，真实子进程）+ 
  `PreferEmbeddedPythonRuntime`（优先内嵌、回退系统）。
- `python/ChaquopyPythonRuntime.kt`：APK 内嵌 CPython 3.11（Chaquopy）
  进程内执行；启动失败返回 `available=false` + 原因。
- `app/src/main/python/agent_runner.py`：真实 `runpy` 执行、捕获
  stdout/stderr、SystemExit 退出码、异常 traceback、墙钟超时（settrace）、
  输出上限；运行期间 chdir 到工作区并在 finally 恢复。
- `ToolRegistry` 的 `run_command` 真正接入：`python3 <脚本>`、`python3 -m
  <模块>`、`python3 --version`；前两者进写轮次保护并需要审批，版本探测
  只读、不建轮次。运行时不可用 → `python_unavailable`，摘要明确写
  "未模拟执行"；绝不伪造退出码或输出。
- `BrainBootstrap` 组装 `PreferEmbeddedPythonRuntime(Chaquopy, Process)`
  并注入 `ToolRegistry`；启动时执行 `recoverStaleRounds()` 并把报告写进
  EventLog。
- `BrainEngine.SYSTEM_PROMPT` 增加开发闭环约束：先列目录、逐文件写、
  先探测 Python、真实跑测试、失败最多修三轮、不可用如实报告、不得声称
  执行了未执行的命令。
- `AgentGraph`：引擎/工作区适配器/Python 运行时改为进程级单例；Activity
  旋转不再重建第二个适配器抢同一把锁、也不会把活着的轮次当成遗留清理。

### 2.4 用户控制（暂停/停止/改目标）

- `brain/TurnController.kt` + 引擎已有的 P1 §5.4 控制态接线：
  - 暂停：在下一个安全检查点停下本轮，不自动续跑；
  - 停止：同上，且 `executeProcess` 每 100ms 轮询停止信号，运行中的命令
    会被 `destroyForcibly` 终止；挂起审批立即拒绝；
  - 改目标：旧目标作废、本轮停；新目标由用户下一条消息给出。
- `MainActivity` 增加三个按钮；同一时刻只允许一轮（并发发送会明确提示）；
  Activity 销毁时停止本轮、解绑审批门，`postUi` 保证不再触碰已销毁 View。

## 3. 验证

### 3.1 本地环境

JDK 17.0.20.1、Gradle 8.0、Android SDK platform 34 / build-tools 34.0.0、
宿主机 python3（真实执行测试用）。

> 说明：本轮开始时 `app/build` 里有一个**损坏的
> `META-INF/app_debug.kotlin_module`（24 字节，空包列表）**，导致所有
> main 顶层函数在单元测试编译时 Unresolved（103 个错误）。这是构建缓存
> 损坏，不是源码问题；清理 `app/build/tmp/kotlin-classes` 等中间产物后
> 恢复。该问题与本轮代码无关，已在报告中单列。

### 3.2 新增/修改的测试

- `RoundLifecycleRegressionTest`（10 项）：正常结束释放、审批拒绝不建轮次、
  写失败仍释放、只读命令不占轮次、hand 属主并发保护、
  遗留 brain 轮次回收、运行中取消释放、`approval_revoked`、
  审批后权限复检、启动恢复（删标记目录/保留未知目录）、
  点名回收。
- `ApprovalSafetyTest`（10 项）：去重、参数变化重新审批、并发串行化、
  超时拒绝、invalidate/close、UI 异常默认拒绝、弹窗不泄露正文/密钥/
  Bearer。
- `PythonToolChainTest`（5 项，真实 python3）：版本探测、calculator
  写码→真实测试→故意失败→修复重跑、真实退出码/stderr、`-m` 模块、
  无运行时如实 `python_unavailable`。
- `BrainEngineTest` 新增 2 项：**跨会话连续两轮 write_file 不再
  `workspace_round_active`**；写轮次后下一轮 `run_command` 仍可执行。

### 3.3 结果

- `gradle :app:testDebugUnitTest`：**587 项，0 失败，0 错误，1 跳过**。
  - 跳过项是原有的 `BrainEngineTest > unreadable directory...`：本机以
    root 运行，`chmod 000` 无法让 `listFiles()` 返回 null，测试用
    `assumeTrue` 主动跳过，不是代码失败。
  - 本轮新增的 `PythonToolChainTest` 5 项全部真实执行（未跳过）：宿主机
    python3 跑 calculator 测试、退出码/stderr、`-m` 模块、不可用如实报告。
- `gradle :app:assembleDebug`：**BUILD SUCCESSFUL**。
  - APK：`android/app/build/outputs/apk/debug/app-debug.apk`
  - 大小：16,394,526 字节（约 15.6 MiB）
  - SHA-256：`d8fef4d21b7360f4644636ea8c7ff80eebf3cf05ccea62ef1df7064a2db6aded`
  - package `wherevercode.agent`，minSdk 26，targetSdk 28，compileSdk 34，
    arm64-v8a，debug 签名（固定 debug.keystore）。
  - APK 内 `assets/chaquopy/app.imy` 已包含 `agent_runner.py`，确认内嵌
    CPython 执行 helper 真的进了包，不是只写了接口。

## 4. 明确未做 / 未验证

- **没有连接 K70 真机**（`adb devices` 为空）：本轮未在真机安装/复测；
  Chaquopy 内嵌 Python 路径只做了编译验证与宿主 `python3` 同接口真实执行，
  未在 K70 上实跑 `calculator.py`。这一点必须在下一轮真机复测中确认。
- 暂停/停止是"安全点停止"，不是硬杀模型流；模型 HTTP 流已发出时，停止
  在流结束/下一个检查点生效。运行中的子进程命令会在 ~100ms 内终止。
- `undo` 跨进程仍不可用（轮次状态只在内存）；启动恢复会清理遗留备份目录。
- Cloudflare 原生回填路径、SIWC 登录等旧功能未改动，也未重新真机验证。
- 未改旧 WhereverCode 平台、未部署、未合并 main、未更新 K70。
