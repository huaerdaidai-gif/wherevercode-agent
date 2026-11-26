<div align="center">

# WhereverCode Agent

**One workspace. Every Agent.**

**把你拥有的 AI Agent 汇集到一个 App，跨设备协作完成工作。**

开源 · Android / iOS 原生 · 本地优先

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE) [![Release](https://img.shields.io/github/v/release/huaerdaidai-gif/wherevercode-agent)](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/latest)

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md)

</div>

## 下载与快速开始

[Android APK](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/download/v0.5.6/wherevercode-agent-0.5.6-238.apk) · [iOS IPA](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/download/v0.5.6/WhereverCodeAgent-unsigned-0.5.6-238.ipa) · [Release v0.5.6](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/tag/v0.5.6) · [All releases](https://github.com/huaerdaidai-gif/wherevercode-agent/releases)

当前发行版：**v0.5.6 / Build 238**（早期开发版）。Android 提供 APK；iOS 提供未签名 IPA，需使用 Sideloadly、AltStore 等方式自行签名侧载，并遵守 Apple 的限制。

1. 安装 App，在「控制」中配置所需的模型或 Agent。
2. 在要执行任务的电脑或兼容终端上，复制 App 提供的 Hand 安装命令，启动设备端组件，完成配对及授权。已有 CLI Agent 需要预先安装并登录。
3. 选择对话方，开始对话或派工；需要多人协作时创建频道，并按所需网络模式配置连接。

无需为基本本地使用购买 VPS。模型服务可能需要自己的账号或 API key，免费额度及可用性由服务商决定。

## 为什么 WhereverCode？

你可能已经在电脑上使用 Codex、Claude Code、Antigravity、Grok Build 或 Kiro，也有多个 AI 账号和设备。WhereverCode 不试图替代它们，而是提供统一的移动工作台，让已有 Agent 按任务协作。

我们的目标是：**用户负责目标、决策和审批，Agent 负责派工、执行、汇报与交付。** 不绑定固定厂商或永久角色；代码和工作区优先保留在用户控制的设备上。调用云端 AI 时，授权发送的上下文仍会由对应服务处理。

## 当前能力

| 模块 | v0.5.6 状态 |
| --- | --- |
| 对话与项目 | 原生移动工作台、手动选择对话方、项目及文件管理相关实现 |
| 设备与 Agent | Hand 安装、配对、发现、远程执行及权限控制相关实现 |
| 模型 | 独立模型管理、免费服务商预设与模型路由相关实现 |
| 协作频道 | 双端本地频道、消息、成员、私聊、任务派工与状态反馈；全量双端对齐持续完善 |
| 局域网 | 双端已有 LAN 服务端实现；按实际网络和授权配置使用 |
| 云端协作 | 平台与 MCP 接入相关实现；v0.5.2 发布记录包含跨设备席位派单、离线补发及状态回流 |
| 安全 | 配对、加密通信、任务凭证与执行权限相关实现 |

上述为源码及发行记录支持的能力范围，不代表每条路径在当前版本均已完成真机端到端验收。v0.5.6 修复频道冻结，并进一步对齐 Android 频道页面与 iOS。

## 四类 Agent：一个工作台

| 席位 | 接入方式 | 状态 |
| --- | --- | --- |
| 设备 Agent | Hand 发现设备上的兼容 CLI Agent | 已有实现 |
| 平台 Agent | 平台连接与 Webhook | 已有接入实现，唤醒能力持续完善 |
| 云端 Agent | MCP 连接器 | 已有接入实现 |
| 网页 Agent | 用户自己的 ChatGPT、Claude、Gemini、Grok 等网页账号 | App 集成研发方向 |

单 Agent 对话由用户选择对话方；模型池的智能路由与 Agent 席位选择分开。四类席位统一热插拔及即时开关是持续完善的目标。

## 三层网络架构

- **本地层**：App 内本地频道服务；Android 另有默认关闭、绑定本机回环地址的 MCP 派工服务。
- **内网层**：局域网服务和 Hand 连接，按权限使用，无需固定公网服务器。
- **跨设备层**：现有平台连接支持远程协作；未来通过近场发现、公钥寻址、动态汇合点、NAT 穿透和中继兜底，逐步实现无需用户自购 VPS 的自组网。

三层全功能一致、自动故障转移及跨网络自组网仍是目标，不是当前交付承诺。

## 路线图

- [ ] 完成频道全量双端对齐：消息、编辑/撤回、文件引用、定时发送、派工及结构化回执。
- [ ] 将桌面网页席位验证成果集成进 App，遵守服务商账号与自动化规则。
- [ ] 完善设备、Agent、模型独立生命周期管理，席位热插拔与即时开关。
- [ ] 实现额度感知、恢复时间报告、任务轮换与断点续传；模型路由优先使用可用免费资源。
- [ ] 完善推送优先的协作信令与自主执行、审查、重试、恢复闭环。
- [ ] 完成三层服务集成及无固定 VPS 的自组网。

开源免费不等于第三方 AI 服务无限免费；信令无需模型推理不等于任务执行不消耗 token。

## 开发与贡献

本仓库是独立 App 项目，不是旧版 `wherevercode` 平台。Android 使用 Kotlin/Gradle，iOS 使用 Swift/SwiftUI。

[Android 构建](android/README.md) · [iOS 构建](ios/README.md) · [Hand 服务](server/README.md) · [历史 P0 审计](docs/p0/README.md) · [安全政策](SECURITY.md) · [贡献指南](CONTRIBUTING.md) · [Apache-2.0](LICENSE)

欢迎试用、提交问题和贡献代码。请注明版本、设备与复现步骤；不要上传令牌、私钥、签名文件或私人工作区内容。

*独立开源项目；提及第三方产品不代表官方合作或背书。*
