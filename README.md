# WhereverCode Agent

An experimental, Android-first independent AI agent application for working with projects, tools, and tasks.

> **Development status:** Source code is under active development. The presence of a feature in source does not mean it has been verified on a real Android device. No prebuilt APK or fully validated release is promised by this repository.

## Scope

This repository is **wherevercode-agent**, the standalone app. It is not the older `wherevercode` orchestration platform. Core single-agent operation should not require that older platform or its channels.

## Android

See [Android setup and architecture notes](android/README.md). The Android build uses Gradle, Kotlin and Chaquopy; local build instructions are in [android/README.md](android/README.md). This source repository does not include GitHub Actions workflows. An APK build may need network access to download dependencies and provision a Node runtime. The repository currently does not include a Gradle Wrapper; a compatible Gradle installation is required.

Debug APKs built from this source use local debug signing. They may **not** update APKs signed with the historical shared debug key; an uninstall or separate migration strategy may be needed. Do not treat a debug-signed APK as a production release.

## Security

See [SECURITY.md](SECURITY.md) for private vulnerability reporting and credential handling. Do not commit signing keys, tokens or private infrastructure configuration.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Reports should distinguish unit tests, CI build results, and actual-device verification.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

## Release readiness

Before the repository is made public, verify the *entire reachable Git history and all branches/tags* for private keys, tokens and user data; rotate any exposed credentials; verify third-party license notices; and publish a clean release snapshot. Removing a file in the current branch does not remove it from history.
