#!/usr/bin/env python3
"""Resolve exact, current-run GitHub artifact IDs without ordering by numeric ID."""
import argparse
import hashlib
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen
import stat
import zipfile

API_ROOT = 'https://api.github.com'
MAX_METADATA_BYTES = 4 * 1024 * 1024
MAX_PAGES = 100
MAX_ATTEMPTS = 20
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_FILES = 10000
MAX_EXTRACTED_BYTES = 1024 * 1024 * 1024
REPOSITORY = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}')

class ArtifactError(ValueError):
    """The evidence manifest is incomplete, stale, or ambiguous."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def timestamp(value, label):
    if not isinstance(value, str):
        raise ArtifactError(f'Missing {label}')
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError as error:
        raise ArtifactError(f'Invalid {label}') from error
    if parsed.tzinfo is None:
        raise ArtifactError(f'Invalid {label}')
    return parsed.astimezone(timezone.utc)


def integer(value, label):
    if isinstance(value, bool):
        raise ArtifactError(f'Invalid {label}')
    try:
        result = int(value)
    except (TypeError, ValueError) as error:
        raise ArtifactError(f'Invalid {label}') from error
    if result < 1:
        raise ArtifactError(f'Invalid {label}')
    return result


def artifact_url_is_safe(url, repository, artifact_id):
    parsed = urlparse(url)
    expected = f'/repos/{repository}/actions/artifacts/{artifact_id}/zip'
    return parsed.scheme == 'https' and parsed.netloc == 'api.github.com' and parsed.path == expected and not parsed.params and not parsed.query and not parsed.fragment


def latest_producer(jobs, producer):
    matches = [job for job in jobs if job.get('name') == producer]
    if not matches:
        raise ArtifactError(f'Missing producer job: {producer}')
    attempts = [integer(job.get('attempt'), 'producer attempt') for job in matches]
    latest_attempt = max(attempts)
    latest = [job for job, attempt in zip(matches, attempts) if attempt == latest_attempt]
    if len(latest) != 1:
        raise ArtifactError(f'Ambiguous producer job attempt: {producer}')
    job = latest[0]
    if job.get('status') != 'completed':
        raise ArtifactError(f'Incomplete producer job: {producer}')
    started = timestamp(job.get('started_at'), 'producer started_at')
    completed = timestamp(job.get('completed_at'), 'producer completed_at')
    if completed < started:
        raise ArtifactError(f'Invalid producer duration: {producer}')
    return job, latest_attempt, started, completed


def select(manifest, *, run_id, head_sha, requirements, require_success=False):
    """Return artifacts tied to each requirement's latest logical producer attempt.

    A partial workflow rerun repeats successful jobs in its latest attempt metadata.  Their
    original timestamps are therefore authoritative; artifact IDs deliberately never are.
    """
    if not isinstance(manifest, dict):
        raise ArtifactError('Invalid manifest')
    repository = manifest.get('repository')
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        raise ArtifactError('Invalid repository')
    expected_run = integer(run_id, 'run ID')
    run = manifest.get('run')
    if not isinstance(run, dict) or integer(run.get('id'), 'manifest run ID') != expected_run:
        raise ArtifactError('Manifest belongs to another workflow run')
    if run.get('head_sha') != head_sha or not re.fullmatch(r'[0-9a-f]{40}', head_sha or ''):
        raise ArtifactError('Manifest belongs to another source revision')
    artifacts = manifest.get('artifacts')
    jobs = manifest.get('jobs')
    if not isinstance(artifacts, list) or not isinstance(jobs, list):
        raise ArtifactError('Invalid manifest collections')

    selections = []
    for name, producer in requirements:
        matching_jobs = [job for job in jobs if job.get('name') == producer]
        if any(job.get('run_id') != expected_run or job.get('head_sha') != head_sha for job in matching_jobs):
            raise ArtifactError(f'Foreign producer job: {producer}')
        job, attempt, started, completed = latest_producer(matching_jobs, producer)
        if require_success and job.get('conclusion') != 'success':
            raise ArtifactError(f'Latest producer did not succeed: {producer}')
        candidates = []
        for artifact in artifacts:
            if not isinstance(artifact, dict) or artifact.get('name') != name:
                continue
            artifact_id = integer(artifact.get('id'), 'artifact ID')
            association = artifact.get('workflow_run')
            if not isinstance(association, dict) or association.get('id') != expected_run or association.get('head_sha') != head_sha:
                continue
            if artifact.get('expired') is not False:
                continue
            created = timestamp(artifact.get('created_at'), 'artifact created_at')
            # upload-artifact completes just before the job does. A small API clock lag
            # allowance keeps the trust boundary on the producer attempt, not artifact ID.
            if not started <= created <= completed + timedelta(minutes=2):
                continue
            if not artifact_url_is_safe(artifact.get('archive_download_url'), repository, artifact_id):
                continue
            candidates.append((created, artifact_id, artifact))
        if not candidates:
            raise ArtifactError(f'Missing current producer artifact: {name} (attempt {attempt})')
        newest = max(created for created, _, _ in candidates)
        winners = [candidate for candidate in candidates if candidate[0] == newest]
        if len(winners) != 1:
            raise ArtifactError(f'Ambiguous current producer artifact: {name} (attempt {attempt})')
        selections.append(winners[0][2])
    return selections


def request_json(url, token):
    request = Request(url, headers={'Accept': 'application/vnd.github+json',
                                    'Authorization': f'Bearer {token}',
                                    'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urlopen(request, timeout=20) as response:
            length = response.headers.get('Content-Length')
            if length and int(length) > MAX_METADATA_BYTES:
                raise ArtifactError('GitHub Actions metadata is oversized')
            data = response.read(MAX_METADATA_BYTES + 1)
            if len(data) > MAX_METADATA_BYTES:
                raise ArtifactError('GitHub Actions metadata is oversized')
            return json.loads(data)
    except (HTTPError, URLError, json.JSONDecodeError, ValueError) as error:
        raise ArtifactError(f'GitHub Actions metadata request failed: {error}') from error


def paged(api_root, path, key, token):
    result, page = [], 1
    expected_total = None
    while True:
        query = urlencode({'per_page': 100, 'page': page})
        payload = request_json(f'{api_root}{path}?{query}', token)
        values = payload.get(key)
        if not isinstance(values, list):
            raise ArtifactError(f'Invalid GitHub Actions {key} page')
        total = payload.get('total_count')
        if isinstance(total, bool) or not isinstance(total, int) or total < 0 or total > MAX_PAGES * 100:
            raise ArtifactError(f'Invalid GitHub Actions {key} total count')
        if expected_total is None:
            expected_total = total
        elif total != expected_total:
            raise ArtifactError(f'Inconsistent GitHub Actions {key} pagination')
        result.extend(values)
        if len(result) > expected_total:
            raise ArtifactError(f'Invalid GitHub Actions {key} pagination')
        if len(result) == expected_total:
            return result
        if len(values) < 100:
            raise ArtifactError(f'Truncated GitHub Actions {key} pagination')
        page += 1
        if page > MAX_PAGES:
            raise ArtifactError(f'Unsafe GitHub Actions {key} pagination')


def fetch_manifest(*, repository, run_id, token):
    if not REPOSITORY.fullmatch(repository):
        raise ArtifactError('Invalid repository')
    run_id = integer(run_id, 'run ID')
    base = f'/repos/{repository}/actions/runs/{run_id}'
    run = request_json(f'{API_ROOT}{base}', token)
    if integer(run.get('id'), 'run ID') != run_id or not re.fullmatch(r'[0-9a-f]{40}', run.get('head_sha', '')):
        raise ArtifactError('Invalid GitHub workflow run')
    if run.get('repository', {}).get('full_name') != repository:
        raise ArtifactError('Workflow run belongs to another repository')
    attempt_count = integer(run.get('run_attempt'), 'workflow attempt')
    if attempt_count > MAX_ATTEMPTS:
        raise ArtifactError('Unsafe workflow attempt count')
    artifacts = paged(API_ROOT, f'{base}/artifacts', 'artifacts', token)
    jobs = []
    for attempt in range(1, attempt_count + 1):
        for job in paged(API_ROOT, f'{base}/attempts/{attempt}/jobs', 'jobs', token):
            if job.get('run_id') != run_id or job.get('head_sha') != run['head_sha'] or job.get('run_attempt') != attempt:
                raise ArtifactError('Job belongs to another workflow run or attempt')
            job['attempt'] = attempt
            jobs.append(job)
    for artifact in artifacts:
        association = artifact.get('workflow_run')
        if not isinstance(association, dict) or association.get('id') != run_id or association.get('head_sha') != run['head_sha']:
            raise ArtifactError('Artifact belongs to another workflow run or revision')
    return {'schema': 1, 'repository': repository, 'run': {'id': run_id, 'head_sha': run['head_sha'],
            'attempt': attempt_count}, 'artifacts': artifacts, 'jobs': jobs}


def parse_requirement(value):
    name, separator, producer = value.partition('=')
    if not separator or not name or not producer:
        raise argparse.ArgumentTypeError('Requirement must be ARTIFACT=PRODUCER_JOB')
    return name, producer


def selected_artifacts(manifest, ids):
    if not isinstance(manifest, dict) or not isinstance(manifest.get('artifacts'), list):
        raise ArtifactError('Invalid artifact manifest')
    wanted = [integer(item, 'artifact ID') for item in ids.split(',') if item]
    if not wanted or len(wanted) != len(set(wanted)):
        raise ArtifactError('Invalid artifact IDs')
    values = {integer(item.get('id'), 'artifact ID'): item for item in manifest['artifacts'] if isinstance(item, dict)}
    if len(values) != len([item for item in manifest['artifacts'] if isinstance(item, dict)]):
        raise ArtifactError('Duplicate artifact metadata')
    try:
        result = [values[item] for item in wanted]
    except KeyError as error:
        raise ArtifactError('Selected artifact metadata is missing') from error
    for item in result:
        identifier = integer(item.get('id'), 'artifact ID')
        if not artifact_url_is_safe(item.get('archive_download_url'), manifest.get('repository'), identifier):
            raise ArtifactError('Unsafe artifact download URL')
        size = integer(item.get('size_in_bytes'), 'artifact size')
        digest = item.get('digest')
        if size > MAX_ARCHIVE_BYTES or not isinstance(digest, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
            raise ArtifactError('Invalid artifact integrity metadata')
        name = item.get('name')
        if not isinstance(name, str) or name in ('', '.', '..') or '/' in name or '\\' in name:
            raise ArtifactError('Unsafe artifact name')
    return result


def storage_redirect_is_safe(url):
    parsed = urlparse(url)
    host = parsed.hostname or ''
    return (parsed.scheme == 'https' and parsed.port in (None, 443) and not parsed.username and not parsed.password and
            not parsed.params and not parsed.fragment and
            (host in ('actions.githubusercontent.com', 'pipelines.actions.githubusercontent.com') or
             host.endswith('.blob.core.windows.net')))


def read_archive(url, token, expected_size):
    headers = {'Accept': 'application/vnd.github+json', 'Authorization': f'Bearer {token}',
               'X-GitHub-Api-Version': '2022-11-28'}
    opener = build_opener(NoRedirect)
    try:
        try:
            response = opener.open(Request(url, headers=headers), timeout=30)
        except HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise ArtifactError(f'Artifact download failed (HTTP {error.code})') from error
            response = error
        with response:
            if response.status not in (301, 302, 303, 307, 308):
                raise ArtifactError('Artifact download did not redirect')
            redirect = response.headers.get('Location')
        if not redirect or not storage_redirect_is_safe(redirect):
            raise ArtifactError('Unsafe artifact download redirect')
        with urlopen(Request(redirect, headers={'Accept': 'application/octet-stream'}), timeout=30) as response:
            length = response.headers.get('Content-Length')
            if length and int(length) != expected_size:
                raise ArtifactError('Artifact download size mismatch')
            data = response.read(expected_size + 1)
    except ArtifactError:
        raise
    except HTTPError as error:
        raise ArtifactError(f'Artifact download failed (HTTP {error.code})') from error
    except (URLError, ValueError) as error:
        raise ArtifactError('Artifact download failed') from error
    if len(data) != expected_size:
        raise ArtifactError('Artifact download size mismatch')
    return data


def zip_members(data):
    try:
        archive = zipfile.ZipFile(__import__('io').BytesIO(data))
        infos = archive.infolist()
    except (OSError, zipfile.BadZipFile) as error:
        raise ArtifactError('Invalid artifact ZIP') from error
    if len(infos) > MAX_ARCHIVE_FILES:
        raise ArtifactError('Artifact ZIP has too many files')
    names, total = set(), 0
    for info in infos:
        path = PurePosixPath(info.filename)
        mode = info.external_attr >> 16
        file_type = stat.S_IFMT(mode)
        if (not info.filename or '\\' in info.filename or path.is_absolute() or
                any(part in ('', '.', '..') for part in path.parts) or info.filename.endswith('/') or
                str(path) != info.filename or stat.S_ISLNK(mode) or
                file_type not in (0, stat.S_IFREG) or info.flag_bits & 1):
            raise ArtifactError('Unsafe artifact ZIP member')
        if info.filename in names or info.file_size < 0:
            raise ArtifactError('Duplicate or invalid artifact ZIP member')
        names.add(info.filename)
        total += info.file_size
        if total > MAX_EXTRACTED_BYTES or (info.compress_size == 0 and info.file_size) or (
                info.compress_size and info.file_size > info.compress_size * 1000):
            raise ArtifactError('Artifact ZIP exceeds extraction limits')
    return archive, infos


def download(manifest, ids, output, layout, token):
    artifacts = selected_artifacts(manifest, ids)
    output.mkdir(parents=True, exist_ok=False)
    occupied = set()
    try:
        for artifact in artifacts:
            data = read_archive(artifact['archive_download_url'], token, integer(artifact['size_in_bytes'], 'artifact size'))
            if 'sha256:' + hashlib.sha256(data).hexdigest() != artifact['digest']:
                raise ArtifactError('Artifact download digest mismatch')
            archive, infos = zip_members(data)
            prefix = Path(artifact['name']) if layout == 'named' else Path()
            for info in infos:
                target = prefix / info.filename
                target_name = str(target)
                if any(target_name == prior or target_name.startswith(prior + '/') or prior.startswith(target_name + '/')
                       for prior in occupied):
                    raise ArtifactError('Artifact ZIP members collide')
                occupied.add(target_name)
                destination = output / target
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, destination.open('xb') as destination_file:
                    destination_file.write(source.read())
        print('Downloaded verified artifact IDs: ' + ','.join(str(item['id']) for item in artifacts))
    except Exception:
        import shutil
        shutil.rmtree(output, ignore_errors=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    fetch = commands.add_parser('fetch')
    fetch.add_argument('--out', type=Path, required=True)
    fetch.add_argument('--repository', required=True)
    fetch.add_argument('--run-id', required=True)
    select_parser = commands.add_parser('select')
    select_parser.add_argument('--manifest', type=Path, required=True)
    select_parser.add_argument('--run-id', required=True)
    select_parser.add_argument('--head-sha', required=True)
    select_parser.add_argument('--require', type=parse_requirement, action='append', required=True)
    select_parser.add_argument('--github-output', type=Path)
    select_parser.add_argument('--require-success', action='store_true')
    download_parser = commands.add_parser('download')
    download_parser.add_argument('--manifest', type=Path, required=True)
    download_parser.add_argument('--artifact-ids', required=True)
    download_parser.add_argument('--out', type=Path, required=True)
    download_parser.add_argument('--layout', choices=('flat', 'named'), required=True)
    args = parser.parse_args()
    try:
        if args.command == 'fetch':
            token = os.getenv('GITHUB_TOKEN')
            if not token:
                raise ArtifactError('Missing GitHub Actions token')
            manifest = fetch_manifest(repository=args.repository, run_id=args.run_id, token=token)
            args.out.write_text(json.dumps(manifest, sort_keys=True) + '\n')
            return 0
        if args.command == 'download':
            token = os.getenv('GITHUB_TOKEN')
            if not token:
                raise ArtifactError('Missing GitHub Actions token')
            download(json.loads(args.manifest.read_text()), args.artifact_ids, args.out, args.layout, token)
            return 0
        selections = select(json.loads(args.manifest.read_text()), run_id=args.run_id, head_sha=args.head_sha,
                            requirements=args.require, require_success=args.require_success)
        ids = ','.join(str(artifact['id']) for artifact in selections)
        print(ids)
        if args.github_output:
            with args.github_output.open('a') as output:
                output.write(f'artifact_ids={ids}\n')
        return 0
    except (OSError, json.JSONDecodeError, ArtifactError) as error:
        print(f'Artifact selection failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
