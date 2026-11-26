<div align="center">

# WhereverCode Agent

**One workspace. Every Agent.**

**Bring your AI agents together in one app and collaborate across devices.**

Open source · Native Android / iOS · Local-first

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE) [![Release](https://img.shields.io/github/v/release/huaerdaidai-gif/wherevercode-agent)](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/latest)

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md)

</div>

## Download and get started

[Android APK](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/download/v0.5.6/wherevercode-agent-0.5.6-238.apk) · [iOS IPA](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/download/v0.5.6/WhereverCodeAgent-unsigned-0.5.6-238.ipa) · [Release v0.5.6](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/tag/v0.5.6) · [All releases](https://github.com/huaerdaidai-gif/wherevercode-agent/releases)

Current release: **v0.5.6 / build 238** (early development). Android provides an APK. The iOS IPA is unsigned and requires personal signing and sideloading, for example with Sideloadly or AltStore, subject to Apple's restrictions.

1. Install the app and configure a model or agent in Control.
2. On the computer or compatible terminal where work should run, copy the Hand installation command from the app, start the device component, pair and authorize it. Existing CLI agents must be installed and signed in first.
3. Choose an agent to chat or dispatch tasks; create a channel and configure the relevant connection for collaboration.

Basic local use does not require a purchased VPS. Model services may require your own account or API key; free-tier availability and limits depend on the provider.

## Why WhereverCode?

You may already use Codex, Claude Code, Antigravity, Grok Build or Kiro on your devices, alongside multiple AI accounts. WhereverCode is not trying to replace them. It provides a shared mobile workspace for coordinating agents you already have.

Our goal: **you set objectives, make decisions and approve actions; agents dispatch, execute, report and deliver work.** No permanent vendor or role lock-in. Code and workspaces remain on devices you control by default; authorized context sent to cloud AI is still processed by the relevant provider.

## Current capabilities

| Area | v0.5.6 status |
| --- | --- |
| Chat and projects | Native mobile workspace, manual conversation-partner selection, project and file management implementation |
| Devices and agents | Hand installation, pairing, discovery, remote execution and permission controls |
| Models | Separate model management, free-provider presets and model-routing implementation |
| Channels | Local channels on both platforms, messaging, membership, direct messages, task dispatch and status feedback; full parity is in progress |
| LAN | LAN server implementation on both platforms; actual use depends on network and authorization |
| Cloud collaboration | Platform and MCP integration code; v0.5.2 release records describe cross-device seat dispatch, offline redelivery and status feedback |
| Security | Pairing, encrypted communication, task credentials and execution permissions |

These are capabilities supported by source and release records, not a claim that every path has passed real-device end-to-end acceptance in this build. v0.5.6 fixes a channel freeze and further aligns Android channel screens with iOS.

## Four kinds of agents, one workspace

| Seat | Integration | Status |
| --- | --- | --- |
| Device agent | Hand discovers compatible CLI agents | Implemented |
| Platform agent | Platform connection and webhook | Integration implemented; wake-up support evolving |
| Cloud agent | MCP connector | Integration implemented |
| Web agent | Your own ChatGPT, Claude, Gemini, Grok and other web accounts | Planned app integration |

Direct conversations use manual partner selection; model-pool routing is separate from agent-seat selection. Unified hot-plugging and immediate enable/disable across all seat types remain goals.

## Three network layers

- **Local:** embedded local channel service; Android also includes an opt-in loopback-only MCP dispatch endpoint.
- **LAN:** local-network services and Hand connections, subject to authorization, without a fixed public server.
- **Cross-device:** existing platform-connected remote collaboration; future self-organizing networking using nearby discovery, public-key addressing, dynamic rendezvous, NAT traversal and relay fallback aims to remove the need for a user-purchased VPS.

Full feature parity across layers, automatic failover and cross-network self-organizing connectivity are targets, not current delivery guarantees.

## Roadmap

- [ ] Complete cross-platform channel parity: messaging, editing/recall, file references, scheduled sending, task dispatch and structured receipts.
- [ ] Integrate desktop web-seat validation work into the app, respecting provider account and automation rules.
- [ ] Improve independent device, agent and model lifecycles, hot-plugging and instant seat toggles.
- [ ] Add quota awareness, reset reporting, task rotation and checkpoint handoff; prioritize available free model resources.
- [ ] Improve push-first signaling and autonomous execution, review, retry and recovery loops.
- [ ] Complete three-layer integration and self-organizing networking without a fixed VPS.

Free and open-source software does not imply unlimited free third-party AI usage. Signaling without model inference does not mean execution consumes no tokens.

## Development and contributions

This is the standalone app, not the older `wherevercode` platform. Android uses Kotlin/Gradle; iOS uses Swift/SwiftUI.

[Android build](android/README.md) · [iOS build](ios/README.md) · [Hand server](server/README.md) · [Historical P0 audits](docs/p0/README.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [Apache-2.0](LICENSE)

Try it, report issues and contribute. Include your version, device and reproduction steps; never upload tokens, private keys, signing files or private workspace contents.

*Independent open-source project. References to third-party products do not imply affiliation or endorsement.*
