#!/usr/bin/env python3
"""Verify V5RuntimeInstrumentation emulator captures and fail on fake-green.

Checks (mirrors the instrumentation's own contract):
1. v5-runtime/ exists with all 7 expected page PNGs.
2. Every PNG is non-empty and decodable (IHDR) with sane size.
3. No two pages are byte-identical (the "many screenshots, one page"
   failure mode).

Usage: verify_v5_runtime.py <evidence-dir>
"""
import hashlib
import struct
import sys
from pathlib import Path

EXPECTED = [
    "01-drawer.png",
    "02-chat.png",
    "03-projects.png",
    "04-channels.png",
    "05-channel-files-unconfigured.png",
    "06-control.png",
    "07-account-settings.png",
]


def png_dimensions(data: bytes):
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    if data[12:16] != b"IHDR":
        return None
    width, height = struct.unpack(">II", data[16:24])
    return width, height


def main() -> int:
    evidence_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("emulator-evidence")
    capture_dir = evidence_dir / "v5-runtime"
    failures = []
    if not capture_dir.is_dir():
        print(f"FAIL: v5-runtime evidence directory not found under {evidence_dir}")
        return 1
    seen = {}
    for name in EXPECTED:
        png = capture_dir / name
        if not png.is_file() or png.stat().st_size == 0:
            failures.append(f"missing or empty screenshot: {png}")
            continue
        data = png.read_bytes()
        dims = png_dimensions(data)
        if dims is None or dims[0] <= 0 or dims[1] <= 0:
            failures.append(f"not a valid PNG: {png}")
            continue
        sha = hashlib.sha256(data).hexdigest()
        if sha in seen:
            failures.append(f"duplicate screenshot: {name} identical to {seen[sha]}")
        seen[sha] = name
    if failures:
        print("FAIL: v5-runtime evidence failed verification")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("OK: v5-runtime evidence verified:")
    for name in EXPECTED:
        png = capture_dir / name
        print(f"  {name}: {png.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
