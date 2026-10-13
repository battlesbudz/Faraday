#!/usr/bin/env python3
"""One release emulator contract for workflow provisioning, artifact selection and receipts."""
import argparse
import json
from pathlib import Path
import re
import sys

try:
    from .artifacts import ArtifactError, parse_requirement, select
except ImportError:
    from artifacts import ArtifactError, parse_requirement, select

PROFILES = Path(__file__).with_name('profiles.json')
FIELDS = {'id', 'api', 'apk', 'device_profile', 'screen_profile', 'page_size', 'target',
          'runner', 'arch', 'acceleration', 'boot_timeout', 'job_timeout', 'instrumentation_timeout'}


def load_profiles(path=PROFILES):
    contract = json.loads(Path(path).read_text())
    if not isinstance(contract, dict) or contract.get('schema') != 1:
        raise ValueError('Invalid emulator profile schema')
    profiles = contract.get('profiles')
    if not isinstance(profiles, list) or not 1 <= len(profiles) <= 20:
        raise ValueError('Missing or oversized required emulator profiles')
    ids, configurations = set(), set()
    for profile in profiles:
        if not isinstance(profile, dict) or set(profile) != FIELDS:
            raise ValueError('Invalid emulator profile fields')
        if not isinstance(profile['id'], str) or not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', profile['id']):
            raise ValueError('Invalid emulator profile ID')
        if isinstance(profile['api'], bool) or not isinstance(profile['api'], int) or not 30 <= profile['api'] <= 99:
            raise ValueError('Invalid emulator API')
        if profile['apk'] not in ('app-release', 'app-compact'):
            raise ValueError('Invalid APK variant')
        if not isinstance(profile['device_profile'], str) or (profile['device_profile'] != '7.6in Foldable' and
                not re.fullmatch(r'[A-Za-z0-9_]+', profile['device_profile'])):
            raise ValueError('Invalid emulator device profile')
        if profile['screen_profile'] not in ('phone', 'foldable'):
            raise ValueError('Invalid screen profile')
        if isinstance(profile['page_size'], bool) or profile['page_size'] not in (4096, 16384):
            raise ValueError('Invalid emulator page size')
        allowed_targets = ('google_apis', 'google_apis_ps16k')
        if profile['target'] not in allowed_targets:
            raise ValueError('Invalid system image target')
        if (profile['target'] == 'google_apis_ps16k') != (profile['page_size'] == 16384):
            raise ValueError('Page size disagrees with system image target')
        if profile['runner'] != 'ubuntu-latest':
            raise ValueError('Invalid emulator runner')
        if profile['arch'] != 'x86_64':
            raise ValueError('Invalid emulator architecture')
        if profile['acceleration'] != 'kvm':
            raise ValueError('Invalid emulator acceleration')
        for key, lower, upper in (('boot_timeout', 300, 300), ('job_timeout', 40, 40)):
            if isinstance(profile[key], bool) or not isinstance(profile[key], int) or not lower <= profile[key] <= upper:
                raise ValueError(f'Invalid or out-of-bounds emulator {key}')
        # Build 1254 completed 76/82 Fold journeys in 894.318 seconds. Give only
        # this reviewed profile more aggregate capacity, not longer assertions.
        fold_capacity = profile['id'] == '36-foldable-compact'
        if fold_capacity and (
                tuple(profile[key] for key in ('api', 'apk', 'screen_profile', 'page_size', 'target')) !=
                (36, 'app-compact', 'foldable', 4096, 'google_apis') or
                profile['device_profile'] not in ('pixel_fold', '7.6in Foldable')):
            raise ValueError('Fold main instrumentation capacity requires its declared profile')
        main_timeout = profile['instrumentation_timeout']
        if (isinstance(main_timeout, bool) or not isinstance(main_timeout, int) or
                main_timeout != (1200 if fold_capacity else 900)):
            raise ValueError('Invalid or out-of-scope main instrumentation timeout')
        configuration = tuple(profile[key] for key in sorted(FIELDS - {'id'}))
        if profile['id'] in ids or configuration in configurations:
            raise ValueError('Duplicate required emulator profile')
        ids.add(profile['id'])
        configurations.add(configuration)
    return profiles


def artifact_name(profile):
    return f"jarvis-verification-{profile['id']}-{profile['apk']}"


def artifact_requirements(profiles=None):
    profiles = load_profiles() if profiles is None else profiles
    return [(artifact_name(profile),
             f"Verify signed release in Android sandbox / Release journeys / {profile['id']} / {profile['apk']}")
            for profile in profiles]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profiles', type=Path, default=PROFILES)
    commands = parser.add_subparsers(dest='command', required=True)
    matrix = commands.add_parser('matrix')
    matrix.add_argument('--github-output', type=Path)
    commands.add_parser('requirements')
    selector = commands.add_parser('select')
    selector.add_argument('--manifest', type=Path, required=True)
    selector.add_argument('--run-id', required=True)
    selector.add_argument('--head-sha', required=True)
    selector.add_argument('--require', type=parse_requirement, action='append', default=[])
    selector.add_argument('--github-output', type=Path)
    selector.add_argument('--require-success', action='store_true')
    args = parser.parse_args()
    try:
        profiles = load_profiles(args.profiles)
        if args.command == 'matrix':
            matrix_json = json.dumps({'include': profiles}, separators=(',', ':'))
            print(matrix_json)
            if args.github_output:
                with args.github_output.open('a') as output:
                    output.write(f'matrix={matrix_json}\n')
            return 0
        requirements = artifact_requirements(profiles)
        if args.command == 'requirements':
            print(json.dumps(requirements))
            return 0
        requirements = [*args.require, *requirements]
        names = [name for name, _ in requirements]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate required artifact')
        selected = select(json.loads(args.manifest.read_text()), run_id=args.run_id, head_sha=args.head_sha,
                          requirements=requirements, require_success=args.require_success)
        ids = ','.join(str(artifact['id']) for artifact in selected)
        print(ids)
        if args.github_output:
            with args.github_output.open('a') as output:
                output.write(f'artifact_ids={ids}\n')
        return 0
    except (OSError, ValueError, TypeError, ArtifactError) as error:
        print(f'Emulator profile contract failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
