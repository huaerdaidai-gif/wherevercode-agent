#!/usr/bin/env bash
# Captures installation/startup evidence only. Interactive acceptance is separate.
set -euo pipefail
if [[ $# -ne 3 ]]; then
  echo 'Usage: verify-k70-debug.sh ADB_SERIAL DEBUG_APK OUTPUT_DIRECTORY' >&2
  exit 2
fi
serial=$1
apk=$2
output=$3
adb_bin=${ADB_BIN:-adb}
aapt_bin=${AAPT_BIN:-aapt}
mkdir -p "$output"
"$aapt_bin" dump badging "$apk" > "$output/apk-badging.txt"
if ! rg -q "^package: name='wherevercode.agent.ci' " "$output/apk-badging.txt"; then
  echo 'Refusing installation: expected isolated wherevercode.agent.ci debug package.' >&2
  exit 3
fi
"$adb_bin" -s "$serial" get-state > "$output/adb-state.txt"
model=$("$adb_bin" -s "$serial" shell getprop ro.product.model | tr -d '\r')
market=$("$adb_bin" -s "$serial" shell getprop ro.product.marketname | tr -d '\r')
printf 'model=%s\nmarketname=%s\n' "$model" "$market" > "$output/device.txt"
if [[ "$model" != '23113RKC6C' && "$market" != 'Redmi K70' ]]; then
  echo 'Device is not identified as Redmi K70; refusing to label evidence as K70.' >&2
  exit 4
fi
"$adb_bin" -s "$serial" shell getprop ro.build.version.release >> "$output/device.txt"
sha256sum "$apk" > "$output/apk-sha256.txt"
# Signature mismatch fails here; never uninstall or delete existing user data.
"$adb_bin" -s "$serial" install -r "$apk" > "$output/install.txt" 2>&1
"$adb_bin" -s "$serial" shell am start -W -n wherevercode.agent.ci/wherevercode.agent.MainActivity > "$output/start.txt" 2>&1
"$adb_bin" -s "$serial" exec-out screencap -p > "$output/startup.png"
"$adb_bin" -s "$serial" shell dumpsys package wherevercode.agent.ci > "$output/package.txt"
pid=$("$adb_bin" -s "$serial" shell pidof wherevercode.agent.ci | tr -d '\r')
if [[ -n "$pid" ]]; then
  "$adb_bin" -s "$serial" logcat -d --pid="$pid" > "$output/app-logcat-PRIVATE.txt"
fi
printf '%s\n' 'Installation/startup captured. Navigation, task execution, control receipts and approval acceptance remain NOT_RUN.'
printf '%s\n' 'Review/redact private logcat and device data before sharing. Do not commit raw device logs.'
