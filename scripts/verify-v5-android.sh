#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python scripts/export-v5-wiring.py
python -m unittest server.test_hand_server -q
gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  -PandroidAbis=arm64-v8a,x86_64 --console=plain
if [[ "${V5_RUN_EMULATOR_TESTS:-0}" == "1" ]]; then
  gradle -p android :app:connectedDebugAndroidTest -PandroidAbis=arm64-v8a,x86_64 \
    -Pandroid.testInstrumentationRunnerArguments.class=wherevercode.agent.ui.FlagshipNavTest --console=plain
fi
