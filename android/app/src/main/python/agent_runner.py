"""Real CPython runner used by the Android app's run_command tool.

This module is bundled into the APK by Chaquopy (src/main/python) and is
executed in-process when the agent runs ``python3 <script>``. It performs
 REAL execution only: the exit code and the captured stdout/stderr come
from the interpreted script, never from this helper's guesses.

Contract with Kotlin (ChaquopyPythonRuntime):
- ``version()`` -> JSON string with the interpreter version.
- ``run(script, cwd, argv_json, module, timeout_ms)`` -> JSON string with
  keys: ok, exit_code, stdout, stderr, timed_out, cwd, argv, error.

Notes:
- The workspace is made the process cwd for the duration of the run and
  restored afterwards. The Kotlin side serialises calls so two runs never
  observe each other's cwd.
- A wall-clock deadline is enforced with a tracing hook. It reliably stops
  pure-Python loops; a C extension that blocks without returning to the
  interpreter cannot be interrupted (documented, not hidden).
- Output is capped at MAX_OUTPUT_CHARS to protect the phone's memory.
"""

import contextlib
import io
import json
import os
import platform
import runpy
import sys
import time
import traceback

MAX_OUTPUT_CHARS = 1_000_000


class _DeadlineExceeded(BaseException):
    """Raised by the tracing hook; BaseException so user code's
    ``except Exception`` does not swallow the timeout."""


def _truncate(text):
    if len(text) > MAX_OUTPUT_CHARS:
        return text[:MAX_OUTPUT_CHARS] + "\n…（输出超过上限，已截断）"
    return text


def _deadline_tracer(deadline):
    def tracer(frame, event, arg):
        if time.monotonic() > deadline:
            raise _DeadlineExceeded("wall-clock deadline exceeded")
        return tracer

    return tracer


def version():
    return json.dumps(
        {
            "ok": True,
            "version": platform.python_version(),
            "implementation": platform.python_implementation(),
            "executable": sys.executable or "",
            "platform": "%s %s" % (platform.system(), platform.machine()),
        },
        ensure_ascii=False,
    )


def run(script, cwd, argv_json, module, timeout_ms):
    argv = list(json.loads(argv_json)) if argv_json else []
    timeout_s = max(1.0, float(timeout_ms) / 1000.0)

    old_cwd = os.getcwd()
    old_argv = list(sys.argv)
    old_path = list(sys.path)
    old_trace = sys.gettrace()
    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()
    exit_code = 0
    timed_out = False
    error = None
    deadline = time.monotonic() + timeout_s

    try:
        if cwd:
            os.chdir(cwd)
        if module:
            sys.argv = [module] + argv
            sys.path.insert(0, os.getcwd())
            target = ("module", module)
        else:
            script = os.path.abspath(script)
            if not os.path.isfile(script):
                raise FileNotFoundError("script not found: %s" % script)
            sys.argv = [script] + argv
            sys.path.insert(0, os.path.dirname(script) or os.getcwd())
            target = ("script", script)

        sys.settrace(_deadline_tracer(deadline))
        with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
            try:
                if target[0] == "module":
                    runpy.run_module(target[1], run_name="__main__", alter_sys=True)
                else:
                    runpy.run_path(target[1], run_name="__main__")
            except SystemExit as exc:
                if exc.code is None:
                    exit_code = 0
                elif isinstance(exc.code, int):
                    # bool is an int subclass (unittest.main exits with
                    # False/True); JSON-encode 0/1 so Kotlin's optInt can
                    # read it instead of falling back to -1.
                    exit_code = int(exc.code)
                else:
                    sys.stderr.write("%s\n" % exc.code)
                    exit_code = 1
            except _DeadlineExceeded:
                timed_out = True
                exit_code = 124
                sys.stderr.write(
                    "执行超过 %.1f 秒，已中止（纯 Python 循环可中断；"
                    "阻塞在 C 扩展里的调用可能无法中断）\n" % timeout_s
                )
            except BaseException:
                exit_code = 1
                traceback.print_exc()
    except _DeadlineExceeded:
        timed_out = True
        exit_code = 124
    except BaseException as exc:  # noqa: BLE001 - the helper must report, not crash
        error = "%s: %s" % (type(exc).__name__, exc)
        exit_code = 1
        stderr_buf.write("%s\n" % error)
    finally:
        sys.settrace(old_trace)
        try:
            os.chdir(old_cwd)
        except Exception:
            pass
        sys.argv = old_argv
        sys.path = old_path

    return json.dumps(
        {
            "ok": error is None,
            "exit_code": exit_code,
            "stdout": _truncate(stdout_buf.getvalue()),
            "stderr": _truncate(stderr_buf.getvalue()),
            "timed_out": timed_out,
            "cwd": cwd,
            "argv": argv,
            "error": error,
        },
        ensure_ascii=False,
    )
