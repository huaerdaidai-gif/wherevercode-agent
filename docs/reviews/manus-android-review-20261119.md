# Manus Android B1/B2 独立复核报告

- 基线：`main` / `bafe277cad7cb9296480c9935ab8ba5e2ed8962e`
- 复核对象：`4b8a8598bc67094f41c612f6e0818dca6b896606`、`27e52cd5df95cf880c1c551128c35379d873be28`、`634987784b75024824e910d60b3f021946622ecc`、`6120b81c340a19f208e74d37ef7c86c780565f2f`
- 审查方式：只读静态代码、提交差异及仓库内自测材料检查；未修改产品代码，未运行 Android 构建或真机验收。

## 总评

**不可合并：5 条阻断项。** B1 的三项功能与 R1/R2/R4 静态检查通过；B2 的批准例外是 BrainBootstrap 中单一改动，且 `model_origin` 三条来源路径代码均有写入。当前不能整体合并，原因是：设备重发现把仅有配对信任、无设备记录的设备当成不存在；设备记录读取失败也会降级为空集合并继续改绑；删除 Agent 时密钥共享检查漏掉 TeamMember 引用；B2 使用进程级共享 registry，跨会话并发有覆盖/误注销风险；门禁脚本的保护范围和执行接线不足，不能保证预期 brain 核心零差异。

本报告所称“阻断项”按独立失效路径计数：重发现的配对集合遗漏、读取失败 fail-open、Agent 删除密钥引用遗漏、B2 registry 并发串扰、门禁范围/接线不完整，各计一项。第五项不意味着脚本对已覆盖路径的常规判定错误，而是它不能作为完整且自动执行的合并门禁。

## R1 — Android 设置「大脑」分节

**结论：通过（分支1、分支3）。** 以改名前原稿核对，设置布局在相关提交中未变化。B1 基线与目标的 `screen_settings.xml` blob 相同（`2874ed1528411e734c8562c27170363219243208`）；追溯改名前原稿 `75c7218c` 亦无差异。目标文件标题位于 `android/app/src/main/res/layout/screen_settings.xml:75-83`。该文件未被 B1 或 B2 提交修改或重命名；B2 的差异清单涉及 `MainActivity.kt`、`BrainBootstrap.kt`、`AgentBrainWiring.kt`、`ChatViewModel.kt`、`ModelListRows.kt`，不涉及设置布局。

历史中曾有临时设置分节改动，随后有 `fa345a3` 撤回改名、`7571f2e` 恢复到原稿的记录。故本结论按用户要求比较改名前原稿，而不是仅凭较早中间提交判断。

## R2 — brain 核心文件零差异

**分支1、分支2：通过。** B1 的 Android `brain/` 目录相对基线无差异；B2 的设备重发现提交只改发现筛选、提示及相应测试，没有改脑核心。

**分支3：通过，适用已批准例外。** `63498778` 对 `brain/**` 的唯一差异是 `android/app/src/main/java/wherevercode/agent/brain/siwc/BrainBootstrap.kt:616-617`，`isBrainConfigured` 在配置存储未命中时回退到 `BrainAdapterRegistry.configFor(brainId)`。除此之外未发现其他 brain 核心改动；`BrainConfig` 与 `BrainLoginRows` 未改。该处按需求明确批准，不计违规。

**分支4：门禁脚本对受保护路径有效，但不能证明完整 R2。** 脚本 `tools/zero-diff-gate/zero-diff-gate.sh:73-91,101-110,119-137` 检查限定路径和文件名，并非全量脑行为依赖清单，详见分支4。脚本不能代替对 R2 全部涉及文件的直接差异检查。

## R3 — 删除设备、删除 Agent 与密钥共享

### 删除设备

**结论：通过。** 设备删除调用位于 `android/app/src/main/java/wherevercode/agent/MainActivity.kt:3061-3083`；`core/transport/RemoteDeviceStore.kt:416-434` 删除设备条目和对应配对信任，没有级联删除 Agent 记录。该删除路径没有删除模型或脑配置的操作。

### 删除 Agent

**结论：不通过。** Agent 删除先删 Agent 记录，并检查是否仍有其他 Agent 或 brain 配置引用 authRef，只有未发现引用时才删除 Keystore 密钥。调用和清理逻辑见 B2 目标 `MainActivity.kt:8554-8580`；其中 still-used 检查在 `:8572-8575`，`BrainKeyStore` 是 Agent credential adapter 使用的密钥存储，见 `core/agent/AgentCredentialStores.kt:19-26`。

但引用检查没有覆盖 TeamMember。`core/team/TeamMember.kt:250-254` 将 `authRef` 作为密钥引用，`core/team/TeamMemberStore.kt:41-55` 持久化该字段。若被删除 Agent 的密钥也被 TeamMember 使用，现有检查可能误判为独享并删掉仍共享的密钥，违反“不得动共用密钥”红线。应在删除前统一核验所有 authRef 使用者，或使用统一引用计数/清理机制。该问题为**高严重度阻断**。

## 分支1 — Android B1：`4b8a8598`

### MCP 控制台角标读取真实 Token 状态

**结论：通过（静态代码）。** `MainActivity.kt:5346-5360` 从 `platformCredentialStore.loadPlatformToken() != null` 构造状态；`ConsoleSnapshot.kt:287-290,364` 传递 `mcpTokenConfigured`；`ConsoleScreenController.kt:404-411` 根据状态显示“已配置 Token”或“未配 Token”。这里检查 Token 是否存在，不证明 Token 有效或连接成功。尚未做真机验证。

### 选择器标题与残留文案

**结论：通过。** 对话选择器对话框标题见 `MainActivity.kt:2322,2345`，聊天入口按钮见 `MainActivity.kt:9553-9556` 和 `screen_chat.xml:261,267`，均为“选择对话方”。未发现选择器残留“选择模型 / Agent”。设置模型或切换具体模型时出现的“选择模型”不是该选择器标题，不计为违规。

### 抽屉搜索框高度

**结论：通过（静态代码）。** `DrawerView.kt:364-376` 改为 `WRAP_CONTENT` 并增大上下容器间距；`:379-394` 为输入框添加垂直 padding，消除固定高度和字形裁切的布局原因。未运行模拟器/真机，最终视觉效果尚未经运行时验证。

## 分支2 — 设备重发现护栏：`27e52cd5`

### 自动发现与手工记录隔离

**结论：通过（正常数据路径）。** `core/agent/DeviceAgentDiscovery.kt:96-105` 仅当 `old.addHint == AUTO_DISCOVERED_HINT` 时复制并改绑；其他记录只计入 `skippedManual`，不会写回。自动发现新记录写标记见 `:109-121`。候选筛选与手工跳过提示在 `MainActivity.kt:3015-3026,3037-3051`。新增测试覆盖无标记手工记录不改绑，见目标 `DeviceAgentDiscoveryTest.kt:205-240`。本次未实际运行 Gradle 测试。

### 「设备存在」是否包含只有配对信任的设备

**结论：不通过。** `MainActivity.kt:2971-2974` 的 `knownDeviceIds` 只来自 `remoteDeviceStore.listDevices()`；确认时传入的集合见 `:3076-3088`，也是设备记录 ID 加本次选中设备 ID，没有把配对信任记录 ID 合并进去。发现列表虽读取 `pairingStore.all()`（`:2917-2929`），但仅将配对列表中与 RemoteDevice 记录匹配的设备呈现为设备候选。因此只有配对信任、没有 RemoteDevice 记录的设备可能被视为不存在；绑定在其上的自动发现 Agent 会被列入可改绑候选。该遗漏是**中高严重度阻断**。

### 读取设备记录失败时的处理

**结论：不通过。** `MainActivity.kt:2971-2975` 在读取设备列表异常时使用 `emptySet()`；之后逻辑仍可把既有绑定判作过期，确认路径 `:3076-3080` 也采用空集合降级。若失败只是暂时存储错误而非设备删除，自动发现记录会被错误改绑。异常应停止该轮重发现并报告错误，而不是将读取失败当成空设备集合。该路径与配对集合遗漏分别计为两个独立失效条件。

### R4 对大脑本体影响

**结论：通过（提交差异范围）。** 本提交改设备发现与 Agent 绑定，不改 brain 核心/模型路由代码，也不引入删除脑配置的逻辑。该结论是静态差异结论，不代表完整集成运行时证明。

## 分支3 — Android B2：`63498778`

### 新增「云端模型 API」Agent 不落盘 BrainConfig

**结论：通过（主添加路径）。** `MainActivity.kt:8220-8232` 的 generic/model API 添加分支不调用 `configStore.saveConfig` 或 `registerConfig`；Agent 记录仍按常规持久化，见 `:8245-8254`。发送期配置由 Agent 记录合成，见 `AgentBrainWiring.kt:92-108` 与 `ChatViewModel.kt:956-985`。因此不将新增 Agent 配置写入 BrainConfig。

### 发送时内存组装、结束注销

**结论：有条件通过；并发隔离存在阻断风险。** 正常路径在 `ChatViewModel.kt:961-976` 创建内存配置；未使用该配置时在 `:979-985` 注销，已进入轮次则在 `:1293-1300` 的清理路径恢复 adapter 并注销。`AgentBrainWiring.kt:178-200` 的 session store 将该轮配置用于读取，持久化写入仍转交真实 store。

但是 `BrainAdapterRegistry` 为进程级共享 `LinkedHashMap`，注册/注销按相同 brainId 覆盖或删除，见 `brain/BrainAdapter.kt:188-225`。`ui/ChatViewModel.kt:1218-1225,1297-1300` 会全局替换通用 adapter 并在轮次后恢复；未见进程级锁或会话隔离。不同会话并发时，第二轮可覆盖第一轮注册；其中一轮结束可能恢复共享 adapter 或移除另一轮仍在使用的配置。缺少并发/取消路径测试。建议改成请求级/会话级配置，或确保注册表全局串行并以注册 token 做安全注销。该风险计为**高严重度阻断**。

### 已有同名存量配置

**结论：基本通过。** `AgentBrainWiring.sessionConfigFor` 对已有相同 brainId 且 endpoint 相符的配置返回 `null`，沿用原配置路由；编辑路径见 `MainActivity.kt:8138-8149`。不过复用判断没有同时验证 adapter 类型及 authRef 一致性，遇到同 endpoint、不同凭证的存量配置时应补回归测试确认路由/密钥选择符合旧行为。

### `model_origin` 来源键与旧 `custom-` 兼容

**结论：通过（静态代码）。** 来源键和值定义见 `ModelListRows.kt:53-62`。手工表单路径写 `manual` 或命中内置项时写 `preset`，见 `ModelListRows.kt:97-103`、`MainActivity.kt:11318-11338`；预设路径写 `preset`，见 `MainActivity.kt:11945-11963`；设备发现路径写 `device-discovered`，见 `MainActivity.kt:12199-12216`。旧自定义识别仍检查 `provider_preset` 的 `custom-` 前缀，同时支持 `model_origin=manual`，见 `ModelListRows.kt:80-83`。

### R4 对大脑本体行为

**结论：基本通过，但须受并发风险限制。** 大脑适配器算法和轮次实现未因本次改动而更改；唯一 brain 核心改动是前述获批的配置 fallback。然而该方案通过全局注册表暂时覆盖 adapter/config，跨会话可能影响正在进行的路由，因此不能对“所有并发运行时行为完全不变”作无条件通过结论。

## 分支4 — 零差异门禁与残留扫描：`6120b81c`

### 脚本实际逻辑与指定 PASS/FAIL

**结论：指定样例逻辑通过，完整门禁不通过。** 脚本 `tools/zero-diff-gate/zero-diff-gate.sh:101-110` 对 merge-base 到候选提交的受保护路径差异置 FAIL；main 基线测试通过。按仓库中脚本逻辑，brain 改动触及受保护路径会被拒绝。但本轮无法重放自测日志里的 scratch refs，因此“brain 改动应 FAIL”可确认的是代码逻辑，不是所有边界样例的独立实测。

检查目录范围见脚本 `:73-91,119-137`：Android `brain` 主代码/测试、iOS `Brain` 目录及若干固定 basename。目录名单外的脑相关文件或集成接线可能不受保护，例如 Android `AgentBrainWiring.kt`、`MainActivity.kt`、`ChatViewModel.kt` 或 iOS `BrainSettingsView.swift`。若门禁意图覆盖“brain 核心所有行为”，当前范围不充分。

### 残留扫描

**结论：不充分，不能作为无残留证明。** Check B 仅在被枚举目录的 `.kt/.java/.swift` 文件中用正则检查特定 models import（`:113-145`）；Java `import static ...` 不匹配现有常规 import 表达式。报告中的字符串残留扫描是有限的 `git grep` 查询，覆盖已跟踪文件及明确列出的几种文案；它不是旧配置键、代码符号或所有残留的完备扫描。报告所列扫描命令和命中分类见目标报告文件中 residual scan 章节（`6120b81c` 新增文件；对应扫描说明约 `:169-182`）。

### 自测结论可信度及自动接入

**结论：部分可信。** main PASS 可独立确认。自测文本 `tools/zero-diff-gate/self-test-20261119.txt:11-24` 记载 main PASS；脚本对当前目标提交树正常可执行。日志中声称的候选 SHA `83a5f0e`、`0756a67`、`70f2a9d`、`19af363` 及临时 refs 在当前仓库对象库中无法解析，故相关 brain FAIL/PASS 自测无法独立复放（日志 `:26-89`）。此外未发现 GitHub Actions 工作流调用该脚本；README `:24-28` 给的是手动调用方式，提交标题包含 `[skip ci]`。因此该脚本当前不是可确认已接入的自动合并门禁。

脚本还存在 fail-open 风险：只有 `set -u`（`:42`）；`git diff` 输出捕获和 `git ls-tree` 扫描过程未全面检查退出状态（`:103-110,118-145`），某些命令错误有可能被当作空结果或无命中。该问题应纳入门禁修复。

## 阻断项清单

1. **设备重发现存在性集合漏配对信任记录：** `MainActivity.kt:2971-2974,3076-3088` 只用 RemoteDevice 记录判定设备存在。
2. **设备清单读取异常 fail-open：** `MainActivity.kt:2971-2975,3076-3080` 异常变空集合后仍允许改绑。
3. **删除 Agent 的密钥引用检查不完整：** `MainActivity.kt:8530-8557` 未覆盖 `TeamMember.authRef`，见 `TeamMember.kt:250-254`、`TeamMemberStore.kt:41-55`。
4. **B2 内存配置全局注册表并发串扰：** `BrainAdapter.kt:188-225` 与 `ui/ChatViewModel.kt:1218-1225,1297-1300`。
5. **零差异门禁不完整且未证实接入 CI：** 脚本 `:73-91,101-110,119-145`；自测记录不可完整复放；未发现调用脚本的 workflow。

**合并建议：不可合并。** 修复以上 5 项并补上覆盖对应失效路径的自动化测试/CI 检查后，再复核合并包。
