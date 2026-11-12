# Public release procedure (maintainer only)

**Status: NOT APPROVED FOR PUBLIC RELEASE.** This branch removes the shared Android debug keystore from its current tree, but the existing repository history and other refs may still expose it.

## Release boundaries

- Only `huaerdaidai-gif/wherevercode-agent`; do not operate on `huaerdaidai-gif/wherevercode`.
- No GitHub Actions build or APK is required to release source code.
- Do not change repository visibility until a clean repository has been checked independently.
- Preserve the original private repository and a secure offline backup. **Do not push the old branches or tags to a new public repository.**

## Safe release approach

A fresh, single-commit source snapshot is preferable to rewriting the history in place. Using a *new empty repository* is the easiest way to ensure no old refs are published. If the repository name must stay the same, perform a carefully coordinated private backup, snapshot creation and removal of all old refs before switching visibility. A single clean `main` commit does not make old branches, tags, pull-request refs, forks, caches or archived copies disappear.

1. Freeze changes and record the exact reviewed source commit. Review all files in the intended snapshot, including dotfiles, GitHub workflows, build scripts and documentation.
2. Export the **tree only**, not the `.git` directory. Exclude credentials, keystores, tokens, private configuration, real user data, build artifacts and caches.
3. Search the exported tree for secrets and inspect binary files. Check third-party code, assets and dependency licenses, including required notices and attribution.
4. Rotate/revoke any exposed real secrets. A shared Android **debug** keystore is not necessarily a production secret, but publishing its private key lets anyone sign APKs with that same debug identity; assess existing installs and update compatibility.
5. Initialize an empty Git repository from the reviewed snapshot and create its initial commit. Verify that it has no parents and only the intended files.
6. Publish only that snapshot and intended refs. If reusing the existing repository, delete or quarantine all old reachable branches/tags and verify the GitHub web/API visibility of old commits and refs. Old commits may remain accessible for a time; **do not rely on a branch reset as a security guarantee**. Contact GitHub Support about sensitive-data removal if necessary.
7. Only after verification, enable Public visibility. Recheck as an unauthenticated visitor.
8. Publish an APK only after a separate signing/update strategy and build verification.

## Current release gate (2026-11-12)

The remediation branch removes production endpoint defaults, the production
certificate fixture and the private update-file identifier. Public builds use
empty optional configuration with strict HTTPS validation. The Android Actions
workflow is removed; dependency graphs and required license notices have been
reviewed. See `OPEN_SOURCE_SECURITY_REVIEW.md` and
`THIRD_PARTY_NOTICES/README.md`.

Before publication, export only the reviewed tree, create a parentless initial
commit in a new local repository, and rerun the sensitive-information scan on
that exact snapshot. Preserve the original repository and keep its historical
refs Private. Do not publish until that final snapshot check passes.

## Build compatibility

The cleaned branch uses local debug signing. A newly signed APK will not update an installed APK signed with the historical shared debug certificate. This is a release migration issue, not a reason to keep a private signing key in public source.
