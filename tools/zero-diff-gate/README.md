# Brain-core zero-diff gate

`zero-diff-gate.sh` is the CI gate required by the dual-end alignment final
plan （差异 9, section E「零改动 CI 门禁脚本」; acceptance checklist item
「CI 零改动门禁」). It proves that a candidate change does not touch the
brain core, so the independent model store （差异 9) can land without
changing brain behaviour.

## Usage

```sh
bash tools/zero-diff-gate/zero-diff-gate.sh <candidate-ref> [base-ref]
```

- `<candidate-ref>` — branch name, tag or commit SHA to gate (required).
- `[base-ref]` — what the candidate is compared against (default `main`).
  The comparison is made against `merge-base(base, candidate)`, so commits
  that are already on the base never count as violations.

Exit codes: `0` = PASS, `1` = violation found, `2` = usage / environment
error. The script is read-only and runs anywhere inside the working tree,
locally or in CI, e.g.:

```sh
bash tools/zero-diff-gate/zero-diff-gate.sh "$PR_HEAD_SHA" main
```

(The file is stored with mode 100644, so invoke it through `bash`.)

## What it checks

**Check A — zero diff.** Between the merge-base and the candidate there
must be no added, modified, deleted or renamed file in:

- `android/app/src/main/java/wherevercode/agent/brain/**`
- `android/app/src/test/java/wherevercode/agent/brain/**`
- `ios/WhereverCodeAgent/Brain/**`
- any file named `BrainConfig.kt` / `BrainConfig.swift`,
  `BrainLoginRows.kt` / `BrainLoginRows.swift`, or
  `BrainBootstrap.kt` / `BrainBootstrap.swift`, wherever it lives.
  The named-file rule matters: `BrainLoginRows.kt` sits in
  `android/app/src/main/java/wherevercode/agent/ui/`, outside `brain/`,
  and is protected only by this rule.

**Check B — no models imports in brain.** No file inside the `brain/**`
directories at the candidate ref may import the independent models package
created by the 差异 9 work:

- Kotlin/Java: `import wherevercode.agent.models...`
- Swift: `import <module whose name contains "Model">`

## What it deliberately does NOT check

**Check C — brain store bytes equal before/after migration** is the third
item in the plan's gate, and it is **not** implemented in this script,
because it cannot be: the brain store lives on a device, not in git
(Android: the `wherevercode_brain_configs` SharedPreferences,
`configs_json` value; iOS: the `brain.configs_json` entry in the app's
key-value store). No repository script can read it, and a script that
pretended to check it would be a fake gate.

Check C is a **device-side manual step** in the B3/B5 acceptance flow:

1. On a device running the pre-migration build, dump the raw brain store
   value (`configs_json` / `brain.configs_json`) and record its SHA-256.
2. Install the post-migration build, let the one-time migration run, and
   dump the same value again.
3. The two hashes must be identical: the migration copies model entries
   into the model store and must not move, rewrite or delete anything in
   the brain store. Record both hashes in the acceptance notes.

## Self-test

`self-test-20261119.txt` records the script being run against:

1. `main` itself — PASS (exit 0);
2. a scratch ref that edits `BrainConfig.kt` — FAIL, check A (exit 1);
3. the same ref plus a `wherevercode.agent.models` import added to
   `BrainEngine.kt` — FAIL, checks A + B (exit 1);
4. a scratch ref that edits only `ui/BrainLoginRows.kt` (outside `brain/`)
   — FAIL, check A (exit 1);
5. this branch (`tools/zero-diff-gate-20261119`) — PASS (exit 0).

The scratch refs were local-only and were deleted after the test; they were
never pushed.
