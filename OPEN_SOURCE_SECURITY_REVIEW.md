# Open-source remediation review (2026-11-12)

## Scope and result

Reviewed the 673-file source baseline at
`cdd0c1c9e5d2228ca022f61174eb11c61512ea78`. The remediation branch is
`chore/open-source-remediation-20261112`. The repository history still contains
the removed signing key and production configuration; only a new clean snapshot
may be published. The GitHub repository remains Private.

## Findings addressed

- Removed production platform IP/Tailnet configuration, platform certificate
  fingerprint, and the matching production PEM fixture. Public builds default
  platform host/channel/pin values to empty. Maintainers can provide local
  Gradle properties or environment values, documented in
  `OPEN_SOURCE_CONFIGURATION.md`.
- HTTPS remains enforced through the platform's normal system trust and
  hostname verification. A valid configured pin adds a host-scoped path. No
  all-trusting trust manager is installed.
- Removed the private Google Drive APK distribution file ID. Update checks are
  disabled in public builds until a maintainer configures a file ID locally;
  blank configuration now fails closed with a clear not-configured error.
- Removed the repository's Android GitHub Actions workflow. The reviewed tree
  contains zero workflow files; publishing this snapshot will not trigger that
  build.
- Removed user-specific identity/path examples and replaced them with neutral
  placeholders. Removed contiguous private-key marker text from the redactor
  unit test.
- Gitleaks 8.30.1 final working-tree scan: **0 findings**. A second scan was
  run after the final source and notice edits. The report is redacted and stored
  outside the source tree.

## Manual candidate review

The prior scan reported 1 private-key-marker candidate, 24
credential-shaped candidates, and 75 high-entropy-string candidates.

- The private-key marker was a redaction test fixture, not key material; the
  test now constructs the marker from fragments.
- The 24 credential-shaped candidates were synthetic test inputs: fake API-key
  references, websocket/session signing test values, and a fixture OAuth
  client secret. They were replaced by clearly synthetic values. No live API
  key, token, password, or private key was found.
- The 75 high-entropy candidates were individually reviewed. They covered
  fixed protocol hashes/checksums, published RFC cryptographic test vectors,
  synthetic test IDs, public OAuth client identifiers, public X.509 certificate
  bytes, and one private Drive update-file identifier. That identifier was not
  an access credential, but did identify a production resource; it was removed
  and made locally configurable. The real platform certificate and endpoint
  data were removed. No private key or usable credential remains in the
  intended tree.

As a broader cross-check, a redacted scanner with a lower entropy threshold
found 124 baseline candidates. The 40 workflow entries were branch names and a
public Gradle distribution checksum; that workflow is deleted. The certificate
PEM line candidates were public certificate bytes, and the production fixture
was deleted. The remaining candidates were test fixtures, protocol hashes,
file/path fragments, public identifiers, and version/checksum values. Candidate
values are excluded from this review record.

## Dependency and license review

Gradle 8.2 with Temurin 17 successfully resolved `releaseRuntimeClasspath`,
`debugUnitTestRuntimeClasspath`, and the root `buildEnvironment`. No APK,
compile, or test task was run. Artifact POM license labels were reviewed; six
artifacts without labels inherited their license from parent POMs (Apache-2.0
for Google artifacts and BSD for Hamcrest). The direct and transitive
non-Apache license families and applicable notice files are listed in
`THIRD_PARTY_NOTICES/README.md`. Termux package archives, the Gradle reports,
and license metadata were kept in the external audit evidence directory and
were not added to the release source tree.

The Termux index confirmed the pinned versions of Node.js 24.22.3, ICU 79.1,
OpenSSL 3.6.5, c-ares 1.34.9, SQLite 3.54.1-1, zlib 1.4, and libc++ 30. All
seven downloaded `.deb` SHA-256 values matched the index. Their required
copyright and license notices are included. Chaquopy's CPython and certifi
notices are also included.

## Release gate

Source review, secret scan, dependency graph resolution, and license notice
review passed for this source snapshot. Before publication, export the reviewed
tree without `.git`, make a parentless initial commit, and rerun the sensitive
scan on that exact export. The original private repository and its history must
remain private; do not push its historical refs to the public snapshot.
