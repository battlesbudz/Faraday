"""Failure cases for the consolidated CI gate; all fixtures are synthetic."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import subprocess
import sys
from check_page_sizes import audit_apk
from test_page_sizes import elf
from verification.receipt import consolidate, junit_results, SCENARIOS
from verification.android import sha256, instrumentation_results, interrupted_results, LIFECYCLE_SCENARIOS, LAYOUT_SCENARIOS
from verification.profiles import artifact_name, load_profiles
import check_recorded_audio as acoustic


class ReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.head = 'a' * 40, 'b' * 40
        self.url = 'https://github.com/owner/repo/actions/runs/1'
        self.needs = {key: {'result': 'success'} for key in
                      ('build-release', 'prepare-upgrade-baseline', 'verify-release', 'verify-native-pages', 'verify-recorded-audio')}
        self.scenarios = json.loads(SCENARIOS.read_text())
        self.junit = self.root / 'jarvis-release-unit-tests' / 'TEST-suite.xml'
        self.junit.parent.mkdir()
        required_jvm = acoustic.contract()['required_jvm_tests']
        self.junit.write_text(f'<testsuite tests="{len(required_jvm)}" failures="0" errors="0" skipped="0">' +
                              ''.join(f'<testcase classname="{entry["classname"]}" name="{entry["name"]}"/>' for entry in required_jvm) + '</testsuite>')
        test = self.root / 'jarvis-verification-test-apk' / 'app-release-androidTest.apk'
        test.parent.mkdir()
        test.write_bytes(b'synthetic test APK')
        self.profiles = load_profiles()
        for profile in self.profiles:
            api, apk = profile['api'], profile['apk']
            binary = self.root / 'jarvis-os-v2-release-apk' / f'{apk}.apk'
            binary.parent.mkdir(exist_ok=True)
            if not binary.exists():
                with zipfile.ZipFile(binary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                    archive.writestr('lib/arm64-v8a/libfixture.so', elf())
                    archive.writestr('classes.dex', f'synthetic {apk}')
            folder = self.root / artifact_name(profile)
            folder.mkdir()
            (folder / 'tests').mkdir()
            raw = ''.join(f"INSTRUMENTATION_STATUS: class={self.scenarios['class']}\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 0\n" for name in self.scenarios['tests']) + f"OK ({len(self.scenarios['tests'])} tests)\n"
            (folder / 'instrumentation.txt').write_text(raw)
            report = {'passed': True, 'source_commit': self.source, 'pr_head': self.head, 'run_url': self.url,
                      'errors': [], 'process_restart': 'passed', 'apk_sha256': sha256(binary), 'test_apk_sha256': sha256(test),
                      'profile': profile, 'device': {'api': str(api), 'page_size': profile['page_size']},
                      'instrumentation': instrumentation_results(raw, self.scenarios['tests'], self.scenarios['class'])}
            (folder / 'report.json').write_text(json.dumps(report))
            for name in ['first-launch', 'final', 'after-process-restart', *self.scenarios['tests']]:
                base = folder / 'tests' if name in self.scenarios['tests'] else folder
                (base / f'{name}.png').write_bytes(b'\x89PNG\r\n\x1a\nfixture')
                (base / f'{name}.xml').write_text(f'<hierarchy><node text="{self.scenarios["restart_model"]}"/></hierarchy>')
        self.folder = self.root / artifact_name(next(p for p in self.profiles if p['id'] == '30-phone-normal'))
        native = self.root / 'jarvis-native-page-sizes' / 'report.json'
        native.parent.mkdir()
        native.write_text(json.dumps({'schema': 1, 'source_commit': self.source, 'page_size': 16384,
                                     'passed': True, 'apks': [audit_apk(self.root / 'jarvis-os-v2-release-apk' / name)
                                                            for name in ('app-release.apk', 'app-compact.apk')]}))
        previous = self.root / 'jarvis-previous-release'
        previous.mkdir()
        previous_apk = previous / 'app-release.apk'
        previous_apk.write_bytes(b'fixture previous published APK')
        self.previous = {'schema': 1, 'apk': 'app-release.apk', 'download_source': 'GitHub numbered release',
                         'repository': 'owner/repo', 'tag': 'audio-pr2-pr6-build.907', 'build': 907,
                         'release_id': 1, 'asset_id': 2, 'size_bytes': previous_apk.stat().st_size,
                         'sha256': sha256(previous_apk), 'published_at': '2026-10-03T04:00:00Z'}
        (previous / 'previous-release.json').write_text(json.dumps(self.previous))
        self.lifecycle = json.loads(LIFECYCLE_SCENARIOS.read_text())
        self.layout = json.loads(LAYOUT_SCENARIOS.read_text())
        for profile in self.profiles:
            folder = self.root / artifact_name(profile)
            report_path = folder / 'report.json'
            report = json.loads(report_path.read_text())
            (folder / 'upgrade').mkdir()
            (folder / 'lifecycle').mkdir()
            (folder / 'layout').mkdir()
            (folder / 'upgrade' / 'previous-release.json').write_text(json.dumps(self.previous))
            for phase, version in (('previous', 907), ('candidate', 908)):
                (folder / 'upgrade' / f'{phase}-package.txt').write_text(f'versionCode={version} minSdk=29')
            report['upgrade'] = {'passed': True, 'previous_apk_sha256': self.previous['sha256'],
                                 'previous_version_code': 907, 'candidate_version_code': 908,
                                 'previous_release': self.previous, 'data_cleared_during_update': False}
            for phase, entry in self.lifecycle['upgrade'].items():
                raw = self.transcript(entry['class'], [entry['test']])
                (folder / 'upgrade' / f'{phase}.txt').write_text(raw)
                report['upgrade'][phase] = instrumentation_results(raw, [entry['test']], entry['class'])
                self.snapshot(folder / 'tests', entry['test'])
            report['lifecycle'] = {'passed': True, 'phases': {},
                                   'notification_permission': 'runtime' if profile['api'] >= 33 else 'platform_not_applicable'}
            for phase, entry in self.lifecycle['phases'].items():
                if 'interrupt' in entry:
                    raw = f"INSTRUMENTATION_STATUS: class={self.lifecycle['class']}\nINSTRUMENTATION_STATUS: test={entry['test']}\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: jarvisBoundary={entry['interrupt']}\nINSTRUMENTATION_STATUS_CODE: 2\n"
                    observed = interrupted_results(raw, entry['test'], self.lifecycle['class'], entry['interrupt'])
                    observed.update({'boundary': entry['interrupt'], 'pid_before': 345, 'pid_after': None})
                    self.snapshot(folder, f"boundary-{entry['interrupt']}")
                else:
                    raw = self.transcript(self.lifecycle['class'], [entry['test']])
                    observed = instrumentation_results(raw, [entry['test']], self.lifecycle['class'])
                (folder / 'lifecycle' / f'{phase}.txt').write_text(raw)
                report['lifecycle']['phases'][phase] = observed
                self.snapshot(folder / 'tests', entry['test'])
            raw = self.transcript(self.layout['class'], self.layout['tests'])
            events = [{'posture': 'fold'}, {'posture': 'unfold'}] if profile['screen_profile'] == 'foldable' else []
            raw += ''.join(f"INSTRUMENTATION_STATUS: jarvisFold={event['posture']}\n" for event in events)
            (folder / 'layout' / 'instrumentation.txt').write_text(raw)
            report['layout'] = {'passed': True, 'instrumentation': instrumentation_results(raw, self.layout['tests'], self.layout['class']),
                                'fold_events': events}
            for name in self.layout['tests']:
                self.snapshot(folder / 'tests', name)
            for suffix in (('folded', 'unfolded') if profile['screen_profile'] == 'foldable' else ('rotated',)):
                self.snapshot(folder / 'tests', f'test03_foldAndUnfoldPreserveActiveCall-{suffix}')
            native = {'page_size': profile['page_size'], 'expected_page_size': profile['page_size'],
                      'shipping_libraries': ['fixture'], 'loaded_libraries': ['fixture'], 'passed': True,
                      'coverage': 'Native library loading only; no speech/model inference'}
            (folder / 'tests' / 'test04_nativeLibrariesLoadAtExpectedPageSize-native.json').write_text(json.dumps(native))
            report['layout']['native_loading'] = native
            report_path.write_text(json.dumps(report))
        sample, pcm = acoustic.fixture()
        self.acoustic_report = {'schema_version': 1, 'source_commit': self.source, 'status': 'passed',
                                'runtime_versions': acoustic.contract()['runtime_versions'], 'checks': []}
        for entry in acoustic.contract()['checks']:
            self.acoustic_report['checks'].append({'name': entry['name'], 'backend': entry['backend'], 'variant': entry['variant'],
                'sample_id': sample['id'], 'status': 'passed', 'recording_sha256': sample['sha256'],
                'input_sha256': acoustic.sha256(acoustic.variant_pcm(pcm, entry['variant'])),
                'model_files': acoustic.model_specs()[entry['backend']], 'text': sample['text'], 'raw_result': 'synthetic native result',
                'elapsed_ms': 1.0, **acoustic.score(sample['text'], sample['text'])})
        audio = self.root / 'jarvis-recorded-audio'
        audio.mkdir()
        (audio / 'report.json').write_text(json.dumps(self.acoustic_report))

    @staticmethod
    def transcript(test_class, names):
        return ''.join(f'INSTRUMENTATION_STATUS: class={test_class}\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 0\n' for name in names) + f'OK ({len(names)} tests)\n'

    @staticmethod
    def snapshot(folder, name):
        (folder / f'{name}.png').write_bytes(b'\x89PNG\r\n\x1a\nfixture')
        (folder / f'{name}.xml').write_text('<hierarchy/>')

    def check(self):
        return consolidate(self.root, self.source, self.head, self.url, self.needs)

    def test_complete_evidence_passes_without_granting_approval(self):
        receipt = self.check()
        self.assertTrue(receipt['passed'], receipt['errors'])
        self.assertEqual(len(self.profiles), len(receipt['variants']))
        self.assertFalse(receipt['release_approved'])

    def test_failed_upstream_rejected_even_with_good_artifacts(self):
        for job in self.needs:
            self.needs[job]['result'] = 'failure'
            with self.subTest(job=job):
                self.assertFalse(self.check()['passed'])
            self.needs[job]['result'] = 'success'

    def test_junit_failures_skips_empty_and_count_lies_rejected(self):
        for xml in ['<testsuite tests="0"/>', '<testsuite tests="2"><testcase classname="S" name="a"/></testsuite>',
                    '<testsuite tests="1"><testcase classname="S" name="a"><failure/></testcase></testsuite>',
                    '<testsuite tests="1"><testcase classname="S" name="a"><skipped/></testcase></testsuite>',
                    '<!DOCTYPE testsuite><testsuite tests="0"/>']:
            self.junit.write_text(xml)
            with self.subTest(xml=xml):
                self.assertFalse(self.check()['passed'])

    def test_apk_tampering_rejected(self):
        (self.root / 'jarvis-os-v2-release-apk' / 'app-release.apk').write_bytes(b'changed')
        self.assertFalse(self.check()['passed'])

    def test_stale_revision_wrong_run_or_api_rejected(self):
        path = self.folder / 'report.json'
        original = json.loads(path.read_text())
        for key, value in [('source_commit', self.head), ('pr_head', self.source), ('run_url', self.url + '9'), ('device', {'api': '35'})]:
            report = copy.deepcopy(original)
            report[key] = value
            path.write_text(json.dumps(report))
            with self.subTest(key=key):
                self.assertFalse(self.check()['passed'])

    def test_missing_evidence_and_failed_raw_test_rejected(self):
        (self.folder / 'final.png').unlink()
        self.assertFalse(self.check()['passed'])
        (self.folder / 'instrumentation.txt').write_text('OK (0 tests)')
        self.assertTrue(any('instrumentation' in e for e in self.check()['errors']))

    def test_restart_xml_must_contain_selected_model(self):
        (self.folder / 'after-process-restart.xml').write_text('<hierarchy/>')
        self.assertFalse(self.check()['passed'])

    def test_push_receipt_accepts_empty_pr_head(self):
        self.head = ''
        for path in self.root.glob('jarvis-verification-*/report.json'):
            report = json.loads(path.read_text())
            report['pr_head'] = ''
            path.write_text(json.dumps(report))
        self.assertTrue(self.check()['passed'])

    def test_missing_profile_never_accepted_as_smaller_matrix(self):
        import shutil
        shutil.rmtree(self.root / artifact_name(self.profiles[-1]))
        self.assertFalse(self.check()['passed'])

    def test_wrong_profile_or_page_size_rejected(self):
        path = self.folder / 'report.json'
        original = json.loads(path.read_text())
        for field in ('profile', 'device'):
            report = copy.deepcopy(original)
            if field == 'profile':
                report['profile']['screen_profile'] = 'foldable'
            else:
                report['device']['page_size'] = 16384
            path.write_text(json.dumps(report))
            with self.subTest(field=field):
                self.assertFalse(self.check()['passed'])

    def test_missing_or_stale_native_report_rejected(self):
        path = self.root / 'jarvis-native-page-sizes' / 'report.json'
        report = json.loads(path.read_text())
        report['source_commit'] = self.head
        path.write_text(json.dumps(report))
        self.assertFalse(self.check()['passed'])
        path.unlink()
        self.assertFalse(self.check()['passed'])

    def test_native_claim_cannot_hide_actual_library_checks(self):
        path = self.root / 'jarvis-native-page-sizes' / 'report.json'
        report = json.loads(path.read_text())
        report['apks'][0]['libraries'][0]['segments'] = []
        path.write_text(json.dumps(report))
        self.assertFalse(self.check()['passed'])

    def test_duplicate_native_variant_rejected(self):
        path = self.root / 'jarvis-native-page-sizes' / 'report.json'
        report = json.loads(path.read_text())
        report['apks'][1] = copy.deepcopy(report['apks'][0])
        path.write_text(json.dumps(report))
        self.assertFalse(self.check()['passed'])

    def test_previous_apk_tampering_or_foreign_release_stream_rejected(self):
        path = self.root / 'jarvis-previous-release' / 'previous-release.json'
        for key, value in [('sha256', '0' * 64), ('tag', 'another-build.907'), ('build', 900)]:
            report = copy.deepcopy(self.previous)
            report[key] = value
            path.write_text(json.dumps(report))
            with self.subTest(key=key):
                self.assertFalse(self.check()['passed'])

    def test_workflow_cli_imports_helpers_without_pythonpath(self):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('verification') / 'receipt.py'),
                                 '--help'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_jvm_cannot_pass_without_the_required_recording_delivery_tests(self):
        self.junit.write_text('<testsuite tests="1"><testcase classname="Other" name="passes"/></testsuite>')
        receipt = self.check()
        self.assertFalse(receipt['passed'])
        self.assertTrue(any('required recorded-audio JVM' in error for error in receipt['errors']))

    def test_upgrade_rejects_cleared_data_wrong_versions_or_different_baseline(self):
        path = self.folder / 'report.json'
        original = json.loads(path.read_text())
        for key, value in [('data_cleared_during_update', True), ('previous_apk_sha256', 'f' * 64),
                           ('previous_version_code', 900), ('candidate_version_code', 907)]:
            report = copy.deepcopy(original)
            report['upgrade'][key] = value
            path.write_text(json.dumps(report))
            with self.subTest(key=key):
                self.assertFalse(self.check()['passed'])

    def test_upgrade_raw_skip_cannot_be_hidden_by_passing_report(self):
        path = self.folder / 'upgrade' / 'verify.txt'
        path.write_text(path.read_text().replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3'))
        self.assertFalse(self.check()['passed'])

    def test_package_dump_allows_repeated_same_version_but_rejects_ambiguity(self):
        path = self.folder / 'upgrade' / 'candidate-package.txt'
        path.write_text('versionCode=908 minSdk=29\nversionCode=908 minSdk=29\n')
        self.assertTrue(self.check()['passed'])
        path.write_text('versionCode=908 minSdk=29\nversionCode=907 minSdk=29\n')
        self.assertFalse(self.check()['passed'])

    def test_interrupted_phase_requires_exact_boundary_and_actual_process_death(self):
        path = self.folder / 'report.json'
        original = json.loads(path.read_text())
        for key, value in [('pid_before', 0), ('pid_before', True), ('pid_after', 789), ('boundary', 'another_boundary')]:
            report = copy.deepcopy(original)
            report['lifecycle']['phases']['process_seed'][key] = value
            path.write_text(json.dumps(report))
            with self.subTest(key=key, value=value):
                self.assertFalse(self.check()['passed'])
        path.write_text(json.dumps(original))
        raw = self.folder / 'lifecycle' / 'process_seed.txt'
        raw.write_text(raw.read_text() + 'INSTRUMENTATION_STATUS: jarvisBoundary=process_kill\n')
        self.assertFalse(self.check()['passed'])

    def test_lifecycle_requires_all_named_phases_and_raw_passes(self):
        path = self.folder / 'report.json'
        original = json.loads(path.read_text())
        report = copy.deepcopy(original)
        del report['lifecycle']['phases']['permission_regranted']
        path.write_text(json.dumps(report))
        self.assertFalse(self.check()['passed'])
        path.write_text(json.dumps(original))
        (self.folder / 'lifecycle' / 'notification_denied.txt').write_text('OK (0 tests)\n')
        self.assertFalse(self.check()['passed'])

    def test_layout_requires_actual_fold_events_and_all_accessibility_tests(self):
        folder = self.root / artifact_name(next(profile for profile in self.profiles if profile['screen_profile'] == 'foldable'))
        path = folder / 'layout' / 'instrumentation.txt'
        raw = path.read_text()
        path.write_text(raw.replace('INSTRUMENTATION_STATUS: jarvisFold=unfold\n', ''))
        self.assertFalse(self.check()['passed'])
        path.write_text(raw.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3', 1))
        self.assertFalse(self.check()['passed'])

    def test_recorded_audio_missing_duplicate_skip_or_stale_evidence_rejected(self):
        path = self.root / 'jarvis-recorded-audio' / 'report.json'
        changes = [lambda report: report['checks'].pop(),
                   lambda report: report['checks'].__setitem__(1, copy.deepcopy(report['checks'][0])),
                   lambda report: report['checks'][0].__setitem__('status', 'skipped'),
                   lambda report: report.__setitem__('source_commit', self.head),
                   lambda report: report['checks'][0].__setitem__('input_sha256', 'f' * 64),
                   lambda report: report['checks'][0].__setitem__('text', 'invented wrong speech')]
        for index, mutate in enumerate(changes):
            report = copy.deepcopy(self.acoustic_report)
            mutate(report)
            path.write_text(json.dumps(report))
            with self.subTest(change=index):
                self.assertFalse(self.check()['passed'])

    def test_runtime_native_loading_cannot_omit_shipping_libraries_or_lie_about_page_size(self):
        path = self.folder / 'tests' / 'test04_nativeLibrariesLoadAtExpectedPageSize-native.json'
        original = json.loads(path.read_text())
        for key, value in [('loaded_libraries', []), ('shipping_libraries', ['another']), ('page_size', 16384)]:
            native = copy.deepcopy(original)
            native[key] = value
            path.write_text(json.dumps(native))
            with self.subTest(key=key):
                self.assertFalse(self.check()['passed'])


if __name__ == '__main__':
    unittest.main()
