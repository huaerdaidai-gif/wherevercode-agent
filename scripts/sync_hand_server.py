#!/usr/bin/env python3
"""Synchronize the bundled Hand from server/hand_server.py (the only source).

No arguments or --check is read-only. Use --write explicitly to regenerate.
Paths are relative to this script, never to the caller's working directory.
"""
import argparse
import hashlib
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "server/hand_server.py"
ASSET = ROOT / "android/app/src/main/assets/install/hand_server.py"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="check bytes without changing files (default)")
    mode.add_argument("--write", action="store_true", help="explicitly regenerate the asset")
    args = parser.parse_args(argv)
    try:
        source = SOURCE.read_bytes()
    except OSError as exc:
        print(f"Cannot read source {SOURCE}: {exc}", file=sys.stderr)
        return 1
    print(f"source {SOURCE.relative_to(ROOT)} sha256={digest(source)}")
    if args.write:
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".hand-sync-", dir=ASSET.parent)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(source)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, 0o644)
            os.replace(temporary, ASSET)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    try:
        asset = ASSET.read_bytes()
    except OSError as exc:
        print(f"Cannot read generated asset {ASSET}: {exc}", file=sys.stderr)
        print("Run: python3 scripts/sync_hand_server.py --write", file=sys.stderr)
        return 1
    print(f"asset  {ASSET.relative_to(ROOT)} sha256={digest(asset)}")
    if source != asset:
        print("Hand asset differs. Edit server/hand_server.py, then run:", file=sys.stderr)
        print("python3 scripts/sync_hand_server.py --write", file=sys.stderr)
        return 1
    print("Hand source and generated asset match byte-for-byte.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
