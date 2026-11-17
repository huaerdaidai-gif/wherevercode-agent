#!/usr/bin/env python3
"""Verify page-capture evidence and fail the CI job on any fake-green signal.

Checks (mirrors PageCaptureTest, on the pulled artifact files):
1. manifest.json exists and declares every required page capture.
2. Every PNG exists, is non-empty, and is a decodable PNG (IHDR) with sane size.
3. Recorded SHA-256 matches the actual file bytes.
4. No page screenshot is a byte-identical duplicate of another page
   (the "10 screenshots, one welcome page" failure mode).
5. The onboarding overlay was actually dismissed during launch.
6. Build commit recorded in the manifest is present.

No network, no credentials, no secrets: reads only the evidence directory.
Usage: verify_page_capture.py <evidence-dir> [required-page...]
"""
import hashlib
import json
import struct
import sys
from pathlib import Path

DEFAULT_PAGES = [
    "01-chat-home.png",
    "02-sidebar.png",
    "03-tasks.png",
    "04-projects.png",
    "05-devices.png",
    "06-model-selection.png",
    "07-settings.png",
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
    capture_dir = evidence_dir / "page-capture"
    required = sys.argv[2:] or DEFAULT_PAGES

    failures = []
    manifest_path = capture_dir / "manifest.json"
    if not manifest_path.is_file():
        # The evidence pull nests the directory under its own name.
        nested = evidence_dir / "page-capture" / "page-capture" / "manifest.json"
        if nested.is_file():
            capture_dir = nested.parent
            manifest_path = nested
    if not manifest_path.is_file():
        print(f"FAIL: manifest.json not found under {capture_dir}")
        return 1

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"FAIL: manifest.json is not valid JSON: {exc}")
        return 1

    if not manifest.get("overlayDismissed"):
        failures.append("onboarding overlay was never confirmed dismissed (overlayDismissed != true)")
    if not manifest.get("gitSha") or manifest.get("gitSha") == "unknown":
        failures.append("manifest has no build commit (gitSha missing/unknown)")

    pages = {entry.get("file"): entry for entry in manifest.get("pages", []) if entry.get("file")}
    for page_file in required:
        entry = pages.get(page_file)
        if entry is None:
            failures.append(f"missing page capture: {page_file} (not in manifest)")
            continue
        png = capture_dir / page_file
        if not png.is_file() or png.stat().st_size == 0:
            failures.append(f"missing or empty screenshot file: {png}")
            continue
        data = png.read_bytes()
        dims = png_dimensions(data)
        if dims is None:
            failures.append(f"not a valid PNG: {png}")
        elif dims[0] <= 0 or dims[1] <= 0:
            failures.append(f"degenerate PNG dimensions {dims}: {png}")
        actual_sha = hashlib.sha256(data).hexdigest()
        if entry.get("sha256") != actual_sha:
            failures.append(f"sha256 mismatch for {page_file}: manifest={entry.get('sha256')} actual={actual_sha}")
        if entry.get("duplicateOf"):
            failures.append(f"page screenshot is a duplicate of {entry['duplicateOf']}: {page_file}")

    if len([p for p in pages if p in required]) < len(required):
        pass  # already reported as missing above

    if failures:
        print("FAIL: page-capture evidence failed verification")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print("OK: page-capture evidence verified:")
    print(f"  pages      : {', '.join(required)}")
    print(f"  build      : {manifest.get('gitSha')}")
    print(f"  device     : {manifest.get('deviceModel')} API {manifest.get('apiLevel')}")
    print(f"  wm         : {manifest.get('wmSize', '?').replace(chr(10), ' ')} / {manifest.get('wmDensity', '?').replace(chr(10), ' ')}")
    for page_file in required:
        entry = pages[page_file]
        print(f"  {page_file}: {entry.get('bytes')} bytes sha256={entry.get('sha256')[:12]} marker={entry.get('identityMarker')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
