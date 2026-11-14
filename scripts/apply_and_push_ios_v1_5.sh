#!/usr/bin/env bash
set -euo pipefail

# 在已克隆的 huaerdaidai-gif/wherevercode-agent 仓库根目录执行本脚本：
# 仅新增/更新 ios/、contracts/、.github/workflows/ios-v5-70screen-build.yml 与 scripts/，绝不触碰 android/ 或 server/。

BRANCH="feat/v5-ios-70screen-4drawer-assembly"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [ ! -d ".git" ]; then
  echo "Error: 请在 wherevercode-agent Git 仓库根目录下运行此脚本。" >&2
  exit 1
fi

git checkout -B "${BRANCH}"
cp -R "${BUNDLE_ROOT}/ios" ./
cp -R "${BUNDLE_ROOT}/contracts" ./
mkdir -p .github/workflows scripts
cp "${BUNDLE_ROOT}/.github/workflows/ios-v5-70screen-build.yml" .github/workflows/
cp "${BUNDLE_ROOT}/scripts/verify_ios_v5_assembly.py" scripts/
cp "${BUNDLE_ROOT}/scripts/run_e2e_wiring_tests.py" scripts/

python3 scripts/verify_ios_v5_assembly.py

git add ios/ contracts/ .github/workflows/ios-v5-70screen-build.yml scripts/verify_ios_v5_assembly.py scripts/run_e2e_wiring_tests.py
git commit -m "feat(ios): v1.5 build 6 70-screen 4-drawer assembly and wiring"
echo "Local commit created on ${BRANCH}: $(git rev-parse HEAD)"
echo "Run: git push -u origin ${BRANCH}"
