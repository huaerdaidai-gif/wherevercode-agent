# Native Android / iOS UI contract

Scope: `huaerdaidai-gif/wherevercode-agent` only. Baseline: PR #9 head
`e14426aa83cfa1589c21f8e7c25f7e8b6e309759`, which contains assembly
`73ee623` and main `a9206e1`. No merge or overwrite of either source branch.

## Navigation and state

- Chat home → one side drawer → existing workspaces. No bottom tab bar.
- Header: 60 logical units; menu, infinity brand, WhereverCode, history search,
  new conversation. Header action targets are at least 44 logical units.
- Android: MainActivity + EntryRegistry + ChatViewModel; iOS: AppState +
  LocalData + BrainConfigStore + BrainEngine. No parallel business registry.
- The seventy-route catalog is an internal index, never a second UI shell.
- Back pops a workspace detail; closing a drawer does not create a new screen.
- New conversation resets the current selection, never deletes old messages.
- During a model turn, changing the conversation is blocked. The iOS model
  belongs to AppState so navigating to another workspace retains the turn.

## Visual tokens

| Token | Light | Dark |
|---|---|---|
| Home background | #FAF8F5 | #0B0E1D |
| Home ink | #15181E | #F2F4FF |
| Secondary ink | #5A6270 | #9AA3C7 |
| Accent | #7B5CFF | #7B5CFF |
| Card | #FFFFFF | #171B31 |
| Border | #E6E1D8 | #2E3560 |

Native platform fonts, scalable text; headline 28, header brand 17, body
14–16, supporting text 12–13 logical units. Cards use 16-unit corners and
12–16-unit padding. Composer uses 20-unit corners and two rows: multiline
input above context/model controls and send. Shared greeting and suggestion
copy; suggestions fill a draft and never send or execute by themselves.

Native system sheets, text selection and accessibility labels are required.
Icons are native outline icons; no HTML/webview implementation.

## Capability and error rules

- Model choices come from saved BrainConfig entries; provider model discovery
  remains in BrainSettingsView / Android model settings. No fixed model specs.
- Choosing a model is explicit: iOS passes allowFailover=false. A credential,
  adapter or network error is displayed, not converted to synthetic chat text.
- Local history, projects, registry and task reads work without platform login.
- Platform channels and platform tasks retain their real MCP adapters.
- iOS has no connected local workspace executor or direct external-Agent/Hand
  chat adapter. Do not advertise tool definitions, write files or manufacture
  control receipts. Disable unavailable actions with the concrete reason.
- Console iOS events are in-process only. Persisted local task events remain
  in LocalTaskStore; never label the process log as a durable audit archive.
- Historical Android K70 evidence does not certify this revision. Capability
  index entries remain implemented-unverified until current evidence exists.

## Acceptance boundaries

Simulator captures must be distinguished from host-rendered screenshots.
An emulator using a generic Android profile is not a Redmi K70 hardware test.
An unsigned IPA needs the user's own signing before physical iPhone install.
CI debug APK uses existing isolated `.ci` application ID and ephemeral debug
signing; it cannot be represented as an in-place update of another signing key.
No schema version, migration, application ID or signing settings are changed
by this UI work. Full physical-device upgrade testing remains required.
