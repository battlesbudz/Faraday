#!/usr/bin/env python3
"""Bind retained successful producer outputs across same-run partial retries."""
import argparse
import json
from pathlib import Path
import re
import sys

VERIFICATION = Path(__file__).resolve().parents[1] / 'verification'
if str(VERIFICATION) not in sys.path:
    sys.path.insert(0, str(VERIFICATION))
from artifacts import select as select_artifacts

PRODUCERS = {'sdk': 'Build reviewed streaming SDK ARM64',
             'quality': 'Verify full-E2B native audio quality'}
KINDS = {'sdk': 'jarvis-streaming-sdk-aar', 'quality': 'jarvis-streaming-quality-evidence'}


def attempt_number(value):
    if not isinstance(value, str) or not re.fullmatch('[1-9][0-9]*', value):
        raise ValueError('Missing or invalid SDK producer/current attempt')
    return int(value)


def workflow_identity(environment, producer_attempt=None):
    """The consumer keeps this run/source, but uses the retained producer attempt."""
    identity = {key: environment.get(key, '') for key in
                ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA', 'GITHUB_REPOSITORY')}
    current = attempt_number(identity['GITHUB_RUN_ATTEMPT'])
    selected = identity['GITHUB_RUN_ATTEMPT'] if producer_attempt is None else producer_attempt
    if attempt_number(selected) > current:
        raise ValueError('SDK producer attempt is newer than the consuming workflow attempt')
    if (not re.fullmatch('[1-9][0-9]*', identity['GITHUB_RUN_ID'])
            or not re.fullmatch('[0-9a-f]{40}', identity['GITHUB_SHA'])
            or not identity['GITHUB_REPOSITORY']):
        raise ValueError('Missing or invalid current workflow run/source identity')
    identity['GITHUB_RUN_ATTEMPT'] = selected
    return identity


def select_producer(manifest, *, repository, run_id, head_sha, kind, artifact_name,
                    producer_attempt, current_attempt, artifact_id):
    if manifest.get("repository") != repository:
        raise ValueError("Artifact manifest belongs to another repository")
    if attempt_number(producer_attempt) > attempt_number(current_attempt):
        raise ValueError('SDK producer attempt is newer than the consumer')
    if artifact_name != f'{KINDS[kind]}-{run_id}-{producer_attempt}':
        raise ValueError('Artifact name does not match retained SDK producer identity')
    if not isinstance(artifact_id, str) or not re.fullmatch('[1-9][0-9]*', artifact_id):
        raise ValueError('Missing or invalid retained producer artifact ID')
    # GitHub repeats an already-successful job in later attempt metadata, keeping
    # its original timestamps. The existing resolver owns that retry contract;
    # do not compare its logical attempt to the original producer's attempt.
    result = select_artifacts(manifest, run_id=run_id, head_sha=head_sha,
                             requirements=[(artifact_name, PRODUCERS[kind])], require_success=True)
    if str(result[0]['id']) != artifact_id:
        raise ValueError('Selected artifact differs from retained successful producer ID')
    return result[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    for name in ('repository', 'run-id', 'head-sha', 'artifact-name', 'producer-attempt', 'current-attempt', 'artifact-id'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--kind', choices=sorted(KINDS), required=True)
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    artifact = select_producer(json.loads(args.manifest.read_text()), repository=args.repository, run_id=args.run_id,
        head_sha=args.head_sha, kind=args.kind, artifact_name=args.artifact_name,
        producer_attempt=args.producer_attempt, current_attempt=args.current_attempt, artifact_id=args.artifact_id)
    if args.github_output:
        with args.github_output.open('a') as output:
            output.write(f'artifact_ids={artifact["id"]}\n')
    print(artifact['id'])


if __name__ == '__main__':
    main()
