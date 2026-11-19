# B3 iOS 头审查：feat/ios-model-decouple-20261119 @ 78a116ef

- **结论：不可合并**
- **被审头**：`78a116ef39b80616c6451bf5c6ce72ae9db6a1e6`（`fix(ios): remove deprecated connection probe section from BrainSettingsView per diff 11`）
- **基线**：`main` @ `bafe277cad7cb9296480c9935ab8ba5e2ed8962e`
- **设置页对照**：`24522af^` = `75c7218cf33def70431f4d4b5082caf3bef303df`（改名提交 `24522af0bf76b8b7d565ab89f2cbad1b058e029a` 之前）。`main` 上带着错误改名，本头把设置页三处文案改回，**不判缺陷**。
- **范围**：只读被审树。本文件是审查记录，不是被审改动。

## 阻断清单

1. **删 Agent 没有释放「无共享引用的密钥」。** 设备半句成立，Agent 半句不成立。见第 4 节。不修这一条不能合并。

其余第 1、2、3、5、6 项与第 5 节两处定性，按下面的行号证据通过。非阻断观察附在文末，不进入阻断清单。

---

## 1. 门禁五条

### ① 适配器 id 用 `GenericOpenAiBrainAdapter.adapterId`，无硬编码 `"generic"`

通过。常量值是 `model-api-generic`：

- [GenericOpenAiBrainAdapter.swift](ios/WhereverCodeAgent/Brain/GenericOpenAiBrainAdapter.swift) 第 80 行：`public static let adapterId = "model-api-generic"`

模型页与模型存储的新建路径都引用该常量，两份源码里没有 `adapterId: "generic"` / `adapterId = "generic"`：

- [ModelRegistry.swift](ios/WhereverCodeAgent/Models/ModelRegistry.swift) 第 34 行：`adapterId: String = GenericOpenAiBrainAdapter.adapterId`
- 同文件第 448 行 `addModelConfig`：`adapterId: GenericOpenAiBrainAdapter.adapterId`
- [ModelSettingsView.swift](ios/WhereverCodeAgent/Views/ModelSettingsView.swift) 第 64 行（通用 API 弹窗）与第 348 行（常用服务商）：`adapterId: GenericOpenAiBrainAdapter.adapterId`

### ② authRef 用 `model-api:<id>`，无 `model:<id>:key` 残留

通过。`ModelSettingsView.swift` 全文只有两处新建 authRef，都已改掉此前 64/68、348/352 的硬编码：

- 第 68 行：`authRef: "model-api:\(modelId)"`
- 第 352 行：`authRef: "model-api:\(modelId)"`

存储层同一格式：

- [ModelRegistry.swift](ios/WhereverCodeAgent/Models/ModelRegistry.swift) 第 441 行：`let authRef = "model-api:\(modelId)"`

两文件均无 `"model:\(modelId):key"`。

### ③ `ModelRegistry` 不再自带 `isLocalUrl`

通过。`ModelRegistry.swift` 无 `func isLocalUrl`。本地判断只复用校验器：

- 第 512 行：`configs.filter { $0.kind == .modelApi && BrainConfigValidator.isLocalModelUrl($0.baseUrl ?? "") }`

### ④ `ModelSettingsView` 已添加列表严格三分组

通过。已添加区只有三个 `groupTitle`，没有「本地模型」单独成组：

- 第 175–198 行 `addedModelsSection`：`模型 API` / `CLI 会员` / `订阅授权`
- 分组来源是模型存储，不是大脑存储。第 507–520 行：`modelApis` = `kind == .modelApi`（本地 URL 并入这一组），`cliBrains` = `.agentRuntime`，`subscriptions` = `.subscription`
- `localModels`（第 511–513 行）只是子集计数，视图未再画第四组

### ⑤ `CompositeBrainConfigStore` 已接线

通过。

**App 注入。** [WhereverCodeAgentApp.swift](ios/WhereverCodeAgent/App/WhereverCodeAgentApp.swift)：

- 第 112–116 行：`CompositeBrainConfigStore(modelStore:brainStore:)` 交给 `BrainBootstrap.buildRegistry(configStore: composite, …)`
- 第 131–133 行 `rebuildBrainStack`、第 164–165 行 `makeBrainEngine` 都把 `compositeBrainConfigStore` 交给引擎

**`currentBrainId` 代理模型存储。** [ModelRegistry.swift](ios/WhereverCodeAgent/Models/ModelRegistry.swift) 第 549–550 行：

```swift
public func currentBrainId() -> String? {
    modelStore.currentModelId() ?? brainStore.currentBrainId()
}
```

聊天默认项写入模型存储，不写大脑当前项：[LocalData.swift](ios/WhereverCodeAgent/Data/LocalData.swift) 第 265 行 `modelSettingsModel.configStore.setCurrentModelId`。

**写入与墓碑只落 `brainStore`。** 同文件第 553–575 行：`saveConfig` / `deleteConfig` / `setCurrentBrainId` / `deletedBrainIds` / `markBrainDeleted` / `clearBrainDeleted` 全部转发给 `brainStore`，不写 `modelStore`。

**聊天发送路径读合并视图。**

- 可用列表：`LocalData.swift` 第 385 行 `compositeBrainConfigStore.listConfigs()`
- 发送解析：第 407–414 行用 `modelSettingsModel.currentModelId` 与 `compositeBrainConfigStore.getConfig`
- 真正发送：第 904–914 行 `makeBrainEngine`（内部 configStore 是 composite）+ `brainIdOverride: selectedBrain`
- 气泡与输入条标签：第 1412、1423、2040 行 `compositeBrainConfigStore.getConfig`

`LocalChatModel` 已不再读 `brainSettingsModel`。

---

## 2. 设置页「大脑」分节，以及 brain 红线

通过。不把相对 `bafe277c` 的三处回滚判成缺陷。

`SettingsView.swift` 相对 `24522af^`（`75c7218`）diff 为空，整文件逐字相同。分节在第 106–119 行：`Section("大脑")`、`Text("管理大脑")`、说明句以「大脑是可替换的推理后端」起头。相对 `bafe277c` 的 diff 就是这三处从「模型」改回「大脑」，与 `24522af` 引入的改名互为逆操作。

`git diff --quiet bafe277c 78a116ef -- ios/WhereverCodeAgent/Brain` 成立，`BrainConfig.swift` 同命令成立。iOS 树没有 `BrainLoginRows` 文件；Android `BrainLoginRows.kt` 与 `BrainConfig.kt` 相对 `bafe277c` 同样为零 diff。

---

## 3. 迁移

通过。实现在 [ModelRegistry.swift](ios/WhereverCodeAgent/Models/ModelRegistry.swift) `ModelMigrationManager.runStartupMigration`（第 584–639 行）。

- **只拷不移**：只对 `modelStore.saveConfig` / `markModelDeleted` / `setCurrentModelId`。不调用 `brainStore.saveConfig`、`deleteConfig`、`setCurrentBrainId`、`markBrainDeleted`。大脑原件留在 `brain.configs_json`。
- **按 ID 幂等**：第 612 行与第 634 行都是 `existingModelIds` 已有则跳过。`model.migration_v1_done` 只包住墓碑、fallback 过滤和当前项的一次性同步；之后每次启动仍按缺 ID 补拷。
- **已墓碑不复活**：两次都要求 `!deletedTombstones.contains(brain.brainId)`。首次另把 `brainStore.deletedBrainIds()` 抄进模型墓碑（第 602–605 行）。
- **模型页改过的不被覆盖**：已存在的 `modelId` 直接跳过，不回写。
- **迁移目标只含三分组**：第 593–594 行 `modelApi || agentRuntime || subscription`，平台/云端 Agent kind 不进模型库。
- **大脑原始字节不变**：迁移对大脑只读 `listConfigs` / `deletedBrainIds` / `currentBrainId`。这三处在 [BrainConfig.swift](ios/WhereverCodeAgent/Brain/BrainConfig.swift) 第 366–373、392–394、402–409 行只 `kv.get`，不 `persist`。落盘键是 `model.*` 与 `model.migration_v1_done`，不是 `brain.configs_json` / `brain.current_brain_id` / `brain.deleted_brain_ids_json`。

---

## 4. 删除边界

### 删设备：通过

[AgentRegistryStore.swift](ios/WhereverCodeAgent/Data/AgentRegistryStore.swift) 第 201–207 行 `deleteDeviceCascade`：

- `markDeviceDeleted` 只写 `devices.deleted.v1`（第 147–152 行）
- `HandIdentityManager.deleteDeviceCredentials` 只删 Keychain 账户 `device:<id>:public_key`（[HandClient.swift](ios/WhereverCodeAgent/Services/HandClient.swift) 第 350–355 行），即配对信任
- `agents.removeAll { $0.agentId == deviceId }` 只拿掉宿主那一行

`isDeviceHost`（第 73–75 行）是 `kind == .device && access == .app && agentId == deviceId`。`agentId` 是主键，子 Agent 的 id 不是设备 id，因此不会被这行删掉，循环改字段的旧逻辑已去掉。设备列表只展示宿主（[DevicesView.swift](ios/WhereverCodeAgent/Views/DevicesView.swift) 第 270 行）。宿主上 `device.agentId == devId`，第 259–261 行与第 1065–1067 行的补刀 `deleteAgent` 走不到。

子记录字段保持原样，由 `HandClientTests` 相对本头的断言钉住（`deviceId` 仍等于设备 id，不再被解绑改写）。

### 删 Agent：阻断

要求是：只删 Agent 记录，并删掉已经没有任何共享引用的密钥。现头只做了前一半。

- UI：[FlagshipRouteDestinations.swift](ios/WhereverCodeAgent/FlagshipUI/FlagshipRouteDestinations.swift) 第 1483–1488 行，确认移除后只调用 `agents.deleteAgent(agentId:)`
- 存储：`AgentRegistryStore.deleteAgent` 第 192–196 行只把该 id 从 `agents.records.v1` 拿掉，不读 `authRef`，不调用 `deleteSecret`
- 模型删除已经按三处引用计数放密钥（`ModelRegistry.swift` 第 466–475 行：其他模型、`brainStore`、`agentRegistryStore`）。Agent 删除没有对称的这一步

后果：先删模型（密钥因 Agent 仍引用而保留），再删掉最后一个引用该 `authRef` 的 Agent，密钥留在 Keychain。Android 同操作会放密钥：`MainActivity.kt` 第 8536–8547 行在其他 Agent 与大脑配置都不再引用时 `brainKeyStore.deleteSecret`。iOS 现头的添加 Agent 表单本身不写 `authRef`（第 1870–1881 行），但记录字段和模型侧计数已经把 `AgentRecord.authRef` 当成活引用，删除侧必须同样计数。这是本头的阻断项。

---

## 5. `BrainSettingsView.swift` 两处定性

### (a) 「测试当前连接」独立分节：已删除

通过。相对 `bafe277c`，本头只删了这节和 `runConnectionProbe`（`testStatusMessage` / `isTestingConnection` 状态一并去掉）。现文件 `Form` 在第 221 行结束，没有 `Section("测试当前连接")`，全文也没有 `runConnectionProbe`。

### (b) 第 347 行起「已添加的模型（分类呈现）」：大脑管理页自己的列表，不是门禁④要清的借大脑混杂路径

判据：

1. **数据源是大脑存储。** 第 349–360 行过滤的是 `BrainSettingsModel.configs`（`brainConfigStore`），不是 `ModelConfigStore` / `model.configs_json`。
2. **入口是设置页大脑分节。** `SettingsView` 第 107–108 行 `BrainSettingsView(model: appState.brainSettingsModel)`。模型路由另走 `ModelSettingsView(model: appState.modelSettingsModel)`。两条页面。
3. **相对基线，这一节正文没动。** 本头对 `BrainSettingsView` 的 diff 只有删除「测试当前连接」。第 347–398 行的分组，包括第 391–396 行多出来的「本地模型」，在 `bafe277c` 上就是这样。
4. **门禁④的对象是模型页。** 它验收的是 `ModelSettingsView.addedModelsSection` 的三个 `groupTitle`，并且不准单独成组「本地模型」。大脑页保留第四组，正好说明它不是那条被收口的模型页路径。

不判缺陷，不放入阻断清单。

---

## 6. 路由与自定义服务商存储

通过。

- [ModelRegistry.swift](ios/WhereverCodeAgent/Models/ModelRegistry.swift) 第 291–326 行 `IndependentModelRoutingStore`：新键 `model.routing`，旧键 `wherevercode_model_routing`。`copyLegacyIfNeeded` 仅当新键为 nil 时把旧字节拷过来。`save` 只 `set` 新键。
- 第 328–374 行 `IndependentCustomFreeProviderStore`：新键 `model.custom_free_providers`，旧键 `wherevercode_custom_free_providers`。同样只在新键为空时首拷；`add` / `remove` 只写新键。
- App 第 97–98 行把这两个实现注入 `ModelSettingsModel`。模型页读写走这里（`ModelSettingsView.swift` 第 428 行 `customProviderStore.list()`）。

旧键没有回写路径。

---

## 非阻断观察（不挡合并）

1. **打开历史会话的回落仍读大脑当前项。** `LocalData.swift` 第 467 行：`conversation.brainId ?? appState.brainConfigStore.currentBrainId()`。发送解析和引擎配置读取已经走合并视图（第 1.⑤ 节），这一行只影响「会话自己没记 brainId」时的选中 id。
2. **模型页删掉同 ID 后，合并视图会重新露出大脑原件。** `CompositeBrainConfigStore.listConfigs`（第 535–539 行）只按「模型 id 集合」遮蔽大脑行，不看模型墓碑。这与「只拷不移」一致：迁移不会把墓碑条目拷回模型库，但大脑原件还在，聊天合并视图就能看见。不是迁移复活。
3. **引擎降级仍读旧路由键。** `BrainBootstrap.swift` 第 287 行 `UserDefaultsModelRoutingStore`（键 `wherevercode_model_routing`）。`brain/**` 相对 `bafe277c` 零差异，本头不能改这里。模型页写入 `model.routing` 不会自动作用到聊天 failover。存储隔离本身成立。
4. **大脑页新建通用模型仍用 `generic:<id>`。** `BrainSettingsView.swift` 第 133 与 151 行。这两行不在本头 diff 里，是基线既有，且不属于门禁②点名的 `ModelSettingsView`。通用适配器的 Keychain 前缀只认 `model-api:`（`GenericOpenAiBrainAdapter.swift` 第 16–28 行）。记在这里，避免下一轮把它误当成设置页回滚缺陷或本头回退。
