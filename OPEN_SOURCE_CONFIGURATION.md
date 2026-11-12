# Open-source build configuration

Public builds intentionally contain no production platform address, channel ID,
TLS certificate fingerprint, Tailnet address, update-file identifier, or signing
credential.

The app's platform endpoint is configured by the user in the Platform Agent
form, or by a maintainer for a private distribution using untracked local
Gradle properties. The optional private pin is enabled only when both its host
and a 64-character SHA-256 certificate fingerprint are supplied. Without a
valid pin, HTTPS uses Android's normal certificate-chain and hostname checks.
A mismatched pin falls back to the system trust manager; no all-trusting
certificate manager is used.

For a private build, place only the values needed by that build in
`~/.gradle/gradle.properties` (or provide the equivalent environment
variables). Do not commit those values:

| Gradle property | Environment variable | Purpose |
| --- | --- | --- |
| `platformHost` | `WHEREVERCODE_PLATFORM_HOST` | Optional default platform hostname |
| `platformDefaultChannel` | `WHEREVERCODE_PLATFORM_DEFAULT_CHANNEL` | Optional default channel ID |
| `platformV5Channel` | `WHEREVERCODE_PLATFORM_V5_CHANNEL` | Optional team channel ID |
| `pinnedPlatformHost` | `WHEREVERCODE_PINNED_PLATFORM_HOST` | Optional hostname for certificate pinning |
| `pinnedPlatformSha256` | `WHEREVERCODE_PINNED_PLATFORM_SHA256` | Optional leaf-certificate SHA-256 pin, hexadecimal |
| `updateDriveFileId` | `WHEREVERCODE_UPDATE_DRIVE_FILE_ID` | Optional maintainer update APK file ID; empty disables update checks |

These build values are compiled into the app and are not secrets. Never put
API tokens, passwords, private keys, or signing credentials in these fields.
Public builds leave them empty. The app then requires a platform hostname and
channel ID to be entered where the corresponding feature is used.

The public source snapshot omits GitHub Actions workflows. In particular, it
does not include the previous Android workflow, which ran on pushes to `main`.
This keeps importing the source repository from starting a build. Maintainers
can add a reviewed workflow later in a separate change.
