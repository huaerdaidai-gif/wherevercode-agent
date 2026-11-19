#!/usr/bin/env bash
# zero-diff-gate.sh — Brain-core zero-diff gate for WhereverCode Agent.
#
# Implements the CI gate required by the dual-end alignment final plan
# (差异 9, section E "零改动 CI 门禁脚本" and the acceptance checklist):
#
#   Check A (zero diff): between the candidate ref and its merge-base with
#     the base ref, there must be NO change (add / modify / delete / rename)
#     in any of:
#       - android/app/src/main/java/wherevercode/agent/brain/**  (brain/**)
#       - android/app/src/test/java/wherevercode/agent/brain/**
#       - ios/WhereverCodeAgent/Brain/**                          (brain/**)
#       - any file named BrainConfig.kt / BrainConfig.swift,
#         BrainLoginRows.kt / BrainLoginRows.swift,
#         BrainBootstrap.kt / BrainBootstrap.swift, wherever it lives
#         (BrainLoginRows.kt sits in ui/, outside brain/, so the named-file
#         rule is what protects it).
#
#   Check B (no models imports): no file inside the brain/** directories at
#     the candidate ref may import the independent models package:
#       - Kotlin/Java:  import wherevercode.agent.models...
#       - Swift:        import <module whose name contains "Model">
#     (The Android models package wherevercode.agent.models is created by
#     the 差异 9 work; brain code must never depend on it.)
#
#   Check C (brain store bytes equal before/after migration) is NOT
#   implemented here on purpose: the brain store lives on a device, not in
#   git, so no repository script can verify it. It is a device-side step —
#   see README.md next to this script. Do not add a fake version of it here.
#
# Usage:
#   bash tools/zero-diff-gate/zero-diff-gate.sh <candidate-ref> [base-ref]
#     <candidate-ref>  branch name, tag or commit SHA to gate (required)
#     [base-ref]       ref the candidate is compared against (default: main).
#                      The comparison is candidate vs merge-base(base, candidate),
#                      so commits already on the base never count as violations.
#
# Exit codes: 0 = gate passes, 1 = violation found, 2 = usage / environment error.
#
# Run it from anywhere inside the repository working tree.

set -u

CAND="${1:-}"
BASE_REF="${2:-main}"

if [ -z "$CAND" ]; then
    echo "usage: zero-diff-gate.sh <candidate-ref> [base-ref]" >&2
    exit 2
fi

if ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "ERROR: not inside a git working tree." >&2
    exit 2
fi

if ! git rev-parse --verify --quiet "$CAND^{commit}" >/dev/null; then
    echo "ERROR: candidate ref not found: $CAND" >&2
    exit 2
fi

if ! git rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null; then
    echo "ERROR: base ref not found: $BASE_REF" >&2
    exit 2
fi

MERGE_BASE="$(git merge-base "$BASE_REF" "$CAND")"
if [ -z "$MERGE_BASE" ]; then
    echo "ERROR: no merge-base between $BASE_REF and $CAND." >&2
    exit 2
fi

PROTECTED_DIRS=(
    "android/app/src/main/java/wherevercode/agent/brain"
    "android/app/src/test/java/wherevercode/agent/brain"
    "ios/WhereverCodeAgent/Brain"
)

PROTECTED_NAMES=(
    "BrainConfig.kt" "BrainConfig.swift"
    "BrainLoginRows.kt" "BrainLoginRows.swift"
    "BrainBootstrap.kt" "BrainBootstrap.swift"
)

PATHSPECS=()
for d in "${PROTECTED_DIRS[@]}"; do
    PATHSPECS+=("$d")
done
for n in "${PROTECTED_NAMES[@]}"; do
    PATHSPECS+=(":(glob)**/$n")
done

FAIL=0

echo "== zero-diff gate =="
echo "candidate : $CAND ($(git rev-parse --short "$CAND"))"
echo "base      : $BASE_REF ($(git rev-parse --short "$BASE_REF"))"
echo "merge-base: $(git rev-parse --short "$MERGE_BASE")"
echo

# ---------------------------------------------------------------- check A
echo "-- check A: zero diff in brain/** + BrainConfig + BrainLoginRows + BrainBootstrap"
DIFF_OUT="$(git diff --name-status "$MERGE_BASE" "$CAND" -- "${PATHSPECS[@]}")"
if [ -n "$DIFF_OUT" ]; then
    echo "FAIL: protected brain-core paths changed between merge-base and candidate:"
    echo "$DIFF_OUT" | sed 's/^/  /'
    FAIL=1
else
    echo "PASS: no changes in protected paths."
fi
echo

# ---------------------------------------------------------------- check B
echo "-- check B: no imports of the models package inside brain/**"
KOTLIN_IMPORT_RE='^[[:space:]]*import[[:space:]]+wherevercode\.agent\.models([[:space:].;]|$)'
SWIFT_IMPORT_RE='^[[:space:]]*import[[:space:]]+[A-Za-z_][A-Za-z0-9_.]*[Mm]odel[A-Za-z0-9_.]*([[:space:]]|$)'

IMPORT_HITS=""
while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
        *.kt|*.java) RE="$KOTLIN_IMPORT_RE" ;;
        *.swift)     RE="$SWIFT_IMPORT_RE" ;;
        *)           continue ;;
    esac
    HITS="$(git show "$CAND:$f" 2>/dev/null | grep -nE "$RE" || true)"
    if [ -n "$HITS" ]; then
        while IFS= read -r line; do
            IMPORT_HITS="${IMPORT_HITS}  $f:$line
"
        done <<EOF
$HITS
EOF
    fi
done <<EOF
$(git ls-tree -r --name-only "$CAND" -- "${PROTECTED_DIRS[@]}")
EOF

if [ -n "$IMPORT_HITS" ]; then
    echo "FAIL: models-package imports found inside brain/**:"
    printf '%s' "$IMPORT_HITS"
    FAIL=1
else
    echo "PASS: no models-package imports inside brain/**."
fi
echo

# ---------------------------------------------------------------- verdict
if [ "$FAIL" -ne 0 ]; then
    echo "GATE RESULT: FAIL"
    exit 1
fi
echo "GATE RESULT: PASS"
exit 0
