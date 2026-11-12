# Repository instructions for AI coding agents

## Project boundary

**Current project:** `huaerdaidai-gif/wherevercode-agent` — independent WhereverCode Agent app.

**Separate, paused project:** `huaerdaidai-gif/wherevercode` — older platform. Do not read, modify, deploy, or assign tasks to the older repository unless the user explicitly requests it. Similar names and concepts are not authorization to combine projects.

The app must remain independently usable without the older platform's database, server, channels, or task system. Reuse of an idea or code requires explicit review of licensing, security, and dependencies.

## Development discipline

- Verify the current integration branch and exact commit before changing code. Do not assume `main` is the newest Android build.
- Do not force-push, rewrite history, change repository visibility, rotate secrets, or deploy without explicit approval.
- Distinguish source-code presence, tests passing, and real-device verification.
- Preserve user control over pause, stop, retarget, approvals, and authorized writes.
- Do not use chat/channel messages as substitutes for actual task creation and assignment.
- Do not hardcode provider roles or require any particular model vendor.
- Do not commit secrets, tokens, private keys, signing materials, or personal data.

## Open-source release

Before public release, inspect all reachable refs and history for sensitive material, revoke leaked credentials, choose a license, and verify a clean release tree. The presence of this file is not a security sign-off.
