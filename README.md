<div align="center">

# WhereverCode Agent

**One workspace. Every Agent.**

一个工作空间，连接你的 Agent、模型、设备与项目。

**Android · iOS · Open Source**

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md)

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-in%20development-informational)
![iOS](https://img.shields.io/badge/iOS-in%20development-informational)

</div>

> **开发中 / Work in progress**：本仓库包含实验性实现。页面入口、源码实现、自动化测试、模拟器运行和真机验证是不同阶段；不保证已有稳定版 APK/IPA，也不代表所有功能均已接通。

## 为什么是 WhereverCode？

WhereverCode Agent 旨在把 AI 对话、项目文件、任务执行和多 Agent 协作放在一个简洁的移动工作空间。它不将 Agent 永久固定为某个角色，也不要求所有工作都通过旧平台完成。**本仓库是独立应用 `wherevercode-agent`，不是旧的 `wherevercode` 调度平台。**

## 四个核心入口

| 入口 | 目标体验 |
| --- | --- |
| **对话** | 自然语言对话与任务执行；按会话选择 Agent/模型，默认使用用户配置的大脑 Agent/模型；查看进度、审批、暂停与停止 |
| **项目** | 连接授权项目来源，管理文件、任务、审批与交付成果 |
| **频道** | 人与 Agent 在授权频道中协作，查看消息、文件和任务；私聊能力须遵守服务端权限 |
| **控制** | 管理设备、Agent、模型、连接器、运行状态与审计 |

### 设计方向

- **多 Agent / 多模型**：可按能力与任务选择执行方，而非固定厂商角色。
- **多设备**：目标支持本机、局域网设备、经授权的远程计算机与 VPS。
- **多项目来源**：目标支持本地文件、GitHub、远程设备及受支持的网盘。
- **人类监督**：审批、暂停、停止、目标调整及执行记录是核心体验。
- **安全边界**：连接、文件访问与工具调用须遵守用户授权；未接通能力应明确显示状态，不应伪造结果。

以上描述的是**产品方向**，不是对当前版本逐项完成情况的承诺。

## Android 与 iOS

| | Android | iOS |
| --- | --- | --- |
| 目标形态 | 原生移动应用 | 原生移动应用 |
| 核心信息架构 | 对话 / 项目 / 频道 / 控制 | 对话 / 项目 / 频道 / 控制 |
| 开发状态 | 开发与验证中 | 开发与验证中 |
| 正式发布及真机验收 | 尚未在本 README 中确认 | 尚未在本 README 中确认 |

**Android：** [构建与架构历史记录](android/README.md)。其中包含早期可行性验证内容，不能直接当作最新版本验收结果。

**iOS：** 构建、签名、安装及真机验证说明待与对应源码及交付证据核对后补充；不要把未签名 IPA 当作可直接安装的正式版本。

### 功能状态如何判断？

| 状态 | 含义 |
| --- | --- |
| 设计中 | 已有需求或原型，尚未确认实现 |
| 已有入口 | UI 可见，后端能力可能尚未接通 |
| 已实现 / 待验证 | 有代码或构建结果，但缺少相应运行证据 |
| 已验证 | 有明确的测试、模拟器或真机证据，并注明验证环境 |

本项目的多页面功能清单将逐项维护；**页面数量不等于功能完成数量**。

## 安装与开发

当前不在 README 中提供未经核实的安装包链接。请以仓库实际发布记录和各平台构建说明为准。Android 调试签名 APK 可能无法覆盖安装历史签名版本；调试包不等于生产发行版。

- Android：参见 [android/README.md](android/README.md)，使用与项目兼容的 Gradle 环境；仓库可能不包含完整 Gradle Wrapper。
- iOS：需要相应 Apple 构建和签名流程；请在有可复现文档与验证记录后发布安装说明。

## 安全与贡献

请阅读 [SECURITY.md](SECURITY.md) 和 [CONTRIBUTING.md](CONTRIBUTING.md)。**不要提交账号密码、OAuth Token、API Key、签名密钥或私有基础设施配置。** 报告测试时，请区分单元测试、CI、模拟器与真机验证。

公开发布前应检查所有可达 Git 历史、分支和标签中的敏感信息与第三方许可证；只删除当前分支文件并不能清除历史泄露。

## License

[Apache License 2.0](LICENSE)。

> WhereverCode Agent 为独立开源项目。与第三方 Agent 或服务的兼容性，不代表获得其官方认可或合作关系。
