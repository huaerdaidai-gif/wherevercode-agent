"""Step-0 probe run inside the app by Chaquopy (CPython in-process).

Standard library only: this file is an Android asset, not a server module,
and must stay importable without any WhereverCode server code.
"""

import platform
import subprocess
import sys


def probe(workspace):
    lines = []
    lines.append("python=%s" % sys.version.split()[0])
    lines.append("platform=%s %s" % (platform.system(), platform.machine()))
    lines.append("executable=%s" % sys.executable)
    # One shell command, through the system shell.
    try:
        out = subprocess.run(
            ["/system/bin/sh", "-c", "echo hello-from-python-shell"],
            capture_output=True, text=True, timeout=30,
        )
        lines.append("shell exit=%s out=%s" % (out.returncode, out.stdout.strip()))
    except Exception as exc:  # noqa: BLE001 - probe must report, not crash
        lines.append("shell FAILED: %s" % exc)
    # Write a file in the sandbox workspace and read it back.
    try:
        target = workspace.rstrip("/") + "/spike_probe_py.txt"
        with open(target, "w", encoding="utf-8") as fh:
            fh.write("wherevercode-android-spike-py")
        with open(target, encoding="utf-8") as fh:
            back = fh.read()
        lines.append("file roundtrip=%s" % ("OK" if back == "wherevercode-android-spike-py" else "MISMATCH:" + back))
    except Exception as exc:  # noqa: BLE001 - probe must report, not crash
        lines.append("file FAILED: %s" % exc)
    return "\n".join(lines)
