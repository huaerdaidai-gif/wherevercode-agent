# K70 多步骤执行修复 + DeepSeek 可配置大脑（2026-11-09）

分支：`feat/deepseek-agent-20261109`
基线：`origin/feat/cloudflare-brain-20261108`（含 Muse 的 stage2 / SIWC / Cloudflare / K70 前几轮修复）
范围：只改 Android 客户端；不改日本生产服务器、不改其他 Agent 分支、不合并 main、不部署、不新增付费服务。

## 1. K70 现象

用户消息：“列出工作区文件，再读取 README.txt，最后告诉我完整内容”。

真机返回过：

- `invalid_action`（`read_file` 缺 `path`，被 ToolRegistry 结构校验拒绝）
- `tool_repeated_failure`（同类失败再次出现，护栏 3 终结）
- `tool_no_progress`（连续两轮进展签名不变，护栏 2 终结）

已确认 K70 的 App UID 能读取 `README.txt`，内容为“蓝莓派 42 号”。因此这不是 Root / 文件权限问题，也不再排查权限。

## 2. 实际根因

### 2.1 工具结果没有按原生工具协议回填（主因）

修复前，`BrainEngine.appendFeedback()` 发送的是：

- 一条 assistant 文本消息：`{"name":"list_files","arguments":{}}`
- 一条 `role="tool"` 消息：`{"tool":"list_files","ok":true,...}`

问题在于：

1. assistant 消息没有 `tool_calls` 字段，也没有 call id；
2. tool 消息虽然有 `ChatMessage.toolCallId`，但 Cloudflare 桥接层把它丢掉了，请求体里没有 `tool_call_id`；
3. 因此模型无法把“list_files 的结果”识别为对它那次调用的回答。它继续试 `read_file`，又拿不到明确的“这是清单，请用 README.txt”配对，于是缺参 → `invalid_action`；再试 → `tool_repeated_failure`；空转 → `tool_no_progress`。

OpenAI 兼容协议（DeepSeek 与 Cloudflare 的 OpenAI-compatible 端点）要求：

```json
{"role":"assistant","content":null,
 "tool_calls":[{"id":"call_1","type":"function",
                "function":{"name":"list_files","arguments":"{}"}}]}
{"role":"tool","tool_call_id":"call_1","content":"..."}
```

这是本轮修复的核心。护栏只是终结者，不是始作俑者。

### 2.2 通用 SSE 解析器顺序错误

`StreamParser.parseChatCompletionChunk()` 先看 `finish_reason`，再解析 `delta.tool_calls`。当服务端把两者放在同一个 chunk（或把工具调用放在最后一块）时，解析器直接返回 `Done`，工具调用被静默丢掉，参数变成空 → `invalid_action`。

同时，标准 OpenAI/DeepSeek 流的工具参数是**跨多个 chunk 分片**的，逐行解析单块无法拼出完整 JSON。

### 2.3 多工具调用被拆成多条 assistant 消息

引擎每执行一个调用就追加一条 assistant 回显 + 一条 tool 结果。一轮有 N 个调用时，协议要求是“一条 assistant 带 N 个 `tool_calls`，后跟 N 条 tool 消息”，而不是 N 组 assistant/tool 对。拆开后原生 API 无法正确配对。

### 2.4 缺少参数修复层

即使回填修好，小模型仍可能发 `read_file` 不带 `path`。此前只有“缺参 → 回填 → 再来一次 → 终结”的路径，没有“从本轮已拿到的清单里补路径”的确定性修复。

## 3. 代码改动

### 3.1 协议层

- `BrainAdapter.kt`
  - 新增 `ChatToolCall(id, name, argumentsJson)`。
  - `ChatMessage` 新增 `toolCalls`，保留旧 `content` 回显给不支持原生工具协议的适配器。
- `ConversationStore.kt`
  - `Message` 新增 `toolCalls` 与 `toolCallId`，跨轮持久化配对信息。
- `ContextManager.kt`
  - 重建历史时保留 `toolCalls` / `toolCallId`，不再丢配对。
- `BrainEngine.kt`
  - 每轮只追加**一条** assistant 消息，携带该轮全部 `tool_calls`（含 id）。
  - 每个工具结果追加 `role="tool"` + 匹配的 `tool_call_id`。
  - 新增 `repairMissingReadPath()`：仅对 `read_file` 缺失/空白 `path` 生效，且只从本轮 `list_files` 已经返回的清单里选：
    - 清单只有一个文件 → 用它；
    - 清单多个文件但用户点名了其中一个 → 用用户点名的；
    - 没有清单 → 不修，仍走 `invalid_action`（不编造路径）。
  - 同轮 `list_files` + `read_file` 时，执行到 `read_file` 前会再尝试一次修复（此时清单已由同轮前一个调用填充）。
  - 修复动作写入 EventLog 的 `tool_arg_repaired`，便于真机排查。
- `StreamParser.kt`
  - 先解析 `delta.tool_calls`，再看 `finish_reason`；新增同 chunk 回归测试。

### 3.2 DeepSeek 可配置大脑

新增 `android/app/src/main/java/wherevercode/agent/brain/deepseek/`：

| 文件 | 作用 |
|---|---|
| `DeepSeekApiClient.kt` | OpenAI 兼容 `/chat/completions`、`/models`；原生 `tools`/`tool_calls`/`tool_call_id`；HTTP 错误分类；API Key 只进 Authorization 头，不进日志/错误/请求体 |
| `DeepSeekStreamParser.kt` | 有状态 SSE 解析：跨 chunk 累积工具参数、多工具调用、usage、reasoning、`[DONE]`、截断判定 |
| `DeepSeekBrainAdapter.kt` | 引擎侧 `BrainAdapter`；模型/Base URL 由配置提供；无 Key 明确报错；无模型不静默替换 |
| `DeepSeekCredentialStore.kt` | API Key 只存 `BrainKeyStore`（Keystore-backed EncryptedSharedPreferences），`authRef = "deepseek:api-key"` |

接线：

- `BrainBootstrap` 注册 `deepseek` adapter + config；首次只写入建议模型 `deepseek-flash` 和默认 Base URL，**不写任何 Key**，也不自动选中。
- `MainActivity` 控制台新增 DeepSeek 设置区：Base URL、模型、API Key、刷新模型列表、保存并选用、清除密钥。
- 模型列表来自账户实时 `GET /models`，保留动态选择；`deepseek-flash` 只是首次建议值，配置为空时适配器报错而不是偷偷替换。
- `BrainConfigValidator.isAllowedBaseUrl` 继续生效：只允许 https，本地模型允许 127.0.0.1/localhost。
- API Key 只存本机 Keystore，不进 `BrainConfig`、不进日志、不进 APK、不进仓库。

### 3.3 CI

`.github/workflows/android.yml` 的 push 分支列表增加 `feat/deepseek-agent-20261109`，让本分支能跑同一套单元测试和 APK 构建。

## 4. 测试

### 4.1 新增/修改的测试

- `DeepSeekStreamParserTest`（8 项）：文本/reasoning/usage、分片工具参数、多工具、无 finish_reason、缺 id、截断流、畸形行、provider error。
- `DeepSeekApiClientTest`（8 项）：原生 `tool_calls`/`tool_call_id` 请求体、URL 归一化、分片流、401/402/429、`/models`、空 Key 不发请求、请求体不含 Key。
- `DeepSeekBrainAdapterTest`（8 项）：身份/能力、文本+工具映射、缺 Key、空模型、坏参数、模型列表、凭据校验、清除 Key。
- `BrainEngineTest` 新增 5 项 K70 回归：
  - `list_files → read_file(缺 path) → 汇报` 全程无 Error，配对 id 正确；
  - 多文件时按用户点名的 README.txt 修复；
  - 同轮 list_files + 缺参 read_file 也能修复；
  - 一轮多个调用是“一条 assistant + N 条 tool”；
  - 没有清单时缺参仍是 `invalid_action`，不编造；
  - 清单有多个文件且用户没点名时，不猜，仍走 `availableFiles` 回填让模型显式选择。
- `StreamParserTest` 新增同 chunk `tool_calls` + `finish_reason` 回归。

所有测试使用 fake transport / fake adapter / 临时工作区，**没有**真实模型调用、真实 API Key 或网络计费。

### 4.2 本地执行结果

环境：JDK 17.0.20、Gradle 8.0、Android SDK platform 34 / build-tools 34.0.0。

- `gradle :app:testDebugUnitTest`：**542 项，0 失败，0 错误，1 跳过**。
  - 跳过项：`BrainEngineTest > tool_call_repeated fix - unreadable directory fails clearly without same-arg retry`。
  - 原因：本地以 root 运行，`chmod 000` 无法让 `listFiles()` 返回 null，测试通过 `assumeTrue` 主动跳过；这不是代码失败。
- `gradle :app:assembleDebug`：**BUILD SUCCESSFUL**。
  - 产物：`android/app/build/outputs/apk/debug/app-debug.apk`
  - 大小：16,366,868 字节（约 15.6 MiB）
  - SHA-256：`1f53b270fa118a7f187e2ba08236296e0abe55997f6367c0825b220b538abb73`
  - 签名：debug key，证书 SHA-256 `EE:74:3F:03:D8:81:EE:23:91:03:BC:BB:8B:A6:89:BA:55:B2:9F:5A:76:F0:ED:58:23:52:72:D0:11:A3:1F:81`（与 CI 固定的 debug keystore 一致）。
  - `aapt dump badging`：package `wherevercode.agent`，minSdk 26，targetSdk 28，compileSdk 34。

## 5. 明确未验证 / 未做的事

- **没有真实 DeepSeek API Key**，所以没有做真实 DeepSeek 推理；DeepSeek 线的验证是协议级 + 解析级 + 引擎级 fake 测试。
- **没有连接 K70 真机**（`adb devices` 为空），没有安装本轮 APK，没有在真机上重跑“列出文件→读 README.txt→汇报”。
- **Cloudflare 原生 `/ai/run` 路径没有改成 OpenAI 兼容端点**：引擎层已把 id 传下去，但 Cloudflare 既有桥接仍按原生 content 回填；该路径的真机接受度未在本轮重新验证。多步骤回归主要在引擎层和 DeepSeek 原生协议上验证。
- 没有改日本生产服务器、没有部署、没有合并 main、没有产生新的付费服务。
- 没有把未验证的功能说成已验证。

## 6. 真机复测建议（下一轮）

1. 在 K70 安装本轮 debug APK。
2. 控制台 → DeepSeek：填自己的 API Key，点“刷新模型列表”，选 `deepseek-flash` 或 `deepseek-v4-pro`，保存并选用。
3. 发：“列出工作区文件，再读取 README.txt，最后告诉我完整内容”。
4. 预期：`list_files` 一次、`read_file` 一次、文字回复包含“蓝莓派 42 号”；无 `invalid_action` / `tool_repeated_failure` / `tool_no_progress`。
5. 若仍失败，抓 EventLog 中的 `tool_arg_repaired`、`tool_result`、`sse_malformed_line` 与服务端返回，按实际事件继续定位；不要再用 Root 权限方向排查。
