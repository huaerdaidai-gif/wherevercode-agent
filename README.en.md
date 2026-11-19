<div align="center">

# WhereverCode Agent

**One workspace. Every Agent.**

An open-source Android and iOS workspace connecting AI conversations, projects, tasks, models, and devices.

**Android · iOS · Multi-Agent · Apache-2.0**

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md)

[Android development](android/README.md) · [iOS development](ios/README.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md)

</div>

> **Work in progress (documentation reviewed 2026-11-20).** Source code, visible UI, automated tests, simulator runs, device installation, and end-to-end acceptance are separate milestones. This README does not promise a stable downloadable APK or IPA.

## What is WhereverCode Agent?

WhereverCode Agent aims to bring AI conversations, project files, task execution, multi-agent collaboration, and device controls into one **minimal but capable** mobile workspace. Agents are not locked to vendors or permanent roles. Users should be able to choose models, workers, and devices according to the task, and supervise progress, approvals, and outcomes.

This repository, `wherevercode-agent`, is the standalone app—not the older `wherevercode` orchestration platform. Core single-agent operation should not require the old platform or its channels. Remote collaboration depends on available services and authorization.

## Product experience and cross-platform consistency

The design goal is **Claude/Codex-like simplicity, drawer-based organization, without sacrificing functionality**. Android and iOS should share navigation concepts, naming, state semantics, and permission boundaries.

| Area | Intended experience |
| --- | --- |
| **Chat** | Conversations, agent/model selection, task initiation, and execution feedback |
| **Projects** | Project sources, files, context, deliverables, and associated tasks |
| **Channels / Collaboration** | Authorized communication and task tracking; never assume a missing server API exists |
| **Control** | Devices, agents, models, connections, runtime state, audit logs, and advanced settings |

This is a **product target**, not a claim of full parity on the current `main`. The 2026-11-19 cross-platform alignment work identified differences in channel entry, control surfaces, and interaction patterns. Verify each item against current code and actual screenshots.

## Implementation and verification boundaries

| Scope | Evidence in repository documents/source | Further verification required |
| --- | --- | --- |
| Android | Kotlin, Gradle, Chaquopy; local task/control/UI implementation and audit records | Current-main build, embedded CLI execution, real-device behavior, signing/upgrades, UI parity |
| iOS | Native Swift/SwiftUI client; documented channel, task, connection, and Keychain implementations | Current-main build, signing, installation, live server integration, Android parity |
| Multi-agent / multi-device | Task leases, execution fences, local/remote transport and design records | Authorized end-to-end dispatch, acknowledgements, recovery |
| Autonomous workflows | Scheduler, retries, and some recovery-related mechanisms documented | Durable triggers, restart deduplication, budgets, complete autonomous recovery |
| Security | Permission, write guard, stop confirmation, and signing controls documented | Full repository/release audit and device regressions |

Use evidence stages: **Planned → UI entry → Source implementation → Automated test → Simulator verification → Real-device verification → End-to-end acceptance**. Missing evidence means *not verified*.

Verification update (2026-11-20): Android build 203 from main commit 42798529 has been installed in place on a Xiaomi 12 and double-checked via versionCode and APK SHA-256; the iOS build 203 unsigned IPA has been produced by CI, but real-device sideload acceptance is not yet complete. The dual-platform screen-by-screen re-walk is still pending and is not claimed here as accepted.


See the [P0 audit and implementation gates](docs/p0/README.md), which include historical branch context and must not be treated as a current-main acceptance report.

## Build and install

### Android

Follow [android/README.md](android/README.md) and current Gradle configuration. The development notes list JDK 17, Gradle 8.2.1, SDK 34, Chaquopy 15.0.1, and Python 3.11. With compatible tools and `JAVA_HOME` / `ANDROID_HOME` configured:

```sh
./scripts/build-android-p0.sh
```

A complete Gradle Wrapper may not be present. Debug signing/package identity may differ from production; a debug APK is **not** a production upgrade. Building an APK does not prove the embedded Node runtime or agent CLI works.

### iOS

See [ios/README.md](ios/README.md) for the native Swift/SwiftUI project and build instructions. A compatible macOS/Xcode environment and signing are required for installation. An unsigned IPA is **not** a ready-to-install release. Historical server API documentation requires revalidation.

No unverified APK/IPA download links are listed here.

## Self-deployment (single-Agent edition, usable now)

You need no server and no one's permission or account:

1. **Install the App**: download the signed Android package from this repository's Releases and install it directly. For iOS, download the unsigned package and self-sign it with your own Apple ID using Sideloadly / AltStore.
2. **Add a free model key**: under "Control → Models" the App ships presets for 6 free model providers (Google AI Studio, OpenRouter, Groq, SiliconFlow, Zhipu GLM, Cloudflare Workers AI). Register on the provider's site, paste your key into the App, and start chatting at zero cost. Paid models are entirely optional and never enter smart routing by default.
3. **Connect your devices (Hand, one line)**: on the computer or phone you want to dispatch work to, open a terminal, paste the single Hand command shown in the App, and press Enter — the device and the Agents on it are discovered by the App, and you can then pick an Agent to chat with or dispatch tasks to. Hand is not a separate program to install; the same line works on every device.

**About multi-Agent channels**: channel collaboration (several Agents relaying work in one channel) depends on the MCP server component, which this release does not yet include as a runnable server. It will be published in stages (Tier 1 / 2 / 3). Its deployment shape is already fixed: local (on-device, integrated into the App, zero deployment), LAN, and cross-device (public / mesh remote) topologies are all fully functional with no feature downgrade, and where deployment is needed it takes at most one pasted terminal command. Until then, this release's scope is the single-Agent mode above.

## Architecture principles

- **Replaceable models and agents:** No permanent vendor-specific brain or worker roles.
- **Local-first, remote-optional:** Standalone work where possible; remote access requires explicit authorization.
- **Human control:** Pause, stop, change-goal, approval, and retry must respect real execution acknowledgements.
- **Traceable execution:** Distinguish requested, dispatched, running, confirmed, failed, and pending-confirmation states.
- **Least privilege:** Protect file writes, tool calls, device controls, and credentials; never report failed actions as successful.

## Roadmap (goals, not completion claims)

1. Reconcile Android/iOS navigation, channels, control surfaces, error/empty states, and permissions.
2. Verify builds, installation, critical operations, and remote execution acknowledgements.
3. Improve autonomous planning → execution → verification → recovery with user oversight.
4. Publish verified screenshots, release notes, reproducible builds, and contribution guidance.

## Screenshots

Screenshots must be traceable to a commit, platform, device or simulator, and verification status. Design prototypes must never be presented as screenshots of a running build. Verified screenshots will be added after review.

## Security, contributions, license

Read [SECURITY.md](SECURITY.md), [CONTRIBUTING.md](CONTRIBUTING.md), and [Apache License 2.0](LICENSE). Never commit tokens, signing keys, personal data, or private infrastructure configuration. Review all reachable Git history, branches, tags, and third-party licenses before release; deleting a file from HEAD does not erase history.

---

*WhereverCode Agent is independent. Compatibility with third-party agents or services does not imply endorsement or partnership.*
