#!/usr/bin/env bash
set -euo pipefail
mkdir -p v5-runtime-evidence
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w wherevercode.agent.ci.test/wherevercode.agent.V5RuntimeInstrumentation > v5-runtime-evidence/instrumentation.txt
adb logcat -d > v5-runtime-evidence/logcat.txt
adb pull /sdcard/Android/data/wherevercode.agent.ci/files/v5-runtime v5-runtime-evidence/screenshots
rg 'V5_RUNTIME_OK' v5-runtime-evidence/instrumentation.txt
count=$(find v5-runtime-evidence/screenshots -name '*.png' | wc -l)
test "$count" -eq 7
adb shell getprop ro.build.fingerprint > v5-runtime-evidence/device.txt
adb shell getprop ro.product.cpu.abi >> v5-runtime-evidence/device.txt
