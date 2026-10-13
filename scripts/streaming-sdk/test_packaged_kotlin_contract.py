import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import build_android_sdk as build


class PackagedKotlinContractTest(unittest.TestCase):
    def run_fixture(self, result, *, fail_compile=False, omit_receipt=False):
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary); jar = out / 'classes.jar'; jar.write_bytes(b'synthetic jar identity')
            commands = []
            def run(command, **options):
                commands.append((command, options))
                if fail_compile: raise subprocess.CalledProcessError(1, command)
                if 'com.google.ai.edge.litertlm.PackagedSealedContentContractKt' in command and not omit_receipt:
                    Path(command[-1]).write_text(json.dumps(result))
            with mock.patch.object(build, 'run', side_effect=run):
                receipt = build.verify_packaged_kotlin_contract(Path('/known/java'), 'compiler', 'runtime', jar, out)
            self.assertEqual(60, commands[0][1]['timeout'])
            self.assertEqual(20, commands[1][1]['timeout'])
            self.assertIn('-Xfriend-paths=' + str(jar), commands[0][0])
            self.assertEqual(13, receipt['checks'])
            self.assertEqual(build.hashlib.sha256(jar.read_bytes()).hexdigest(), receipt['classes_jar_sha256'])
            self.assertFalse((out / 'kotlin-classes').exists())
            return receipt

    def test_complete_receipt_is_bound_to_actual_jar_and_test_source(self):
        result = self.run_fixture({'passed': True, 'checks': 13})
        self.assertEqual(64, len(result['test_source_sha256']))

    def test_compile_error_missing_receipt_or_partial_check_count_fail_closed(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_fixture({'passed': True, 'checks': 13}, fail_compile=True)
        with self.assertRaises(FileNotFoundError):
            self.run_fixture({'passed': True, 'checks': 13}, omit_receipt=True)
        for result in ({'passed': False, 'checks': 13}, {'passed': True, 'checks': 12}, {'passed': True}):
            with self.subTest(result=result), self.assertRaises(ValueError):
                self.run_fixture(result)


if __name__ == '__main__':
    unittest.main()
