# 独立只读复核：差异 9 安卓侧 B3

- 仓库：`huaerdaidai-gif/wherevercode-agent`
- 基线：`main` = `bafe277cad7cb9296480c9935ab8ba5e2ed8962e`
- 被审：`fix/android-b3-20261119` @ `d6f973f02de9e63f4b2cff2eb8669a2e85ecd203`
- 范围：`bafe277c..d6f973f0`，两个提交（`3f7ab7b` 生产 + `d6f973f` 仅测试）
- 方式：只读 diff，不改被审代码
- 总评：**不可合并，阻断 3**

路径（8 个，提交说明里的「差异 9」是变更集名，不是文件数）：

| 状态 | 路径 |
|---|---|
| M | `android/app/src/main/java/wherevercode/agent/MainActivity.kt` |
| M | `android/app/src/main/java/wherevercode/agent/brain/siwc/BrainBootstrap.kt` |
| A | `android/app/src/main/java/wherevercode/agent/models/CompositeBrainConfigStore.kt` |
| A | `android/app/src/main/java/wherevercode/agent/models/ModelConfigStore.kt` |
| A | `android/app/src/main/java/wherevercode/agent/models/ModelEntry.kt` |
| A | `android/app/src/main/java/wherevercode/agent/models/ModelStoreMigration.kt` |
| M | `android/app/src/main/java/wherevercode/agent/ui/ChatViewModel.kt` |
| A | `android/app/src/test/java/wherevercode/agent/models/ModelStoreB3Test.kt` |

`android/.../brain/**` 里除 `BrainBootstrap.kt` 外零差异。

## 逐条

### ① 设置大脑分节未动 — 不通过

列表、切换、增删改、一线配置节仍只读写大脑存储，函数体与 `bafe277c` 逐字节相同：`refreshBrainList`（10742）、`buildBrainListRow`、`switchBrain`、`saveGenericBrain`、`deleteBrain`、`confirmDeleteBrain`、`restoreBrain`、DeepSeek/Grok/Muse/Claude/Cloudflare/SIWC 配置节。`refreshBrainList` 仍是 `configStore.listConfigs()` / `configStore.currentBrainId()`（`MainActivity.kt:10746-10747`）。

但页首「大脑」分节状态行被改了。`refreshBrainStatus` 的 KDoc 写明它就是设置页大脑分节（`MainActivity.kt:9633-9638`，`screen_settings.xml` 的 `brainStatus`），现在改读合并视图：

- `MainActivity.kt:9653` `runtimeConfigStore.currentBrainId()`
- `MainActivity.kt:9695` CLI `runtimeConfigStore.getConfig`
- `MainActivity.kt:9708` 设备码 `runtimeConfigStore.getConfig`
- `MainActivity.kt:9732` 通用大脑 `runtimeConfigStore.getConfig`

这与本分支自己的消费纪律相反：`BrainBootstrap.kt:568-569` 写明设置页「大脑」分节只读 `configStore`，绝不经 `runtimeConfigStore`。迁移把当前项抄进模型存储之后，合并视图的当前项优先模型侧（`CompositeBrainConfigStore.kt:45-46`）。设置页 `switchBrain` 仍只写大脑存储，列表勾跟大脑存储，状态行跟模型存储。两边一分就不再是同一个当前大脑。

`brainShortLabel`（`MainActivity.kt:9754`）也改读了合并视图，但它是任务页回落标签（`MainActivity.kt:5079`），不算大脑分节，不单独算阻断。

### ② brain 核心零差异（BrainBootstrap 组装例外） — 通过

`brain/**` 与 `brain` 测试树只有 `BrainBootstrap.kt` 有差异（+36/−8）。没有第二个 brain 核心文件。

方案批准的组装例外都在，且没有多余擅自改行为：

- 默认参数字段 `runtimeConfigStore: BrainConfigStore = configStore`（`BrainBootstrap.kt:571`）。直接构造 `BrainComponents` 的旧测试仍等于纯大脑存储。
- `build()` 在全部种子写完之后建 `ModelConfigStore`、跑迁移、构造 `CompositeBrainConfigStore`（`BrainBootstrap.kt:1315-1318`），并传入组件（`1588`）。

同一文件里其余改动都是把这一份合并视图接到原先读 `configStore` 的运行时消费者，没有改引擎算法、种子规则或一线大脑写路径：

- 通用分支 `isBrainConfigured` 改读合并视图（`626`）；SIWC / Cloudflare / DeepSeek / Muse / Grok / Claude / 设备码 / Gemini / CLI 仍读 `configStore`。
- 通用适配器、`registerConfig`、引擎 `currentBrainId`、智能路由、任务执行器回落、审查者候选（`1335`、`1339`、`1381`、`1391-1393`、任务 `brainIdOverride`、`1614`）。

这是让字段和 Composite 真正被用上的最小接线，不算第二处 brain 核心改动。

### ③ Composite 语义 — 通过

`CompositeBrainConfigStore.kt`：

- `listConfigs`（29-31）：模型全部条目在前，大脑侧只补 id 不在模型集合里的；同 id 只出现一次且取模型侧。
- `getConfig`（34-35）：先模型，未命中再大脑。
- `currentBrainId`（45-46）：`modelStore.currentModelId() ?: brainStore.currentBrainId()`，代理顺序是模型优先、大脑回落。
- 写与墓碑只透传大脑存储：`saveConfig` 37-38、`deleteConfig` 41-42、`setCurrentBrainId` 48-49、`deletedBrainIds` 52、`markBrainDeleted` 54-55、`clearBrainDeleted` 58-59。接口 `BrainConfigStore` 的写/墓碑方法没有漏接。

测试 `composite prefers the model store on id clash`、`composite currentBrainId proxies model current first`、`composite writes pass through to the brain store only` 与上述一致。

### ④ 迁移 — 通过

`ModelStoreMigration.kt`：

- 按 ID 补齐、只复制不移动：`topUp`（54-62）只在 kind 属于 `MODEL_API` / `AGENT_RUNTIME` / `SUBSCRIPTION`、id 不在模型存储、且不在模型墓碑时 `saveEntry`。没有任何 `brainStore` 写调用。平台 / 云端 Agent 不在 `MIGRATED_KINDS`（30-34）。
- 不覆盖已有条目：`59` `if (config.brainId in existing) continue`。模型、名称、地址、extra 不会被后续启动用大脑侧原文盖回。
- `migration_v1_done` 先数据后标志：`run`（41-46）先 `topUp`，再 `syncOneTimeState`（当前项、fallback 过滤、大脑墓碑同步），最后 `markMigrationV1Done()`。
- 迁移前后大脑存储字节不变：迁移只读 `listConfigs` / `currentBrainId` / `deletedBrainIds`。单测 `migration leaves brain store bytes unchanged` 比的是三个大脑 prefs 键。

非阻断：一次性同步在标志落下之前可能改写已存条目的 `fallbackModelIds`（`80-84`），不改其他字段；正常首启在 UI 之前跑完，模型页改不进这个窗口。标志用 `apply()` 而非 `commit()`（`ModelConfigStore.markMigrationV1Done`），崩溃时依赖 SharedPreferences 全量快照按入队顺序落盘，标志不会先于数据成为最后一份完整快照。

### ⑤ 删模型与密钥引用计数 — 不通过

`deleteModel`（`MainActivity.kt:11408-11446`）本身：

- 只删模型条目（`11416` `deleteEntry`）；内置才写模型墓碑（`11417`，仅 `SEEDABLE_BRAIN_IDS`）。
- 不调 `configStore.deleteConfig` / `markBrainDeleted`，不删 Agent，不删设备。
- 密钥在大脑列表、模型条目、Agent 三处 `authRef` 都不再出现时才 `deleteSecret`（`11420-11427`）。删条目在计数之前，不会把自己算进仍在用。这一段符合「删模型只删模型条目 + 模型墓碑」和三处计数。

阻断 A：非内置条目不写墓碑，下次启动会从大脑存储复活。

`11414-11417` 只有 `brainId in SEEDABLE_BRAIN_IDS` 才 `markBuiltinDeleted`。`SEEDABLE_BRAIN_IDS`（`BrainBootstrap.kt:239-250`）只有一线固定 id，不包括用户自建的 `model-api-*`。迁移是复制不移动，旧的通用大脑仍留在大脑存储。`topUp`（`ModelStoreMigration.kt:57-61`）看到 id 不在模型存储且不在墓碑里，就再复制回来。模型页删掉一条已迁移的自建模型，冷启动后条目回来。内置 id 有墓碑，不走这条。

阻断 B：删大脑仍无条件删密钥并注销注册表，模型副本还在用。

`deleteBrain` 函数体相对基线未改，但本分支把同一 `authRef` 复制进模型存储。`MainActivity.kt:11529` 仍无条件 `deleteSecret(authRef)`，不查模型条目、不查 Agent。`11537` 无条件 `unregisterConfig`，即使合并视图里还有同 id 的模型副本。结果是：设置页删大脑会把模型页仍在引用的密钥一并删掉，并在下次启动重新 `registerConfig` 之前把该 id 从引擎注册表摘掉。这与「密钥仅在脑 + 模型 + Agent 三处引用计数归零时才删」相反。`deleteModel` 那侧做到了计数，`deleteBrain` 这侧没有。

### ⑥ 删设备 / 删 Agent 边界 — 通过

对照 `bafe277c` 函数体：

- `deleteRemoteDevice` 与 `deleteDeviceRecords` 逐字节相同。仍是只删设备登记和同 `deviceId` 的配对记录，不删该设备上的 Agent、项目、会话（`MainActivity.kt:3083` 起）。本分支没改这条边界。
- `removeAgent`（`8552` 起）仍只 `agentStore.deleteAgent`，不删设备、不删大脑配置、不删模型条目。唯一差异是密钥仍在用的判断从 `configStore.listConfigs()` 改为 `runtimeConfigStore.listConfigs()`（`8564-8565`），把模型侧引用算进去，方向是少删密钥而不是多删记录。

非阻断：合并视图按 id 去重且模型优先。若同 id 的大脑副本 `authRef` 与模型条目不同，`removeAgent` 看不到被盖住的大脑侧引用。现行保存路径把通用条目的 `authRef` 铸成 `genericAuthRef(id)`，两边通常相同，不升为阻断。

## 阻断项

1. 设置页大脑分节状态行改读 `runtimeConfigStore`（`MainActivity.kt:9653,9695,9708,9732`），与列表仍读 `configStore`（10746-10747）及 `BrainBootstrap.kt:568-569` 的纪律冲突。
2. 删非内置模型不写墓碑，幂等补齐从大脑存储复活（`MainActivity.kt:11416-11417`，`ModelStoreMigration.kt:57-61`）。
3. 删大脑无条件删密钥并注销注册表，模型副本仍持有该 `authRef` / id（`MainActivity.kt:11529,11537`）。

②③④⑥ 通过，不抵消上面三条。未合并。
