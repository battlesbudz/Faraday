import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import hashlib
import io
import zipfile
from urllib.error import HTTPError

from artifacts import ArtifactError, download, fetch_manifest, paged, select


SHA = 'a' * 40
RUN_ID = 35755200903
BUILD = 'Build signed release APK'
API35 = 'Verify signed release in Android sandbox / Release journeys / API 35 / app-compact'


def iso(seconds):
    return (datetime(2026, 9, 22, 16, 0, tzinfo=timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')


def manifest():
    return {
        'schema': 1, 'repository': 'owner/repo',
        'run': {'id': RUN_ID, 'head_sha': SHA, 'attempt': 2},
        'jobs': [
            {'name': BUILD, 'attempt': 1, 'run_id': RUN_ID, 'head_sha': SHA, 'status': 'completed', 'conclusion': 'success', 'started_at': iso(0), 'completed_at': iso(100)},
            # A partial rerun carries the successful build as an earlier execution.
            {'name': BUILD, 'attempt': 2, 'run_id': RUN_ID, 'head_sha': SHA, 'status': 'completed', 'conclusion': 'success', 'started_at': iso(0), 'completed_at': iso(100)},
            {'name': API35, 'attempt': 1, 'run_id': RUN_ID, 'head_sha': SHA, 'status': 'completed', 'conclusion': 'failure', 'started_at': iso(110), 'completed_at': iso(200)},
            {'name': API35, 'attempt': 2, 'run_id': RUN_ID, 'head_sha': SHA, 'status': 'completed', 'conclusion': 'success', 'started_at': iso(210), 'completed_at': iso(300)},
        ], 'artifacts': []}


def artifact(identifier, name, created, **changes):
    value = {'id': identifier, 'name': name, 'workflow_run': {'id': RUN_ID, 'head_sha': SHA},
             'created_at': iso(created), 'expired': False,
             'archive_download_url': f'https://api.github.com/repos/owner/repo/actions/artifacts/{identifier}/zip'}
    value.update(changes)
    return value


class ArtifactSelectionTest(unittest.TestCase):
    def choose(self, data, requirement=('jarvis-verification-api-35-app-compact', API35), **kwargs):
        return select(data, run_id=RUN_ID, head_sha=SHA, requirements=[requirement], **kwargs)

    def test_timestamp_beats_descending_artifact_ids_on_rerun(self):
        data = manifest()
        data['artifacts'] = [
            artifact(10708303736, 'jarvis-verification-api-35-app-compact', 198),
            artifact(10707864350, 'jarvis-verification-api-35-app-compact', 298),
        ]
        self.assertEqual([10707864350], [item['id'] for item in self.choose(data)])

    def test_prior_failure_new_pass_selects_newer_producer(self):
        data = manifest()
        data['artifacts'] = [artifact(900, 'jarvis-verification-api-35-app-compact', 198),
                             artifact(1, 'jarvis-verification-api-35-app-compact', 298)]
        self.assertEqual(1, self.choose(data)[0]['id'])

    def test_prior_pass_new_failure_is_not_replaced_by_old_pass(self):
        data = manifest()
        data['jobs'][-1]['conclusion'] = 'failure'
        data['artifacts'] = [artifact(1, 'jarvis-verification-api-35-app-compact', 198),
                             artifact(2, 'jarvis-verification-api-35-app-compact', 298)]
        self.assertEqual(2, self.choose(data)[0]['id'])
        with self.assertRaisesRegex(ArtifactError, 'Latest producer did not succeed'):
            self.choose(data, require_success=True)

    def test_missing_latest_attempt_does_not_fallback(self):
        data = manifest()
        data['artifacts'] = [artifact(1, 'jarvis-verification-api-35-app-compact', 198)]
        with self.assertRaisesRegex(ArtifactError, 'Missing current producer artifact'):
            self.choose(data)

    def test_expired_foreign_and_unsafe_urls_are_rejected(self):
        data = manifest()
        for changes in ({'expired': True}, {'workflow_run': {'id': RUN_ID + 1, 'head_sha': SHA}},
                        {'workflow_run': {'id': RUN_ID, 'head_sha': 'b' * 40}},
                        {'archive_download_url': 'https://example.invalid/archive'}):
            data['artifacts'] = [artifact(1, 'jarvis-verification-api-35-app-compact', 298, **changes)]
            with self.subTest(changes=changes), self.assertRaises(ArtifactError):
                self.choose(data)

    def test_duplicate_current_timestamp_fails_closed(self):
        data = manifest()
        data['artifacts'] = [artifact(1, 'jarvis-verification-api-35-app-compact', 298),
                             artifact(2, 'jarvis-verification-api-35-app-compact', 298)]
        with self.assertRaisesRegex(ArtifactError, 'Ambiguous'):
            self.choose(data)

    def test_manifest_run_and_revision_are_bound(self):
        data = manifest()
        data['artifacts'] = [artifact(1, 'jarvis-verification-api-35-app-compact', 298)]
        for change in ({'run': {'id': RUN_ID + 1, 'head_sha': SHA}},
                       {'run': {'id': RUN_ID, 'head_sha': 'b' * 40}}):
            wrong = copy.deepcopy(data)
            wrong.update(change)
            with self.subTest(change=change), self.assertRaises(ArtifactError):
                self.choose(wrong)

    def test_run751_retry_regression_from_portable_synthetic_metadata(self):
        source = Path(__file__).with_name('fixtures') / 'run751-retry-metadata.json'
        fixture = json.loads(source.read_text())
        data = {'schema': 1, 'repository': 'battlesbudz/Jarvis-OS-V2',
                'run': {'id': fixture['run']['id'], 'head_sha': fixture['run']['head_sha'], 'attempt': 2},
                'artifacts': [], 'jobs': []}
        data['artifacts'] = copy.deepcopy(fixture['artifacts']['artifacts'])
        for item in fixture['jobs']['jobs']:
            item = copy.deepcopy(item)
            item['attempt'] = item['run_attempt']
            data['jobs'].append(item)
        selected = select(data, run_id=data['run']['id'], head_sha=data['run']['head_sha'], requirements=[
            ('jarvis-verification-api-35-app-compact', API35)])
        self.assertEqual(10707864350, selected[0]['id'])

    def test_pagination_uses_bounded_query_urls(self):
        calls = []
        first = [{'id': number} for number in range(100)]
        with patch('artifacts.request_json', side_effect=lambda url, token: calls.append(url) or {
                'total_count': 101, 'artifacts': first if len(calls) == 1 else [{'id': 100}]}):
            values = paged('https://api.github.com', '/repos/owner/repo/actions/runs/1/artifacts', 'artifacts', 'token')
        self.assertEqual(101, len(values))
        self.assertEqual('https://api.github.com/repos/owner/repo/actions/runs/1/artifacts?per_page=100&page=1', calls[0])
        self.assertEqual('https://api.github.com/repos/owner/repo/actions/runs/1/artifacts?per_page=100&page=2', calls[1])

    def test_fetch_rejects_foreign_artifact_association_instead_of_rewriting_it(self):
        run = {'id': RUN_ID, 'head_sha': SHA, 'run_attempt': 1,
               'repository': {'full_name': 'owner/repo'}}
        foreign = artifact(1, 'jarvis-os-v2-release-apk', 90,
                           workflow_run={'id': RUN_ID + 1, 'head_sha': SHA})
        job = {'id': 1, 'run_id': RUN_ID, 'head_sha': SHA, 'run_attempt': 1}
        with patch('artifacts.request_json', side_effect=[
                run, {'total_count': 1, 'artifacts': [foreign]},
                {'total_count': 1, 'jobs': [job]}]):
            with self.assertRaisesRegex(ArtifactError, 'Artifact belongs'):
                fetch_manifest(repository='owner/repo', run_id=RUN_ID, token='token')


class Response:
    def __init__(self, data=b'', status=200, headers=None):
        self.data, self.status, self.headers = data, status, headers or {}

    def read(self, size=-1):
        return self.data if size < 0 else self.data[:size]

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        return False


class DownloadTest(unittest.TestCase):
    def archive(self, files):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, contents in files.items():
                archive.writestr(name, contents)
        return stream.getvalue()

    def metadata(self, data, name='jarvis-os-v2-release-apk', identifier=10707864350):
        return {'repository': 'owner/repo', 'artifacts': [
            {'id': identifier, 'name': name, 'size_in_bytes': len(data),
             'digest': 'sha256:' + hashlib.sha256(data).hexdigest(),
             'archive_download_url': f'https://api.github.com/repos/owner/repo/actions/artifacts/{identifier}/zip'}]}

    def invoke(self, manifest, data, output, layout='flat', redirect='https://pipelines.actions.githubusercontent.com/archive'):
        requests = []
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                return Response(status=302, headers={'Location': redirect})
        with patch('artifacts.build_opener', return_value=Opener()), patch('artifacts.urlopen', side_effect=lambda request, timeout: requests.append(request) or Response(data, headers={'Content-Length': str(len(data))})):
            download(manifest, str(manifest['artifacts'][0]['id']), output, layout, 'secret-token')
        return requests

    def test_lower_retry_id_downloads_verified_flat_apk_without_token_redirect(self):
        data = self.archive({'app-release.apk': b'apk'})
        with tempfile.TemporaryDirectory() as root:
            requests = self.invoke(self.metadata(data), data, Path(root) / 'dist')
            self.assertEqual(b'apk', (Path(root) / 'dist' / 'app-release.apk').read_bytes())
        self.assertEqual('Bearer secret-token', requests[0].get_header('Authorization'))
        self.assertIsNone(requests[1].get_header('Authorization'))

    def test_actual_no_redirect_handler_http_error_is_followed_without_token(self):
        data = self.archive({'app-release.apk': b'apk'})
        requests = []
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                raise HTTPError(request.full_url, 302, 'Found', {'Location': 'https://pipelines.actions.githubusercontent.com/archive'}, None)
        with tempfile.TemporaryDirectory() as root, patch('artifacts.build_opener', return_value=Opener()), patch('artifacts.urlopen', side_effect=lambda request, timeout: requests.append(request) or Response(data, headers={'Content-Length': str(len(data))})):
            download(self.metadata(data), '10707864350', Path(root) / 'dist', 'flat', 'secret-token')
        self.assertIsNone(requests[1].get_header('Authorization'))

    def test_namespaced_receipt_layout_and_hash_mismatch_fail_closed(self):
        data = self.archive({'receipt.json': b'{}'})
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'inputs'
            self.invoke(self.metadata(data, 'jarvis-verification-receipt'), data, output, 'named')
            self.assertEqual(b'{}', (output / 'jarvis-verification-receipt' / 'receipt.json').read_bytes())
            wrong = self.metadata(data)
            wrong['artifacts'][0]['digest'] = 'sha256:' + '0' * 64
            with self.assertRaisesRegex(ArtifactError, 'digest'):
                self.invoke(wrong, data, Path(root) / 'bad')
            self.assertFalse((Path(root) / 'bad').exists())

    def test_rejects_unsafe_zip_and_redirect(self):
        data = self.archive({'../escape': b'x'})
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ArtifactError, 'ZIP'):
                self.invoke(self.metadata(data), data, Path(root) / 'unsafe')
            self.assertFalse((Path(root) / 'unsafe').exists())
            safe = self.archive({'app-release.apk': b'apk'})
            with self.assertRaisesRegex(ArtifactError, 'redirect'):
                self.invoke(self.metadata(safe), safe, Path(root) / 'redirect', redirect='https://example.invalid/file')


if __name__ == '__main__':
    unittest.main()
