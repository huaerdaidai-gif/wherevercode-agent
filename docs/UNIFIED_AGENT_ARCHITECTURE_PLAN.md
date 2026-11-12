# Unified Agent Architecture and Acceptance Plan

This plan applies to the independent `wherevercode-agent` application. The
older `wherevercode` repository is read-only reference material; its server,
database, production APIs, and credentials are not runtime dependencies.

## Product contract

The Android app owns the local task ledger, scheduling decisions, approvals,
permissions, event history, and recovery state. A user may start work from the
app or, when an optional authenticated public MCP endpoint is enabled, from an
external MCP client. Both entry points must call the same task and control
interfaces and use the same task IDs. Direct device links are preferred for
local work. Public gateways and always-on coordination nodes are optional and
disabled by default.

An Agent is selected by its actual adapter and capabilities, not by a fixed
vendor role. Any connected Agent may develop, review, coordinate, integrate,
or rework when its capabilities and grants permit it. A Hand device is a tool
executor; connecting a Hand must not start another Agent there.

## Shared task and event contract

Every task has one durable `task_id`; Agent runs and Hand calls may have
attempt IDs, but those IDs map back to the task and do not replace it. The task
ID is correlation only. Each operation independently checks caller identity,
task state, selected device, approved pairing, authorized folder, current
attempt, credential lifetime, control epoch, and goal version.

Task creation, assignment, progress, delivery, review, approval, rework,
integration, and terminal results are recorded in the app-owned ledger. Event
consumption is idempotent and replay-safe. A disconnected worker can query
missed work after reconnecting; a stopped task or revoked grant cannot be
restarted by a delayed assignment event.

Internal Agent event subscriptions mean an authorized, capable, online runtime
may claim work from the app-owned queue. They do not imply that ChatGPT or
another cloud client will be woken in the background. External-client
reachability, connector-trigger support, polling support, and pending-work
recovery are separate capabilities and must be reported separately.

## Existing implementation and reference material

The new app already has durable Room task and task-event stores, a canonical
task state machine, `ControlPlane` operation deduplication and fencing,
session delivery state, remote Hand credentials, and MCP client discovery and
calls. Those are the foundation; do not add a parallel task database or event
system.

The read-only older project provides reference patterns for provider-neutral
Agent events, task assignment, delivery retry, signed webhook notification,
and cross-device task execution. Port only reviewed logic and contracts. Do
not import its platform APIs, database, server, channel backend, or runtime.

The Muse channel UI and MCP client/tool-source work may be reused. The current
channel transport still depends on a configured platform connector, so it is
not an independent channel service. The Grok Bot security commits are not
available from the confirmed GitHub refs and are not considered integrated.

## Delivery order

### P0 — CI regression

Keep the Muse API compatibility fixes and the task-persistence regression
assertion covered. Require successful Hand/MCP checks, the full Android unit
suite, debug and compatibility APK builds, checksum verification, test report
artifact, and APK artifacts in GitHub Actions.

### P1 — App-originated single-Agent / Hand loop

The user selects an Agent brain, a distinct Hand device, and an authorized
folder in the app. The app persists that binding with the task, dispatches the
task to the selected Agent adapter, injects the task-scoped generic Hand MCP
tools through that adapter, and receives tool events and results into the same
task timeline. Pause, resume, stop, and goal changes must cross the same
execution path and respect task fences. App restart must restore the task,
binding, event history, and result. Real acceptance must begin in the app and
must not be replaced by CLI or mock calls.

### P2 — Shared multi-Agent scheduler and events

Use the existing scheduler and state machine for parallel work, dependencies,
capability-based assignment, human approval, review tasks, rework, and
integration. Persist assignment and review events, deduplicate claims and
delivery retries, support cursor-based recovery, and make stop/revocation
authoritative over delayed events.

### P3 — Independent channels and bidirectional MCP

The app's MCP client continues to discover tools dynamically across configured
servers. The app-owned MCP server exposes authorized Agent/device discovery,
task creation and assignment, status/events/results, control, review/rework,
channels, and delivery artifacts through the same ledger and policy checks.
Replace platform-backed channel storage/transport with app-owned local
persistence and direct device or optional coordinator synchronization. Public
HTTPS access is an optional authenticated forwarding layer, not a scheduler.

### P4 — Offline-first and optional remote access

Verify core task and device operation with old platform endpoints and public
gateway disabled, including disconnect/reconnect, deduplication, and state
recovery. Separately verify an explicitly enabled HTTPS gateway and accurately
document which operations require an online coordinator. Do not claim
round-the-clock reachability without a reachable always-on node.

## Evidence gates

Each stage records commit SHA, focused and CI test results, APK artifacts when
applicable, and any unpassed scenario. P1 real-device evidence must correlate
the same task ID in app, Agent, MCP, and Hand records. P2-P4 evidence must
distinguish automated tests from actual app/device or external-client runs.
The required end states are: app single-Agent flow; app team flow with review
and rework; ChatGPT-originated shared task and control; bidirectional task
visibility; disconnect recovery; independent operation without old V5 or a
required VPS; and separately verified optional public access.
