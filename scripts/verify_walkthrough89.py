#!/usr/bin/env python3
"""Summarize a Walkthrough89 results file (Android or iOS export).

Input lines (one per registry entry):
    <id>|<registry-status>|PASS|TODO_OK|FAIL|<note>
plus a final WALKTHROUGH89_DONE summary line.

Prints the pass rate over IMPLEMENTED entries, the TODO honesty rate,
and the full failure list. Exit code 0 when the DONE line is present
(the walkthrough completed); the per-entry failures are reported as
data for the failure list, not as a CI failure.
"""
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: verify_walkthrough89.py <results.txt>")
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"FAIL: results file not found: {path}")
        return 1
    lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    done = [line for line in lines if line.startswith("WALKTHROUGH89_DONE")]
    entries = []
    failures = []
    for line in lines:
        parts = line.split("|")
        if len(parts) >= 4 and parts[2] in ("PASS", "TODO_OK", "FAIL"):
            entries.append(parts)
            if parts[2] == "FAIL":
                failures.append(line)
    implemented = [e for e in entries if e[1] == "IMPLEMENTED"]
    implemented_pass = [e for e in implemented if e[2] == "PASS"]
    todo = [e for e in entries if e[1] == "TODO"]
    todo_ok = [e for e in todo if e[2] == "TODO_OK"]
    print(f"entries recorded: {len(entries)}")
    if implemented:
        print(f"IMPLEMENTED pass: {len(implemented_pass)}/{len(implemented)} "
              f"({100.0 * len(implemented_pass) / len(implemented):.1f}%)")
    if todo:
        print(f"TODO honest-hint: {len(todo_ok)}/{len(todo)} "
              f"({100.0 * len(todo_ok) / len(todo):.1f}%)")
    print(f"failures: {len(failures)}")
    for failure in failures:
        print(f"  FAIL {failure}")
    if not done:
        print("FAIL: no WALKTHROUGH89_DONE line — walkthrough did not complete")
        return 1
    print(done[-1])
    return 0


if __name__ == "__main__":
    sys.exit(main())
