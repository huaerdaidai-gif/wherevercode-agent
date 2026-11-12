# Security Policy

## Reporting a vulnerability

Please do not publish exploitable security issues, credentials, private keys, or tokens in public issues or pull requests.

Report vulnerabilities privately using GitHub's **Report a vulnerability** / private vulnerability reporting feature if enabled for this repository. If that feature is unavailable, contact the repository maintainer privately through a verified channel listed on the maintainer's GitHub profile.

Include affected version or commit, reproduction steps, impact, and suggested mitigation. Do not include real production credentials.

## Scope

The Android app, local Agent runtime, task dispatch, remote execution and device communication interfaces are security-sensitive. Do not treat the presence of a test or stub as proof of production security.

## Secrets

Never commit tokens, keystores, signing credentials, private server configuration, or user data. If any credential was committed in the past, rotate or revoke it before public release; deleting the current file is not sufficient.
