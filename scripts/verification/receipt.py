#!/usr/bin/env python3
"""Consolidate same-run GitHub artifacts; never approve a release or fetch credentials."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from urllib.parse import urlparse

# Direct workflow invocation starts on scripts/verification, while the native
# audit helper lives one directory above. Keep CLI and unittest imports equal.
SCRIPTS = Path(__file__).resolve().parent.parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
from check_page_sizes import audit_apk
from check_recorded_audio import contract as acoustic_contract, validate_report as validate_acoustic_report

try:
    from .android import instrumentation_results, interrupted_results, sha256, SCENARIOS, LIFECYCLE_SCENARIOS, LAYOUT_SCENARIOS
    from .profiles import artifact_name, load_profiles
except ImportError:
    from android import instrumentation_results, interrupted_results, sha256, SCENARIOS, LIFECYCLE_SCENARIOS, LAYOUT_SCENARIOS
    from profiles import artifact_name, load_profiles


def xml_file(path):
    data = path.read_bytes()
    if len(data) > 8 * 1024 * 1024 or b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
        raise ValueError('Unsafe or oversized XML')
    return ET.fromstring(data)


def junit_results(folder, required=()):
    files = sorted(folder.rglob('TEST-*.xml'))
    if not files:
        raise ValueError('Missing JVM test XML')
    names = set()
    for path in files:
        suite = xml_file(path)
        cases = suite.findall('testcase')
        if suite.tag != 'testsuite' or int(suite.get('tests', '-1')) != len(cases):
            raise ValueError('Invalid JVM suite/count')
        if any(int(suite.get(key, '0')) != 0 for key in ('failures', 'errors', 'skipped')):
            raise ValueError('JVM suite reports failures, errors or skips')
        for case in cases:
            key = (case.get('classname'), case.get('name'))
            if not all(key) or key in names or any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Failed, skipped, unnamed or duplicate JVM test')
            names.add(key)
    if not names:
        raise ValueError('No JVM tests executed')
    missing = {(entry['classname'], entry['name']) for entry in required} - names
    if missing:
        raise ValueError(f'Missing required recorded-audio JVM regressions: {sorted(missing)}')
    return {'passed': True, 'tests': len(names), 'suites': len(files)}


def native_page_results(root, source_commit):
    """Recompute every native check from candidate bytes, not a report's claimed pass."""
    report = json.loads((root / 'jarvis-native-page-sizes' / 'report.json').read_text())
    if (report.get('schema') != 1 or report.get('source_commit') != source_commit or
            report.get('page_size') != 16384 or report.get('passed') is not True):
        raise ValueError('Invalid native page-size report identity or outcome')
    apks = report.get('apks')
    if not isinstance(apks, list) or len(apks) != 2:
        raise ValueError('Missing or duplicate native APK audits')
    names = [apk.get('name') for apk in apks if isinstance(apk, dict)]
    if sorted(names) != ['app-compact.apk', 'app-release.apk']:
        raise ValueError('Wrong or duplicate native APK audit variants')
    verified = []
    for apk in apks:
        actual = audit_apk(root / 'jarvis-os-v2-release-apk' / apk['name'])
        if actual['passed'] is not True or apk != actual:
            raise ValueError(f"Native page audit failed or disagrees with APK bytes: {apk['name']}")
        verified.append({'apk': apk['name'], 'apk_sha256': actual['sha256'],
                         'libraries': len(actual['libraries']), 'passed': True})
    return {'passed': True, 'page_size': 16384, 'apks': verified}


def previous_release_results(root, run_url):
    folder = root / 'jarvis-previous-release'
    baseline = json.loads((folder / 'previous-release.json').read_text())
    expected = {'schema': 1, 'apk': 'app-release.apk',
                'download_source': 'GitHub numbered release'}
    if any(baseline.get(key) != value for key, value in expected.items()):
        raise ValueError('Invalid previous-release metadata schema or source')
    run = urlparse(run_url)
    match = re.fullmatch(r'/([^/]+/[^/]+)/actions/runs/[1-9][0-9]*', run.path)
    if run.scheme != 'https' or not match or baseline.get('repository') != match.group(1):
        raise ValueError('Previous release belongs to another repository')
    for key in ('build', 'release_id', 'asset_id', 'size_bytes'):
        if isinstance(baseline.get(key), bool) or not isinstance(baseline.get(key), int) or baseline[key] < 1:
            raise ValueError(f'Invalid previous-release {key}')
    if not re.fullmatch(r'audio-pr2-pr6-build\.[1-9][0-9]*', baseline.get('tag', '')):
        raise ValueError('Previous release is outside the user distribution stream')
    if int(baseline['tag'].rsplit('.', 1)[1]) != baseline['build']:
        raise ValueError('Previous release tag and build disagree')
    if (sha256(folder / 'app-release.apk') != baseline.get('sha256') or
            (folder / 'app-release.apk').stat().st_size != baseline['size_bytes']):
        raise ValueError('Previous release APK hash or size mismatch')
    return baseline


def snapshot_result(folder, name):
    pngs, xmls = list(folder.rglob(f'{name}.png')), list(folder.rglob(f'{name}.xml'))
    if len(pngs) != 1 or len(xmls) != 1 or not pngs[0].read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError(f'Missing/ambiguous screenshot or hierarchy: {name}')
    tree = xml_file(xmls[0])
    if tree.tag != 'hierarchy':
        raise ValueError('Invalid UI hierarchy')
    return tree


def lifecycle_results(folder, report, previous):
    contract = json.loads(LIFECYCLE_SCENARIOS.read_text())
    upgrade = report.get('upgrade', {})
    if (upgrade.get('passed') is not True or upgrade.get('previous_apk_sha256') != previous['sha256'] or
            upgrade.get('previous_version_code') != previous['build']):
        raise ValueError('Upgrade baseline identity or outcome mismatch')
    candidate_code = upgrade.get('candidate_version_code')
    if isinstance(candidate_code, bool) or not isinstance(candidate_code, int) or candidate_code <= previous['build']:
        raise ValueError('Candidate is not a strictly newer installed APK')
    if upgrade.get('data_cleared_during_update') is not False:
        raise ValueError('Upgrade did not preserve existing application data')
    if upgrade.get('previous_release') != previous or json.loads((folder / 'upgrade' / 'previous-release.json').read_text()) != previous:
        raise ValueError('Upgrade previous release provenance disagrees with fetched baseline')
    for phase, version in (('previous', previous['build']), ('candidate', candidate_code)):
        raw_version = re.findall(r'\bversionCode=(\d+)\b', (folder / 'upgrade' / f'{phase}-package.txt').read_text())
        if set(raw_version) != {str(version)}:
            raise ValueError(f'Upgrade {phase} installed package version disagrees with report')
    for phase, entry in contract['upgrade'].items():
        raw = instrumentation_results((folder / 'upgrade' / f'{phase}.txt').read_text(),
                                      [entry['test']], entry['class'])
        if raw['passed'] is not True or raw != upgrade.get(phase):
            raise ValueError(f'Upgrade {phase} raw named result is incomplete or disagrees with report')
        snapshot_result(folder / 'tests', entry['test'])
    lifecycle = report.get('lifecycle', {})
    phases = lifecycle.get('phases', {})
    if lifecycle.get('passed') is not True or set(phases) != set(contract['phases']):
        raise ValueError('Lifecycle phase set is missing, duplicated or unexpected')
    for phase, entry in contract['phases'].items():
        output = (folder / 'lifecycle' / f'{phase}.txt').read_text()
        observed = phases[phase]
        if 'interrupt' in entry:
            raw = interrupted_results(output, entry['test'], contract['class'], entry['interrupt'])
            if (observed.get('boundary') != entry['interrupt'] or observed.get('pid_after', False) is not None or
                    isinstance(observed.get('pid_before'), bool) or not isinstance(observed.get('pid_before'), int) or
                    observed['pid_before'] < 1):
                raise ValueError(f'Lifecycle {phase} lacks actual acknowledged process death')
            comparable = {key: value for key, value in observed.items() if key not in ('boundary', 'pid_before', 'pid_after')}
            snapshot_result(folder, f"boundary-{entry['interrupt']}")
        else:
            raw = instrumentation_results(output, [entry['test']], contract['class'])
            comparable = observed
        if raw['passed'] is not True or raw != comparable:
            raise ValueError(f'Lifecycle {phase} raw result is incomplete or disagrees with report')
        snapshot_result(folder / 'tests', entry['test'])
    return {'upgrade': {'passed': True, 'previous_apk_sha256': previous['sha256'],
                        'previous_version_code': previous['build'], 'candidate_version_code': candidate_code,
                        'tests': len(contract['upgrade'])},
            'lifecycle': {'passed': True, 'phases': list(contract['phases'])}}


def layout_results(folder, report, profile, apk):
    contract = json.loads(LAYOUT_SCENARIOS.read_text())
    layout = report.get('layout', {})
    output = (folder / 'layout' / 'instrumentation.txt').read_text()
    raw = instrumentation_results(output, contract['tests'], contract['class'])
    if layout.get('passed') is not True or raw['passed'] is not True or raw != layout.get('instrumentation'):
        raise ValueError('Raw layout/accessibility instrumentation is incomplete or disagrees with report')
    events = [{'posture': posture} for posture in re.findall(r'^INSTRUMENTATION_STATUS: jarvisFold=(.+)$', output, re.MULTILINE)]
    expected_events = [{'posture': 'fold'}, {'posture': 'unfold'}] if profile['screen_profile'] == 'foldable' else []
    if events != expected_events or layout.get('fold_events') != expected_events:
        raise ValueError('Required actual fold/unfold event sequence is missing or disagrees with report')
    for name in contract['tests']:
        snapshot_result(folder / 'tests', name)
    posture_test = 'test03_foldAndUnfoldPreserveActiveCall'
    for suffix in (('folded', 'unfolded') if profile['screen_profile'] == 'foldable' else ('rotated',)):
        snapshot_result(folder / 'tests', f'{posture_test}-{suffix}')
    native_files = list((folder / 'tests').rglob('test04_nativeLibrariesLoadAtExpectedPageSize-native.json'))
    if len(native_files) != 1:
        raise ValueError('Missing or duplicate actual native-loading evidence')
    native = json.loads(native_files[0].read_text())
    with zipfile.ZipFile(apk) as archive:
        libraries = sorted(path.rsplit('/', 1)[-1].removeprefix('lib').removesuffix('.so')
                           for path in archive.namelist() if re.fullmatch(r'lib/arm64-v8a/[^/]+\.so', path))
    if (not libraries or len(set(libraries)) != len(libraries) or native.get('passed') is not True or
            native.get('page_size') != profile['page_size'] or native.get('expected_page_size') != profile['page_size'] or
            sorted(native.get('shipping_libraries', [])) != libraries or sorted(native.get('loaded_libraries', [])) != libraries or
            native != layout.get('native_loading')):
        raise ValueError('Actual native-loading evidence disagrees with shipping APK bytes/page size')
    return {'passed': True, 'tests': len(raw['tests']), 'fold_events': expected_events,
            'native_loading': {'passed': True, 'page_size': profile['page_size'], 'libraries': libraries}}


def acoustic_results(root, source_commit):
    report = json.loads((root / 'jarvis-recorded-audio' / 'report.json').read_text())
    validate_acoustic_report(report, source_commit)
    return {'passed': True, 'runtime_versions': report['runtime_versions'],
            'checks': [{key: check[key] for key in ('name', 'backend', 'sample_id', 'variant', 'input_sha256',
                                                  'model_files', 'word_error_rate', 'first_word_correct', 'last_word_correct')}
                       for check in report['checks']]}


def consolidate(root, source_commit, pr_head, run_url, needs):
    scenarios = json.loads(SCENARIOS.read_text())
    result = {'schema': 2, 'passed': False, 'source_commit': source_commit,
              'pr_head': pr_head, 'run_url': run_url, 'errors': [], 'variants': [],
              'release_approved': False,
              'coverage': 'Pinned host Whisper recorded inference + shipping JVM phrase capture/Gemma Content delivery + expanded Android emulator matrix',
              'not_covered': ['Android language-model inference/tool selection/approved-memory use and Gemma audio understanding'
                              if item == 'Real-model inference and model-generated tool selection' else item
                              for item in scenarios['not_covered']],
              'provenance': 'Same-run GitHub Actions artifacts; APK bytes rehashed. Not an external attestation.'}
    errors = result['errors']
    if not re.fullmatch('[0-9a-f]{40}', source_commit) or (pr_head and not re.fullmatch('[0-9a-f]{40}', pr_head)):
        errors.append('Invalid source revision')
    for job in ('build-release', 'prepare-upgrade-baseline', 'verify-release', 'verify-native-pages', 'verify-recorded-audio'):
        if needs.get(job, {}).get('result') != 'success':
            errors.append(f'Required job did not succeed: {job}')
    try:
        result['jvm'] = junit_results(root / 'jarvis-release-unit-tests', acoustic_contract()['required_jvm_tests'])
    except (OSError, ValueError, ET.ParseError) as error:
        errors.append(f'JVM evidence: {error}')
    try:
        result['native_page_sizes'] = native_page_results(root, source_commit)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, zipfile.BadZipFile) as error:
        errors.append(f'Native page-size evidence: {error}')
    try:
        result['previous_release'] = previous_release_results(root, run_url)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        errors.append(f'Previous release evidence: {error}')
    try:
        result['recorded_audio'] = acoustic_results(root, source_commit)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        errors.append(f'Recorded audio evidence: {error}')
    try:
        profiles = load_profiles()
        result['required_profiles'] = profiles
    except (OSError, ValueError, TypeError) as error:
        errors.append(f'Emulator profile contract: {error}')
        profiles = []
    for profile in profiles:
        api, apk = profile['api'], profile['apk']
        try:
            folder = root / artifact_name(profile)
            report = json.loads((folder / 'report.json').read_text())
            if report.get('passed') is not True:
                raise ValueError('Emulator report did not explicitly pass')
            expected = {'passed': True, 'source_commit': source_commit, 'pr_head': pr_head,
                        'run_url': run_url, 'errors': [], 'process_restart': 'passed',
                        'profile': profile,
                        'apk_sha256': sha256(root / 'jarvis-os-v2-release-apk' / f'{apk}.apk'),
                        'test_apk_sha256': sha256(root / 'jarvis-verification-test-apk' / 'app-release-androidTest.apk')}
            for key, value in expected.items():
                if report.get(key) != value:
                    raise ValueError(f'Mismatched {key}')
            if report.get('device', {}).get('api') != str(api):
                raise ValueError('Wrong emulator API')
            if str(report.get('device', {}).get('page_size')) != str(profile['page_size']):
                raise ValueError('Wrong emulator page size')
            raw = instrumentation_results((folder / 'instrumentation.txt').read_text(), scenarios['tests'], scenarios['class'])
            if not raw['passed'] or raw != report.get('instrumentation'):
                raise ValueError('Raw instrumentation is incomplete or disagrees with report')
            for name in ['first-launch', 'final', 'after-process-restart', *scenarios['tests']]:
                base = folder / 'tests' if name in scenarios['tests'] else folder
                tree = snapshot_result(base, name)
                if name == 'after-process-restart' and scenarios['restart_model'] not in [n.get('text') for n in tree.iter('node')]:
                    raise ValueError('Selection did not persist across process restart')
            additional = lifecycle_results(folder, report, result['previous_release'])
            additional['layout_accessibility'] = layout_results(folder, report, profile,
                                                                 root / 'jarvis-os-v2-release-apk' / f'{apk}.apk')
            result['variants'].append({'profile': profile, 'api': api, 'apk': f'{apk}.apk', 'apk_sha256': expected['apk_sha256'],
                                       'test_apk_sha256': expected['test_apk_sha256'], 'tests': len(raw['tests']),
                                       **additional, 'passed': True})
        except (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError, zipfile.BadZipFile) as error:
            errors.append(f"Profile {profile['id']} {apk}: {error}")
    result['passed'] = not errors
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    receipt = consolidate(args.inputs, os.environ['GITHUB_SHA'], os.getenv('PR_HEAD', ''),
                          f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
                          json.loads(os.environ['VERIFICATION_NEEDS']))
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    summary = (f"# Jarvis verification: {'PASS' if receipt['passed'] else 'FAIL'}\n\n"
               f"Tested commit: `{receipt['source_commit']}`\n\nPR head: `{receipt['pr_head'] or 'push build'}`\n\n"
               f"JVM tests: {receipt.get('jvm', {}).get('tests', 0)}. Verified APK variants: {len(receipt['variants'])}.\n\n"
               + '\n'.join(f'- {error}' for error in receipt['errors']) + '\n\nNot verified:\n'
               + '\n'.join(f'- {item}' for item in receipt['not_covered'])
               + '\n\nScreenshots checked for presence/structure only. Final user sign-off is still required.\n')
    (args.out / 'summary.md').write_text(summary)
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(summary)
    print(json.dumps(receipt, indent=2))
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
