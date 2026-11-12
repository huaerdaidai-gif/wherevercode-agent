# Third-party notices

The project source is licensed under Apache-2.0 (see the repository `LICENSE`).

## Termux Node runtime

`android/tools/provision-node.sh` downloads these `aarch64` Termux packages and
includes their shared libraries in a generated APK. The binaries are not
committed in this source tree. Package versions were checked against the live
Termux package index; the downloaded archives' SHA-256 digests matched the
index.

| Package | Version pinned by script | License / notice |
|---|---:|---|
| Node.js LTS | 24.22.3 | MIT and bundled third-party notices: `termux/nodejs-lts_24.22.3-copyright.txt` |
| ICU | 79.1 | Unicode License v3: `termux/libicu-79.1-LICENSE.txt` |
| OpenSSL | 3.6.5 | Apache-2.0: `termux/Apache-2.0.txt`; package notice: `termux/openssl_3.6.5-copyright.txt` |
| c-ares | 1.34.9 | MIT: `termux/c-ares_1.34.9-copyright.txt` |
| SQLite | 3.54.1-1 | Public domain / CC0 dedication notice: `termux/Public-Domain.txt` |
| zlib | 1.4 | zlib license: `termux/zlib_1.4-copyright.txt` |
| libc++ | 30 | University of Illinois/NCSA Open Source License: `termux/NCSA.txt` |

The Termux package copyright entries for libc++ and SQLite are symlinks to the
Termux license collection; the target texts are included above. The ICU package
copyright entry in the inspected archive was a 404 placeholder, so its license
text is included from the matching upstream ICU 79.1 release tag.

## Android and Gradle dependencies

Dependency graphs were resolved using Gradle 8.2 and Temurin JDK 17 for
`releaseRuntimeClasspath`, `debugUnitTestRuntimeClasspath`, and the Gradle
`buildEnvironment`. No compile, test, or APK task was run. Artifact POM license
metadata was reviewed; where an artifact omitted a license label, its parent
POM was checked. Resolved Android/Gradle artifacts are not vendored in this
source tree.

License families and included applicable notices:

- AndroidX, Kotlin, kotlinx.coroutines, Google libraries, Android Gradle Plugin,
  Kotlin Gradle Plugin, and Apache Commons Compress: Apache-2.0 (full text in
  the repository `LICENSE`).
- Chaquopy Gradle plugin 15.0.1: MIT (`android/Chaquopy-MIT.txt`). Its bundled
  CPython 3.11.6 and CA bundle notices are included as
  `android/CPython-3.11.6-PSF-License.txt` and
  `android/certifi-2023.11.17-ca-bundle-notice.txt`; the MPL-2.0 text is
  `android/MPL-2.0.txt`. The build plugin dependency graph was included in the
  `buildEnvironment` review.
- JSch 0.2.21 and its bundled JZlib/jBCrypt code: revised BSD, BSD, and ISC
  (`android/jsch-*.txt`).
- JUnit 4.13.2: EPL-1.0 (`android/JUnit-EPL-1.0.txt`).
- Robolectric 4.11.1 and Checker Framework annotations: MIT
  (`android/Robolectric-MIT.txt`).
- Bouncy Castle 1.76: Bouncy Castle license
  (`android/Bouncy-Castle-License.html`).
- ASM 9.6: BSD-3-Clause (`android/BSD-3-Clause.txt`).
- `javax.annotation-api` 1.3.2: CDDL-1.0 / GPL-2.0 with classpath exception
  (`android/javax.annotation-api-CDDL-GPL-1.3.2.txt`).
- ICU4J 73.2: Unicode/ICU license (the Unicode License v3 text is included as
  `termux/libicu-79.1-LICENSE.txt`).
- `org.json` 20240303: public domain.
- Guava 31.1-jre, AutoValue annotations 1.10.4, and listenablefuture 1.0 had no
  license label on their artifact POM; their parent POMs specify Apache-2.0.
  Hamcrest 1.3's parent POM specifies BSD.

Direct application dependency coordinates are declared in
`android/app/build.gradle.kts`; plugin coordinates are in
`android/build.gradle.kts` and `android/settings.gradle.kts`. The dependency
reports and downloaded package artifacts used for this review are kept outside
the repository in the audit evidence directory.
