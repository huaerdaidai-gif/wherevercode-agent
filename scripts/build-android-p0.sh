#!/usr/bin/env bash
# JDK 17, Android SDK 34, Gradle 8.2.1; downloads go through caller's network config.
set -uo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
artifact_dir="${P0_ARTIFACT_DIR:-$repo_dir/artifacts/issue-6}"
gradle_bin="${GRADLE_BIN:-gradle}"
mkdir -p "$artifact_dir"
cd "$repo_dir"
git rev-parse HEAD > "$artifact_dir/source-commit.txt"
git status --porcelain > "$artifact_dir/source-status.txt"
cd android
"$gradle_bin" --no-daemon --max-workers=2 --continue :app:assembleDebug :app:testDebugUnitTest "$@" 2>&1 | tee "$artifact_dir/android-build.log"
build_status=${PIPESTATUS[0]}
if [[ -f app/build/outputs/apk/debug/app-debug.apk ]]; then
    cp app/build/outputs/apk/debug/app-debug.apk "$artifact_dir/wherevercode-agent-p0-debug.apk"
    sha256sum "$artifact_dir/wherevercode-agent-p0-debug.apk" > "$artifact_dir/apk-sha256.txt"
fi
if [[ -d app/build/reports/tests/testDebugUnitTest ]]; then
    cp -R app/build/reports/tests/testDebugUnitTest "$artifact_dir/test-report"
fi
if [[ -d app/build/test-results/testDebugUnitTest ]]; then
    cp -R app/build/test-results/testDebugUnitTest "$artifact_dir/test-results"
fi
printf '%s\n' "$build_status" > "$artifact_dir/build-exit-code.txt"
exit "$build_status"
