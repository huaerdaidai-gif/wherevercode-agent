<div align="center">

# WhereverCode Agent

**One workspace. Every Agent.**

An open-source mobile workspace connecting agents, models, devices, and projects.

**Android · iOS · Open Source**

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md)

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-in%20development-informational)
![iOS](https://img.shields.io/badge/iOS-in%20development-informational)

</div>

> **Work in progress.** A visible UI entry, source implementation, automated test, emulator run, and real-device validation are different milestones. This repository does not promise a stable downloadable APK or IPA.

## What is WhereverCode Agent?

WhereverCode Agent aims to bring AI conversations, project files, task execution, and multi-agent collaboration into one minimal mobile workspace. Agents are not permanently tied to predefined roles. This repository, **`wherevercode-agent`**, is a standalone app, separate from the older `wherevercode` orchestration platform.

## Four main areas

| Area | Intended experience |
| --- | --- |
| **Chat** | Natural-language conversations and tasks; select an agent/model per conversation, defaulting to the user's configured brain agent/model; monitor progress and approvals |
| **Projects** | Connect authorized project sources and work with files, tasks, approvals, and deliverables |
| **Channels** | Collaborate in authorized channels through messages, files, and tasks; direct messages require server-side authorization |
| **Control** | Manage devices, agents, models, connectors, runtime status, and audit records |

### Product direction

- **Multi-agent and multi-model:** Choose based on capabilities and tasks, not fixed vendor roles.
- **Multi-device:** Aim to support local devices, LAN computers, and authorized remote hosts/VPS.
- **Multiple project sources:** Aim to support local files, GitHub, remote hosts, and supported cloud drives.
- **Human oversight:** Approvals, pause, stop, goal changes, and execution history.
- **Permission-aware:** Connections, file access, and tools must honor user authorization; unavailable features must be clearly labeled.

These are **design goals**, not claims that every capability is shipped.

## Android and iOS

| | Android | iOS |
| --- | --- | --- |
| Target | Native mobile app | Native mobile app |
| Navigation | Chat / Projects / Channels / Control | Chat / Projects / Channels / Control |
| Status | Under development and verification | Under development and verification |
| Stable release and real-device acceptance | Not confirmed here | Not confirmed here |

**Android:** See the [Android development notes](android/README.md), which include historical feasibility work and should not be treated as a current acceptance report.

**iOS:** Reproducible build, signing, installation, and real-device instructions will be linked after checking the corresponding source and evidence. An unsigned IPA is not a ready-to-install release.

## Feature maturity

| Label | Meaning |
| --- | --- |
| Planned | Requirement or design exists; implementation not confirmed |
| UI entry | A visible entry exists; backend may not be connected |
| Implemented, unverified | Source/build evidence exists; runtime evidence is incomplete |
| Verified | Explicit test, emulator, or real-device evidence, with environment noted |

**Screen count is not a measure of completed functionality.**

## Build and install

No unverified download links are provided here. Refer to actual repository releases and platform build documentation. A debug-signed Android APK is not a production release and may not update packages signed with a different key.

Android builds require a compatible Gradle environment; a complete Gradle Wrapper may not be included. iOS distribution requires an appropriate Apple build/signing process.

## Security and contributions

Read [SECURITY.md](SECURITY.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Never commit passwords, OAuth tokens, API keys, signing keys, or private infrastructure configuration. Distinguish unit tests, CI, emulator results, and real-device validation in reports.

Before public release, review reachable Git history, branches, tags, and third-party licenses; removing a file from the current branch does not erase its history.

## License

[Apache License 2.0](LICENSE).

> WhereverCode Agent is independent. Compatibility with third-party agents or services does not imply endorsement or partnership.
