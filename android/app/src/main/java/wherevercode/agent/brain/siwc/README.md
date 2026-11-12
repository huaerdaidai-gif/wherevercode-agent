# BrainProvider 与 SIWC 适配器骨架

最小隔离实现骨架：只做“大脑可切换”的接口与 SIWC 适配器结构，**不含真实授权、不含真实推理调用、不产生费用**。

## 包含的文件

| 文件 | 作用 |
|---|---|
| `android/app/src/main/java/wherevercode/agent/brain/BrainProvider.kt` | `BrainProvider` 接口 + 请求/响应/能力/错误类型 |
| `android/app/src/main/java/wherevercode/agent/brain/siwc/SiwcBrainAdapter.kt` | SIWC（Sign in with ChatGPT）适配器骨架 + 本地令牌存储接口 + 官方常量 |
| `android/app/src/main/java/wherevercode/agent/brain/siwc/BrainProviderRegistry.kt` | 大脑注册与切换（一次只用一个大脑，可换，不写死） |

## 设计要点

- **大脑可切换，不写死 ChatGPT**。`BrainProvider` 是接口；ChatGPT（经 SIWC）只是第一个验证对象。`BrainProviderRegistry` 一次只选一个大脑，可换；未配置的大脑不能被选中，选不到就明确报“没有可用大脑”，绝不伪造回复。
- **只用官方支持的授权**。SIWC 走官方 OAuth 2.0 / OIDC + PKCE + 动态注册。禁止密码、会话 Cookie、逆向客户端、通用 API 转发、把一个用户的套餐分给另一个用户。
- **能力声明先行**。每个大脑用 `BrainCapabilities` 声明自己能做什么、不能做什么。SIWC Preview 的限制已编码进 `SiwcBrainAdapter.capabilities`：无 system 消息（用 `instructions`）、每次带全量历史、不可设 temperature / max_output_tokens、无 tool_search、无 Code Interpreter、无托管 MCP。调用方必须先看能力；不支持就明确报错，不静默降级。
- **令牌只存本地、用户控制**。`SiwcTokenStore` 的实现必须用 Android Keystore + EncryptedSharedPreferences / EncryptedFile，绝不放服务器、绝不记日志、绝不发给非官方端点。
- **不做复杂团队模式、不大改 UI**。这里只有大脑接口与适配器骨架；团队协作、会话 UI、派单逻辑都不在这里。

## 选型结论（见评估报告）

- **主推路线：Kotlin 原生实现 OAuth 2.0 / OIDC + PKCE 动态注册。**理由：Android 原生形态（Custom Tabs + 127.0.0.1 loopback 回调）、令牌进 Android Keystore（硬件级，比 JS SDK 的文件存储更强）、不依赖 Node 运行时做鉴权、调试直接（Kotlin + Android Studio）、APK 体积影响最小。官方 `@siwc/local` 是正确性参照，Kotlin 实现要对照 Cookbook / SDK 行为逐项验证。
- **Node v24.22.2 承载官方 `@siwc/local` 可行，但不做主推。**SDK 是桌面（Electron 主进程）取向：浏览器打开、存储目录、进程生命周期都要在 Android 上搭桥；令牌存文件而非 Keystore；鉴权要跑一个 Node 运行时并与 Kotlin 做 IPC，复杂度与风险都高于原生。可作为开发期交叉验证的参照，不做生产主路径。

## 集成路径（下一步，不在本分支做）

1. 把 `brain/` 下的 Kotlin 文件拷进 App 模块的同一包名下（App 已用 Kotlin 与协程，`kotlinx.coroutines.flow.Flow` 可直接用）。
2. 实现 `SiwcTokenStore`（Keystore + EncryptedSharedPreferences）。
3. 按官方 Cookbook 实现 OAuth 授权（Custom Tabs + 127.0.0.1 loopback 回调 + PKCE + 动态注册），再用 token 调 Responses API（`store=false`、`stream=true`、全量历史）。
4. 资格路线先定：先开源（走开源自助路线），或填 interest form 确认私有应用可用；并确认用户套餐等级为 Go/Plus/Pro。再做真机 E2E。

## 明确不做的事

- 不含真实授权流程、不含真实推理调用、不产生任何费用。
- 不合并 main、不部署、不碰生产。
- 不把 ChatGPT 写死成唯一大脑；不实现团队模式；不大改 UI。
