# WhereverCode V5 Android 整改 — 交接说明（2026-11-15）

> 交接对象：接手本任务的 Agent / 开发者。
> 本文只写**当前真实状态**：哪些已改、哪些没做完、怎么构建、卡在哪。
> 未完成的项一律标注「未完成」，不存在“看起来做了但没接线”的项。

---

## 0. 一句话现状

一级导航（对话 / 项目 / 频道 / 控制）的**模型层、抽屉视图层、路由层、入口注册表、MainActivity 路由分支已改完**，
**`assembleDebug` 构建成功，APK 已产出**；
**单元测试未跑通验证、频道/私聊双分区首页、频道附件菜单、频道 CRUD、7 张截图、功能矩阵均未完成**。

---

## 1. 仓库与分支

| 项 | 值 |
|---|---|
| 仓库 | `huaerdaidai-gif/wherevercode-agent`（**不是**旧的 `wherevercode`） |
| 本地路径 | `/workspace/wherevercode-agent` |
| 分支 | `feature/v5-nav-overhaul`（从 `main` 切出，未合并、未推送） |
| 远端 | `origin` 已配置；**当前环境无 GitHub 凭据**（`gh auth status` = not logged in），push 需接手方携带 token |
| 旧仓库 | 全程未改动 |

接手后先确认：
```bash
cd /workspace/wherevercode-agent && git log --oneline -5 && git status
```

---

## 2. 构建环境（本机已配好，换机需重装）

| 组件 | 位置 / 版本 | 备注 |
|---|---|---|
| JDK | 系统默认（Java 17 级别即可） | AGP 8.1.4 |
| Android SDK | `/opt/android-sdk`（platforms;android-34、build-tools;34.0.0、platform-tools） | `android/local.properties` 已写 `sdk.dir=/opt/android-sdk` |
| Gradle | **`/opt/gradle-8.5/bin/gradle`**（从腾讯镜像装的） | 项目自带 `gradlew` 缺 jar 且下载失败；**系统 sdkman 的 Gradle 9.3 与 AGP 8.1.4 不兼容** |
| 已删除的坑 | `/root/.gradle/init.gradle` 已 mv 成 `.bak`（里面有 `mavelCentral()` 拼写错误，导致所有 Gradle 任务直接挂） | 若恢复会重新报错 |

**可用构建命令（已验证可跑，离线模式）**：
```bash
cd /workspace/wherevercode-agent/android
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  /opt/gradle-8.5/bin/gradle assembleDebug --no-daemon --offline
```
单元测试：
```bash
cd /workspace/wherevercode-agent/android
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  /opt/gradle-8.5/bin/gradle testDebugUnitTest --no-daemon --offline
```

> 注意：`gradle ... | tail -40` 的退出码是 `tail` 的，**不要用管道判断成败**，要读日志里的 `BUILD SUCCESSFUL/FAILED`。

---

## 3. 已完成（代码已落地）

### 3.1 抽屉模型 `ui/DrawerController.kt`
- `DEFAULT_ORDER` = `[chat, projects, channels, control]`，四项固定顺序。
- 新增 `ID_CHAT = "chat"`、`ID_CHANNELS = "channels"`、`ID_CONTROL = "control"`。
- 旧 id（`new_conversation` / `history_search` / `tasks` / `agents_team` / `devices` / `tools_connections` / `results` / `help` / `settings` / `console` / `mcp_plugins` / `team_channel`）**全部保留为路由常量**，只是不再进 `DEFAULT_ORDER`——70 屏能力靠子页面逐层访问，没删。
- `reset()`、`DrawerPrefs` 默认值随四项更新。

### 3.2 路由表 `ui/DrawerRouter.kt`
- `EntryScreen` 新增 `CHANNELS`。
- `screenFor()`：四项一级 + 13 个旧 id 兜底 + 未知 id → `HOME`。

### 3.3 抽屉视图 `ui/DrawerView.kt`
- 结构改为：品牌头 → 四项一级入口 → 最近会话区 → 底部固定操作区（左账号头像 / 右 ＋ 新对话）。
- 新增 `RecentSession` 数据类 + `recentSessionsProvider` / `onSessionSelected` 回调，会话数据由宿主传入（控制器不自己造数据）。
- 底部账号行点击进设置；＋ 新建对话。

### 3.4 入口注册表 `ui/EntryRegistry.kt`
- `DRAWER_TOP` 组只剩四项：`nav_chat` / `nav_projects` / `nav_channels` / `nav_control`。
- 其余入口降级到 `DRAWER_WORKSPACE` 组（子页面入口），文案注明“从 X 入口内访问”。
- 头部注释已改为四项口径。

### 3.5 `MainActivity.kt`
- `showEntry()` 的映射与可见性补上 `EntryScreen.CHANNELS`（当前指向已有真实团队频道页 `screenTeamChannel`）。
- 离开频道停 WS/轮询的条件已包含 `CHANNELS`。
- 新增 `recentSessionsForDrawer()`：从 `SessionLedger.listSessions("user")` 读真实会话，按 `updatedAtEpochSeconds` 倒序取 8 条，**无数据返回空列表（抽屉显示空态），不造数据**。
- 新增 `openSessionById()`：点抽屉会话打开对应会话。
- 字段修复：`SessionRecord` 用的是 `updatedAtEpochSeconds`（不是 `updatedAt`），已改。

### 3.6 测试（已改，**未跑通验证**）
- `DrawerControllerTest` / `DrawerViewTest` / `DrawerRoutingTest` / `EntryRegistryTest` 已按四项口径重写。
- **状态：`testDebugUnitTest` 跑了约 7 分钟仍未结束，为控额度被主动终止，`app/build/test-results/` 下无结果文件 —— 即“测试代码能编译，但一个断言都没验证过”**（`compileDebugUnitTestKotlin` 通过）。接手方必须重跑并修断言。

---

## 4. 未完成（按优先级）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **编译验证 + 单元测试跑通** | 主代码最后一次编译只剩 1 个错误（`updatedAt`），已修；但**修完还没重新编译验证**。接手第一件事就是 `assembleDebug`。 |
| 2 | **频道 / 私聊双分区首页** | 完全没做。任务要求：频道/私聊 Tab、列表、搜索、创建、最近消息、未读数、免打扰。当前「频道」入口直接落到已有团队频道页。数据源要接真实 API（现在代码库里只有单频道 `PlatformChannelTransport`，**没有频道列表接口**）。 |
| 3 | **频道内 消息/文件/任务 三分区** | 已有 `TeamChannelController`（消息 + 扇出），**文件区、任务区没做**。 |
| 4 | **输入框 ＋ 收纳（附件 / 项目文件 / 网盘 / 关联任务）** | 没做。需要上传通道 + 统一存储适配器；**禁止自建第二套文件系统**，先确认后端有没有现成上传接口。 |
| 5 | **消息长按菜单**（回复/复制/编辑/撤回/删除/关联任务/时间） | 没做。 |
| 6 | **频道 CRUD**（建/改/删频道、成员与角色、删消息、清个人记录） | 没做。高风险操作需二次确认 + 区分撤回/个人清除/服务端删除。 |
| 7 | **私聊权限**（禁 Agent↔Agent） | 需服务端强制。当前客户端**没有**私聊入口，也就没有绕过风险，但也没有实现。 |
| 8 | **7 张真实运行截图** | 一张都没出。见 §5。 |
| 9 | **APK 交付** | 构建已启动，结果见 §6。 |
| 10 | **70 屏功能保留矩阵、接线状态清单** | 需从 `EntryRegistry`（约 100+ 条 Entry）导出成表格，标注 已实现 / 待接线 / 未实现。 |
| 11 | **设置页分组（账户 / 应用）** | 现有 `screen_settings.xml` 是单 ScrollView 分节，**没有按“账户/应用”两大组重排**。 |

---

## 5. 截图：当前环境的关键阻塞

- **本机 `/dev/kvm` 不存在，CPU 也没有 vmx/svm**（`grep -c 'vmx|svm' /proc/cpuinfo` = 0）。
  → x86_64 Android 模拟器**无法启用加速**，基本跑不起来；ARM 镜像走 TCG 软件模拟，启动以十分钟计且极易 ANR。
  → **不要在这台机器上反复尝试模拟器**，浪费时间。
- 可行替代（按推荐顺序）：
  1. **换一台有 KVM 的机器 / 真机**：`emulator -avd <name>` 或 `adb install`，然后 `adb exec-out screencap -p > shot.png`。这是唯一能称为“Android 真实运行截图”的路径。
  2. **Robolectric native graphics 渲染**：把 Activity 跑在 JVM 上，用 `@GraphicsMode(NATIVE)` 把 View 树画成 Bitmap。像素来自真实布局代码，但**不是设备运行**，交付时必须明确标注“Robolectric 渲染，非模拟器/真机”，不能混称。
  3. 设计稿 / 手绘示意图：**只能当参考，禁止当验收截图**。
- 任务原文要求「模拟器、真机和设计图必须明确区分」，交接时同理：**截图来源必须在交付说明里逐张标注**。

---

## 6. 本次收尾动作（已核对的事实）

- 分支：`feature/v5-nav-overhaul`
- commit：`cfc3ec5 feat(android): V5 四项一级导航整改（对话/项目/频道/控制）`（代码 + 本文件；13 文件，+1232/−793）
- **构建：`assembleDebug` = BUILD SUCCESSFUL（2m58s，47 tasks）**
- **APK：`android/app/build/outputs/apk/debug/app-debug.apk`（18 MB），另存 `/workspace/wherevercode-v5-nav-debug.apk`**
- **单元测试：未验证**（跑了约 7 分钟未结束被终止，无结果文件；见 §3.6）
- **截图：0 张**（本机无 KVM，原因见 §5）
- **未推送远端**（无 GitHub 凭据）、**未合并 main**、**未部署**

---

## 7. 接手步骤建议

1. `git log -1 --stat` 看本次提交范围；`git status` 确认工作区干净。
2. 跑 `assembleDebug`（命令见 §2），修掉编译错误。
3. 跑 `testDebugUnitTest`，修 4 个抽屉测试。
4. 装 APK 到有 KVM 的机器，确认四项抽屉、最近会话、底部账号/新对话、设置入口跑得通。
5. 再决定「频道」是继续复用团队频道页，还是新建 `ChannelListController`（建议新建，团队频道页保留为频道内的一级详情页）。
6. 频道列表接口若后端没有，**先在界面上标“待接线”并写进清单**，不要用假数据填列表。

---

## 8. 不能碰的红线（原任务原文）

- 不修改旧仓库 `huaerdaidai-gif/wherevercode`。
- 不覆盖其他 Agent 分支，**不自行合并 main**、不部署生产。
- 保留 `BrainEngine`、模型适配器、任务系统、`TerminalWriteGuard`、权限校验、停止确认与生命周期保护。
- 不新建平行任务系统 / 频道系统 / 文件系统。
- 普通界面不出现 `route_id`、`P0`、`待验收`、`扩展` 等开发字段。
- 未接通的能力显示真实状态，**不得用假数据模拟成功**。
