# 安卓版超级 Agent · 第 0 步：可行性验证

> **2026-11-09 UI V2：** 当前界面已从“测试式聊天 + 控制台”升级为聊天 / 项目 /
> 任务 / Agent 四入口工作台，见 [`docs/android-ui-v2-20261109.md`](../docs/android-ui-v2-20261109.md)。
> 下文的“只做最简界面”是第 0 步历史记录，不再是当前 UI 描述。


任务：`8404fd043962424b8f631e2453e8f971`（maintainer 2026-11-06 定：先做安卓版，不是遥控器，
手机本身就能干活；到期后租的服务器都不用，不能依赖服务器；网站搁置）。
分支：`task/8404fd043962424b8f631e2453e8f971`（项目工具自动建分支，不合 main，不上线，
不改网站和服务端）。

目标机：Redmi K70（Android，arm64）。不装 Termux，不接平台服务器，不用 WhereverCode 账号。

## 结论先行（2026-11-06，本轮）

| # | 要证明的事 | 结论 | 证据状态 |
|---|-----------|------|---------|
| 1 | 内置运行环境：App 内跑 Python 3 和 Node，并执行一条 shell 命令 | **方案成立，待真机验证** | 代码已写；真机执行未验证 |
| 2 | 跑通一个真 Agent（优先 CodeBuddy，其次 Codex），登录走用户账号 | **路径打通到命令一级，待真机登录验证** | 参数与服务端已验证的 Runner 一致；登录流程未在真机走过 |
| 3 | 改沙箱内文件夹里的文件 + 前台服务切后台不被杀 | **文件读写成立；“不被杀”只能做到尽量，不能保证** | 文件操作是普通 Java IO；保活受系统电池策略限制，见下 |
| 4 | 复用 `execution_host_agents.available_agents()` 的发现逻辑 | **逻辑可复用，二进制不可复用** | 注册表与探针形状已按原样移植；Termux 里的 CLI 与 App 沙箱互不可见 |

**APK 大小 / 内存占用：本轮没有数字。** 本机无 Android 构建环境、GitHub 项目无
`project_run`，编不出 APK；CI 出包后看 artifact 大小，K70 安装后实测内存。
这里不编数字。

## 1. 内置运行环境

### 限制的根（已核实）

Android 10 行为变更（developer.android.com，`Behavior changes: apps targeting API 29+`）：

> Removed execute permission for app home directory. Execution of files from the
> writable app home directory is a W^X violation. Apps should load only the binary
> code that's embedded within an app's APK file. Untrusted apps that target Android 10
> cannot invoke `execve()` directly on files within the app's home directory.

即：**targetSdk ≥ 29 的 App 不能直接 exec 自己数据目录里的文件**；只能执行 APK 内嵌的
二进制（`jniLibs` 解出来的 native lib 目录有执行权限）。

### 本 spike 的取舍：targetSdk 28

- 沿用 Termux 老做法：`targetSdk = 28`。Termux 长期把 targetSdk 钉在 28，就是为了
  让下载的包保持可执行；代价是上不了 Google Play（Play 自 2025-08-31 要求新应用
  targetSdk 35）。
- 本任务明确“Google Play 不是目标，第一版发 APK 安装包”，所以这个代价可以接受。
  侧载安装，不走 Play。
- `targetSdk 28` 下，`ProcessBuilder("/system/bin/sh", "-c", ...)` 和执行数据目录里的
  ELF 都按老规则放行；`npm install -g` 装出来的可执行文件也能直接跑。
  这是“安装 Agent”按钮能成立的前提。

### 备选路线（已核实，暂不采用）

- **targetSdk 34/35 + 二进制放 `jniLibs`**：可执行文件改名 `lib*.so` 放进
  `src/main/jniLibs/arm64-v8a/`，随 APK 安装后从 `nativeLibraryDir` 执行
  （rumblefish 的做法；`extractNativeLibs=true`）。只适用于**打包时就确定**的
  二进制；`npm install` 在运行时写进数据目录的文件仍不可执行，所以“用户在 App 内
  再装一个 Agent”这条路会被堵死。
- **`/system/bin/linker64 <elf>`**：Termux Play 版（`termux-exec`）的做法——不直接
  exec 数据目录的 ELF，而是 exec 系统 linker，让 linker 去加载它。能绕过 W^X，
  但静态链接的二进制不行，且属于钻空子的实现，排在最后。
  本 spike 的 `RuntimeEnv.runDataDirElf()` 已把这个回退写进去，但默认不走它。

### Python 3：Chaquopy（已定）

- Chaquopy 14.0 + Python 3.11（多个来源一致的稳定组合；更新的 17.0/Python 3.14
  说法只见于内容农场页，未采信）。Gradle 插件把 CPython 嵌进 APK，
  Kotlin 直接调 Python，不需要 exec，**不受 W^X 限制**。
- `app/src/main/python/spike_probe.py` 是探针：报 Python 版本、跑一条 shell 命令、
  在工作区写读文件。只用标准库。
- 注意：Chaquopy 免费许可覆盖开源应用；WhereverCode 仓库是私有的，正式用之前
  需确认许可条款。本 spike 不做结论。

### Node：两种已验证的供给方式，都还没在真机跑通

- **A. `libnode.so`（node-on-android 模式）**：把 Node 运行时预编译成
  `app/src/main/jniLibs/arm64-v8a/libnode.so`，只支持 arm64（K70 是 arm64，够用）。
  需注意预编译 `libnode.so` 的 Node 版本可能滞后，取用前要核对版本。
- **B. Termux 编译的 node 二进制**：用 `termux-packages` 的 Docker 构建环境
  `./build-package.sh nodejs -a aarch64`，产物改名 `libnode.so` 放进 `jniLibs`，
  从 `nativeLibraryDir` 直接执行。
- **本 spike 不提交 node 二进制**：几十 MB 的二进制不能也不应编进这个验证分支。
  `RuntimeEnv` 已写好发现与执行逻辑（找不到时明确报缺失、给获取命令），
  下一步由 CI 构建步骤下载或编译它，再由 K70 实测 `node --version`。
- 官方 Node 不提供 Android 预编译包（只提供 Linux glibc 的 arm64 包，
  在 Android bionic 上跑不了），不要直接拿 Linux 的 node 二进制往里塞。

## 2. 跑通一个真 Agent

### CodeBuddy（优先）

- 包存在：`npm install -g @tencent-ai/codebuddy-code`，要求 Node 18.20+。
  登录是交互式：首次启动选站点（国内站 copilot.tencent.com / 国际站 codebuddy.ai /
  企业域），然后**打开浏览器完成认证**——官方文档没有设备码流程。
  在 App 内对应做法：用 Custom Tab 打开登录页，认证后回跳 App；是否能在手机浏览器
  里顺利完成，**待 K70 实测**。
- 非交互运行参数与服务端已验证的 `CodeBuddyRunner` 一致（`agent_runners.py`，
  2026-11-06 按 workbuddy.ai 的 CLI 文档核对过）：
  `codebuddy -p --output-format stream-json --permission-mode acceptEdits "<一句话>"`，
  prompt 只放 argv。`AgentRunner` 就是按这个调的。
- 额度：CodeBuddy CLI、IDE、插件共享同一账号额度；免费 Hy4 档存在，但具体剩余额度
  以登录后为准，不写死。

### Codex（备用）

- 服务端 `CodexRunner` 已在真机上验证过的调法：
  `codex exec --json --skip-git-repo-check --sandbox workspace-write --cd <工作区> -`，
  prompt 走 stdin。`AgentRunner` 的 codex 分支照抄这一组参数，不自己发明。
- 登录同样是浏览器 OAuth（ChatGPT 账号），与 CodeBuddy 一样的待验证点。

### 卡点

- 两条 Agent 都依赖 Node（CodeBuddy 直接依赖；Codex CLI 也是 Node 应用），
  而 App 内 Node 还没实际跑起来——这是第 2 项真正的卡点，第 1 项不通则第 2 项
  无从谈起。顺序不能颠倒。
- 登录凭据存在 App 沙箱自己的 HOME 下（`filesDir`），与 Termux 里的登录互不相通，
  用户要在 App 内重新登录一次。

## 3. 改本地文件 + 前台服务

- **改文件**：工作区就是 `filesDir/workspace`，普通 `java.io.File` 读写，不需要
  任何权限。`spike_probe.py` 已包含写读回校验。出了沙箱（比如改 `/sdcard` 任意
  位置）要走存储访问框架，不在本步范围。
- **前台服务**：`AgentService` 是标准 `startForeground + 通知`。
  targetSdk 28 下不需要声明 foregroundServiceType（那是 API 29+ 的要求）。
- **“切后台不被杀”不能只靠前台服务保证**，如实说三点：
  1. Android 12+ 对后台启动前台服务有限制；本 App 从前台启动服务，合规。
  2. 电池优化（Doze / App Standby）仍可能冻住进程；App 内已加
     “去设置里关掉电池优化”的引导（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。
  3. 小米 MIUI 有自己的自启动与省电白名单，系统级杀后台比 AOSP 更 aggressive；
     K70 实测时必须在“应用设置 → 省电策略 → 无限制”和自启动里放行，否则前台服务
     也可能被杀。这是 K70 实测清单里的一项。

## 4. 发现逻辑复用

- `agent_directory.py` 的条目表已经按原样移植为 `AgentDiscovery.kt` 的注册表：
  codex-cli / claude-code-cli / muse-code-cli / gemini-cli / codebuddy-cli / agy，
  每个条目有 binary 名 + 环境变量覆盖（`CODEX_BIN`、`CODEBUDDY_BIN` 等），
  与服务端一致。
- 探针形状与 `execution_host_agents.available_agents()` 一致：
  二进制在不在 → 能不能跑 `--version` → 登录状态**未知，直到一次真实运行**
  （与服务端 agy 的处理一样：有凭据文件也不敢断言已登录，不编造“已登录”）。
- **关键差异（诚实项）**：App 沙箱与 Termux 是不同 UID，互相看不到对方的文件。
  K70 / 小米 12 的 Termux 里已经跑着 codex、claude、agy，但这个 App **看不到也
  用不了它们**。发现逻辑可以复用，二进制必须在 App 沙箱内自己再装一份。
  `AgentDiscovery` 只扫 App 自己的目录（`filesDir/bin`、`nativeLibraryDir`、
  `PATH`），不去碰 Termux 的目录——碰了也读不到。

## 只做最简界面

`MainActivity`：一个输入框、一个输出区、三个按钮（运行一句话 / 安装 CodeBuddy /
运行探针）。不做设计。所有耗时操作都在后台线程跑，不卡 UI。

## 构建

- `.github/workflows/android.yml`：GitHub Actions 出 debug APK，产物放 Actions artifact，
  不进仓库签名钥匙。触发条件：`android/**` 或 workflow 本身有 push、或手动 dispatch。
  **注意：本文件本次没能直接写进 `.github/workflows/`**——写它两次都报
  `github_project_write_failed`，而同分支下 `android/` 的文件一次就写成功了；
  原因是 GitHub API 写 workflow 文件需要 Workflow 权限，Muse 的钥匙只有 Contents
  读写，没有 Workflow 权限。所以 workflow 的完整内容暂存在
  `android/workflow-android.yml.txt`，请有 Workflow 权限的人（maintainer 或总管）
  把它移到 `.github/workflows/android.yml` 再跑 CI；内容一字未改。
- 任务里提到的“旧分支里 android.yml（任务 0ea7f093）”：任务文本没给分支名，
  仓库 main 上也没有这个文件，没能定位到它；新 workflow 是按本仓库
  `.github/workflows/main.yml` 的惯例（ubuntu-latest、checkout v4、固定版本、
  超时、并发组）重写的，不是抄旧文件。
- 注意：workflow 用的不是 gradle wrapper（本分支未提交 wrapper 的 jar），
  而是 `gradle/actions/setup-gradle` 装指定版本 Gradle。能跑，但正式化之前
  建议还是补一个 wrapper。

## 文件清单

| 文件 | 作用 |
|---|---|
| `android/README.md` | 本文件：可行性结论 |
| `android/settings.gradle.kts`、`android/build.gradle.kts`、`android/gradle.properties` | Gradle 工程骨架（AGP 8.1.4、Gradle 8.0、Kotlin 1.9.0） |
| `android/app/build.gradle.kts` | targetSdk 28、minSdk 26、arm64-v8a、Chaquopy 14.0 + Python 3.11 |
| `android/app/src/main/AndroidManifest.xml` | 权限、前台服务、MainActivity |
| `android/app/src/main/java/.../MainActivity.kt` | 最简界面 |
| `android/app/src/main/java/.../AgentDiscovery.kt` | 移植的发现逻辑 |
| `android/app/src/main/java/.../RuntimeEnv.kt` | Python / shell / node 供给 |
| `android/app/src/main/java/.../AgentRunner.kt` | CodeBuddy `-p` / Codex `exec` 调用 |
| `android/app/src/main/java/.../AgentService.kt` | 前台服务保活 |
| `android/app/src/main/python/spike_probe.py` | Python 探针（标准库 only） |
| `android/app/src/main/res/layout/activity_main.xml` | 界面布局 |
| `.github/workflows/android.yml` | CI 出 debug APK（**本次因钥匙无 Workflow 权限未能写入，完整内容暂存于 `android/workflow-android.yml.txt`，见上**） |

## 下一步建议（按顺序）

1. CI 跑通 `android.yml`，拿到第一个 debug APK，看大小（决定要不要拆 ABI、裁 Chaquopy stdlib）。
2. 总管用 K70 `adb install` 装上，先跑“运行探针”：Python 版本、shell 命令、文件写读回、
   `node --version`（需先把 node 二进制按上文 B 方式备好）。
3. 再跑“安装 CodeBuddy”：`npm install -g` 是否能在 targetSdk 28 的沙箱里完成。
4. 在 App 内完成 CodeBuddy 浏览器登录，跑 `-p` 一句话拿回复；登录失败就记下失败点，
   不要绕过（不用 API key 是任务要求）。
5. 切后台 30 分钟，看前台服务还在不在、MIUI 有没有杀；按需补电池优化引导。
6. 以上任何一步失败，先如实记失败点，再决定是换 targetSdk 路线还是换 Node 供给方式，
   不要为了“通”而改结论。

## 未验证清单（本轮明确没做的事）

- 没有在真机上装过这个 APK；没有跑过一次 `-p`；没有测过内存占用。
- 没有验证 Chaquopy 14.0 与 AGP 8.1.4 的组合能在 CI 上编过（CI 会是第一道检验）。
- 没有验证 node 二进制在 K70 上的实际行为；没有验证 MIUI 的杀后台表现。
- 没有动服务端、网站、main 分支；没有放任何密钥进仓库。
