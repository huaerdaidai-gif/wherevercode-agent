# Contributing

Thank you for your interest in wherevercode-agent.

## Scope

This repository contains the independent WhereverCode Agent app and its directly related components. The older `wherevercode` platform is a separate project. Do not introduce a runtime dependency on that platform or change it as part of a contribution here.

## Changes

- Open an issue or describe the bug and expected behavior in a pull request.
- Keep changes focused; avoid unrelated refactoring or rewriting UI designs.
- State the exact branch, commit, test commands, and results. If Android device testing was not performed, say so.
- Preserve permission checks, cancellation, write guards, task ownership, and secret handling.
- Never include credentials, signing keys, real user data, or private infrastructure details.
- For Agent execution, distinguish a tool call that was requested from one that actually completed.

## License

This repository is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE). Contributions are submitted under the repository's license unless explicitly agreed otherwise.
