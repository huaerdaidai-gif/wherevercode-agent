# Cloudflare Workers AI 大脑

Cloudflare Workers AI（免费层）作为 WhereverCode 超级 Agent 的第二个可切换大脑，
与 SIWC（ChatGPT）平行。引擎不认厂商分支；两个大脑都能选，切换只改会话的 brainId。

## 已核实的官方事实（2026-11-08 核查）

全文见 `~/workspace/free-brain/cloudflare-verification-20261108.md`。

- 端点：`POST https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/run/{model}`，
  `Authorization: Bearer <token>`；`stream: true` 返回 SSE（`data: {"response": "…"}`，
  以 `data: [DONE]` 终结）。
- 免费额度：每天 10,000 Neurons，全账户共享；Free 计划超额只失败、不产生账单。
- 超额：官方错误码 `3036` / HTTP 429；第三方实测另见 `4006`，且封禁可能持续到首次使用后
  24 小时（与官方“00:00 UTC 重置”冲突）。客户端把两者都按超额处理，提示写清不确定性，
  绝不高频重试。
- 模型需 Workers Paid：`5035` / HTTP 403。容量不足：`3040` / HTTP 429，可重试。
  不带额度错误码（3036/4006/3040）的 429 按每分钟请求限速处理（Transport，
  可重试），不按“额度用完”报——超额与容量不足官方都给了明确内部码。
- 默认建议模型：`@cf/meta/llama-3.1-8b-instruct-fp8-fast`（必须带 `-fp8-fast` 后缀；
  同权重不同量化价差达 6 倍）。推理自选：`@cf/openai/gpt-oss-120b`。
  GLM-5 / Kimi K2.6+ / DeepSeek V4 不列入免费默认（且 kimi-k2.6、deepseek-v4-pro-0813
  官方已标注计划下架 2026-11-20）。
- Token 创建：Dashboard → Workers AI → Use REST API → Create a Workers AI API Token；
  权限 Account > Workers AI > Read；Account ID 同页复制。
- 函数调用是扁平形状：请求 `tools: [{"name", "description", "parameters"}]`，
  响应 `tool_calls: [{"name", "arguments"}]`（arguments 是 JSON 对象），
  不是 OpenAI 的嵌套形状。

## 文件

| 文件 | 作用 |
|---|---|
| `CloudflareApiClient.kt` | 原生 REST 客户端（HttpsURLConnection，零新依赖）：请求体构造、HTTP 错误映射（401 / 403-5035 / 429-3036 或 4006 → 超额 / 429-3040 → 可重试 / 429-无码 → 限速可重试 / 400 / 5xx） |
| `CloudflareStreamParser.kt` | SSE 解析器：`data: {"response"}` → TextDelta，`data: [DONE]` → Completed，chunk 里的 `tool_calls` / `usage` 如实解析 |
| `CloudflareBrainAdapter.kt` | `BrainProvider` 实现：配置校验、模型解析（显式 > 配置，绝不回退写死值）、流式翻译 |
| `CloudflareCredentialStore.kt` | Token 存储：复用 SIWC 的 `BrainKeyStore`（Keystore-backed），引用名 `cloudflare:token` |
| `CloudflareM2BrainAdapter.kt` | M2 桥接：角色映射、错误映射；超额（QuotaExceeded）标为 retryable 以便引擎按 fallback 切到 SIWC |

## 配置（BrainConfig，`brainId = cloudflare-workers-ai`）

- `kind = MODEL_API`，`baseUrl = https://api.cloudflare.com/client/v4/accounts`，
  `model` 首次为建议值（用户可在设置里改），`authRef = cloudflare:token`，
  `extra["account_id"]` = Account ID（不是密钥），
  `fallbackBrainIds = [siwc-chatgpt]`（超额时自动回落到 SIWC，若 SIWC 可用）。
- Token 只存本机 Keystore；Account ID、模型、baseUrl 存在普通配置里。
- 首次配置的建议模型只是写进用户可改字段的初值；请求时模型为空即报错，
  适配器绝不拿建议值当回退。

## 超额回落

引擎只在 retryable 错误上走 fallback；本桥接把 QuotaExceeded 标为 retryable=true，
引擎先同大脑重试 1 次（超额请求不计 Neuron，零成本），再按 fallback 切到 SIWC。
回落成功时超额提示以 TextDelta 留在回复里；回落失败时 Error 本身带同样文案。
已知小瑕疵：同大脑重试会让超额提示出现两次。

## 未证实清单（须 K70 实测）

1. 默认 8B 模型在真机上是否实际返回 `tool_calls`（函数调用只在 70B 模型页明确标注 Yes）。
2. 流式 chunk 里 `usage` 对象的字段名（本解析器同时接受两套写法；本地 Neuron 计数 90% 停
   待字段确认后再加，本增量不做假计数）。
3. `tool_calls` 是否会在流式 chunk 里出现（同步示例有，流式未明说）。
4. Token 是否只需 Read 权限（官方只明说 Read；第三方有称需 Read + Edit）。
5. 注册是否需要信用卡（官方未明说；K70 步骤写“按 Dashboard 实际提示操作”）。
6. 大陆直连 `api.cloudflare.com` 的可达性与延迟（Workers AI 不在中国网络产品清单中）。
7. 超额后实际恢复时间（官方称 00:00 UTC，第三方称可能到首次使用后 24 小时）。
8. 每个候选模型在 Free 账号下是否返回 403/5035（上线前逐个实测，不要只信清单）。
9. 隐私措辞张力：数据页称不用于训练；服务条款第 9 条保留“为提供和改进服务而使用”
   的表述，边界无官方解释——报告里如实写，不承诺“绝对不用于改进”。
10. revoke 只清本地 Token；彻底作废须用户在 Dashboard 删除该 Token（本 App 不存 Token ID）。

## 红线

- 单用户 BYO：用户自己的账号 + 自己的 Token，只服务自己；绝不共享额度、绝不转售
  （自助协议 §2.2.1(a)）；Token 绝不进仓库、聊天、APK 分发包或平台服务器。
- 绝不新增付费 API；超额只失败、不产生账单（Free 计划无超额计费路径）。
- 不合并 main、不部署、不公开仓库、不依赖旧 WhereverCode 运行时。
