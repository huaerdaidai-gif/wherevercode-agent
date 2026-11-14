# Native dual UI verification record — 2026-11-14

This is an evidence ledger, not a completed product sign-off.

## Source provenance

- Target repository: huaerdaidai-gif/wherevercode-agent.
- main at inspection: a9206e1.
- Assembly baseline: 73ee623 (feat/v5-dual-70-assembly-20261114).
- PR #9 head: e14426a (open draft). Its head contains both refs above.
- Independent branch: feat/native-dual-ui-delivery-20261114, PR #10.
- No merge, force push, deployment, or changes to the older repository.

## Local checks completed

- `python -m unittest server.test_hand_server -q`: 23 tests, PASS.
- `git diff --check`: PASS at every code commit.
- Parsed all Android resource XML with ElementTree: PASS.
- Compared Android / iOS internal route IDs: exactly 70, same order, unique.
- Compared Android Manifest, iOS pbxproj and entitlements byte-for-byte to
  e14426a: unchanged. Existing application IDs, signing configuration and
  permissions retained.
- Existing Brain/Services/Data code and Android task dispatch/control code
  retained. No database migration or destructive data reset added.

## Native CI

Initial a8ded8a iOS run 43600638218 built a generic iOS device app and
packaged/uploaded an unsigned IPA. Downloaded and unzipped in the workspace.
This early build is not the final revision; its simulator job was superseded
by later changes. Do not count cancellation as a pass.

Final CI run IDs, test totals and artifact hashes will be added after review.

## Required evidence boundaries

Android emulator uses a generic hardware profile with a K70-size viewport
(1440×3200, density 640). This is not Redmi K70 hardware verification.
iOS CI prefers an available iPhone 17 simulator; the actual selection is in
its runner log. Host-rendered test attachments are not simulator screenshots;
only UI-test captures from the running app count as runtime UI evidence.

Real-provider inference with user credentials, a physical K70 installation,
an iPhone 17 installation, old signed-build upgrade and cross-device sync
remain unverified. No user signing material exists in this workspace.

## Current known capability gaps

- iOS local workspace tool execution and file browser/editor are not wired.
- iOS direct external-Agent/Hand chat, discovery and pairing are not wired.
- iOS local task executor is not wired: task reads and audit events work;
  pause/resume/stop/retarget are disabled and say why.
- Platform task control/approval depends on real server interfaces. Existing
  reserved controls stay disabled where those interfaces are absent.
- iOS console contains process-scoped BrainEngine events, not a persistent
  full workspace console. Task events use the existing durable store.
- Shared navigation/home structure is implemented; complete per-workspace
  visual and functional parity has not yet been certified.

## First completed Android run

Run 43601145902, source 1ab79e4: Android CI PASS, 309 JVM suites,
2730 tests / 0 failures / 0 errors / 1 skipped. Debug and compatibility APKs
uploaded; debug APK and XML reports downloaded and read locally.

Emulator run 43601145915 failed the existing control test: both test-created
blocker tasks failed for lack of a configured brain before pause could land.
The queued target then failed as well. This is retained as a failed run.
The correction uses an explicitly labelled, session-bound active-work test
fixture to occupy existing scheduler capacity. The controlled task is still
created through the real native form; pause/resume/stop are tapped through
UI and checked against RoomControlStore/RoomTaskStore. Fixture state is not
inference evidence, no fake success result is written, and the fixture is
removed in finally. The separate runner test still proves a genuine claim
and records the real no-model failure. No production scheduler hook added.
