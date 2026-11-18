"""Exercise the actual bundled Chaquopy helper on host CPython, not a mock."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

_path = Path(__file__).resolve().parents[1] / 'android/app/src/main/python/agent_runner.py'
_spec = importlib.util.spec_from_file_location('embedded_python_control', _path)
runner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(runner)


class EmbeddedControlTests(unittest.TestCase):
    def _interrupt(self, state):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / 'work.py'
            script.write_text("from pathlib import Path\nPath('started').write_text('running')\nfor i in range(1000000):\n    value = i * i\nPath('must-not-exist').write_text('unsafe continuation')\n")
            class Probe:
                def isStopped(self):
                    return state == 'stopped' and (root / 'started').exists()
                def isPaused(self):
                    return state == 'paused' and (root / 'started').exists()
            result = json.loads(runner.run(str(script), str(root), '[]', '', 5000, Probe()))
            self.assertTrue((root / 'started').exists(), 'must interrupt actual script execution')
            self.assertEqual(state, result['control_state'])
            self.assertNotEqual(0, result['exit_code'])
            self.assertFalse(result['timed_out'])
            self.assertFalse((root / 'must-not-exist').exists())

    def test_stop_interrupts_running_python_before_later_side_effect(self):
        self._interrupt('stopped')

    def test_pause_interrupts_running_python_before_later_side_effect(self):
        self._interrupt('paused')

    def test_legacy_five_argument_call_remains_compatible(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'legacy.py'
            script.write_text("print('actual output')\n")
            result = json.loads(runner.run(str(script), directory, '[]', '', 1000))
            self.assertEqual(0, result['exit_code'])
            self.assertEqual('actual output\n', result['stdout'])
            self.assertIsNone(result['control_state'])


if __name__ == '__main__':
    unittest.main()
