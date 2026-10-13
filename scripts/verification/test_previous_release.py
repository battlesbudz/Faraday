import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from previous_release import (
    MAX_APK_BYTES, PreviousReleaseError, download_apk, fetch,
    releases, request_json, select_release,
)


REPOSITORY = 'battlesbudz/Jarvis-OS-V2'
PREFIX = 'audio-pr2-pr6-build.'
APK = b'PK\x03\x04a published signed APK fixture'


def release(build, **changes):
    value = {'id': build, 'tag_name': f'{PREFIX}{build}', 'draft': False,
             'published_at': '2026-10-03T04:00:00Z', 'assets': [
                 {'id': build * 10, 'name': 'app-release.apk', 'size': len(APK),
                  'state': 'uploaded', 'digest': 'sha256:' + hashlib.sha256(APK).hexdigest(),
                  'url': f'https://api.github.com/repos/{REPOSITORY}/releases/assets/{build * 10}'}]}
    value.update(changes)
    return value


def select(values, before=910):
    return select_release(values, repository=REPOSITORY, tag_prefix=PREFIX,
                          before_build=before)


class SelectionTest(unittest.TestCase):
    def test_build_number_is_authoritative_and_current_future_foreign_tags_are_excluded(self):
        values = [release(902), release(999), release(907), release(910),
                  release(908, tag_name='v0.1.0-build.908'),
                  release(909, tag_name='audio-pr2-pr7-build.909')]
        self.assertEqual(907, select(values)['build'])

    def test_missing_or_invalid_integrity_metadata_cannot_be_upgrade_baseline(self):
        for changes in ({'digest': None}, {'digest': 'sha256:' + 'g' * 64},
                        {'digest': 'sha1:' + 'a' * 40}, {'size': 0}, {'size': True},
                        {'size': MAX_APK_BYTES + 1}, {'state': 'new'},
                        {'url': 'https://example.invalid/app-release.apk'},
                        {'url': f'https://api.github.com/repos/other/repo/releases/assets/9090'}):
            broken = release(909)
            broken['assets'][0].update(changes)
            with self.subTest(changes=changes):
                self.assertEqual(907, select([broken, release(907)])['build'])

    def test_draft_unpublished_or_non_numbered_releases_are_excluded(self):
        for changes in ({'draft': True}, {'draft': None}, {'published_at': None},
                        {'published_at': 'not a timestamp'},
                        {'published_at': '2026-99-03T04:00:00Z'},
                        {'tag_name': PREFIX + '909-rc1'},
                        {'tag_name': PREFIX + '0909'}, {'tag_name': PREFIX + '909/../../'}):
            with self.subTest(changes=changes):
                self.assertEqual(907, select([release(909, **changes), release(907)])['build'])

    def test_missing_baseline_fails_without_cross_namespace_substitution(self):
        with self.assertRaisesRegex(PreviousReleaseError, 'No earlier'):
            select([release(907, tag_name='v0.1.0-build.907')])

    def test_duplicate_release_tags_or_apk_names_fail_closed(self):
        with self.assertRaisesRegex(PreviousReleaseError, 'Ambiguous earlier'):
            select([release(907), release(907, id=1907)])
        value = release(907)
        value['assets'].append(copy.deepcopy(value['assets'][0]))
        with self.assertRaisesRegex(PreviousReleaseError, 'Ambiguous release APK'):
            select([value])

    def test_rejects_invalid_scope_arguments(self):
        for changes in ({'repository': '../repo'}, {'tag_prefix': '../build.'},
                        {'tag_prefix': 'audio-pr2-pr6-build'}, {'before_build': True},
                        {'before_build': 0}):
            kwargs = {'repository': REPOSITORY, 'tag_prefix': PREFIX, 'before_build': 910}
            kwargs.update(changes)
            with self.subTest(changes=changes), self.assertRaises(PreviousReleaseError):
                select_release([release(907)], **kwargs)

    def test_paginated_selection_reads_past_first_page(self):
        first = [release(number, tag_name=f'unrelated.{number}') for number in range(1, 101)]
        calls = []
        with patch('previous_release.request_json', side_effect=lambda url, token:
                   calls.append(url) or (first if len(calls) == 1 else [release(907)])):
            selected = select(releases(REPOSITORY, 'token'))
        self.assertEqual(907, selected['build'])
        self.assertEqual(f'https://api.github.com/repos/{REPOSITORY}/releases?per_page=100&page=2', calls[1])

    def test_inconsistent_pagination_fails_closed(self):
        first = [release(number) for number in range(1, 101)]
        with patch('previous_release.request_json', side_effect=[first, [first[0]]]):
            with self.assertRaisesRegex(PreviousReleaseError, 'Duplicate release'):
                releases(REPOSITORY, 'token')
        with patch('previous_release.request_json', return_value={'releases': []}):
            with self.assertRaisesRegex(PreviousReleaseError, 'metadata page'):
                releases(REPOSITORY, 'token')
        with patch('previous_release.MAX_PAGES', 1), patch('previous_release.request_json', return_value=first):
            with self.assertRaisesRegex(PreviousReleaseError, 'pagination exceeds'):
                releases(REPOSITORY, 'token')


class Response:
    def __init__(self, data=b'', status=200, headers=None):
        self.stream, self.status, self.headers = io.BytesIO(data), status, headers or {}

    def read(self, size=-1):
        return self.stream.read(size)

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        return False


class DownloadTest(unittest.TestCase):
    def invoke(self, destination, data=APK, redirect='https://release-assets.githubusercontent.com/apk?signature=fixture', length=None):
        requests = []
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                if len(requests) == 1:
                    return Response(status=302, headers={'Location': redirect})
                return Response(data, headers={} if length is None else {'Content-Length': str(length)})
        with patch('previous_release.build_opener', return_value=Opener()):
            download_apk(select([release(907)]), destination, 'secret-token')
        return requests

    def test_digest_size_and_token_boundary_are_verified(self):
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / 'app-release.apk'
            requests = self.invoke(destination, length=len(APK))
            self.assertEqual(APK, destination.read_bytes())
        self.assertEqual('Bearer secret-token', requests[0].get_header('Authorization'))
        self.assertIsNone(requests[1].get_header('Authorization'))

    def test_api_may_return_bytes_directly_without_storage_redirect(self):
        calls = []
        class Opener:
            def open(self, request, timeout):
                calls.append(request)
                return Response(APK)
        with tempfile.TemporaryDirectory() as folder, patch('previous_release.build_opener', return_value=Opener()):
            destination = Path(folder) / 'app-release.apk'
            download_apk(select([release(907)]), destination, 'token')
            self.assertEqual(APK, destination.read_bytes())
        self.assertEqual(1, len(calls))

    def test_redirect_credentials_or_unapproved_hosts_are_rejected_before_download(self):
        for redirect in ('http://release-assets.githubusercontent.com/apk',
                         'https://release-assets.githubusercontent.com.evil.invalid/apk',
                         'https://attacker.invalid/apk',
                         'https://user:password@release-assets.githubusercontent.com/apk',
                         'https://release-assets.githubusercontent.com:444/apk',
                         'https://release-assets.githubusercontent.com:notaport/apk'):
            with tempfile.TemporaryDirectory() as folder, self.subTest(redirect=redirect):
                destination = Path(folder) / 'app-release.apk'
                with self.assertRaisesRegex(PreviousReleaseError, 'Unsafe release APK download redirect'):
                    self.invoke(destination, redirect=redirect)
                self.assertFalse(destination.exists())

    def test_rejects_truncated_oversized_wrong_digest_and_wrong_content_length(self):
        for data, length, message in ((APK[:-1], None, 'size mismatch'),
                                      (APK + b'X', None, 'size mismatch'),
                                      (b'X' * len(APK), None, 'digest mismatch'),
                                      (APK, len(APK) + 1, 'size mismatch')):
            with tempfile.TemporaryDirectory() as folder, self.subTest(message=message):
                with self.assertRaisesRegex(PreviousReleaseError, message):
                    self.invoke(Path(folder) / 'app-release.apk', data=data, length=length)

    def test_existing_apk_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / 'app-release.apk'
            destination.write_bytes(b'preserve me')
            with self.assertRaises(PreviousReleaseError):
                self.invoke(destination)
            self.assertEqual(b'preserve me', destination.read_bytes())

    def test_no_metadata_token_is_forwarded_on_redirect(self):
        class Opener:
            def open(self, request, timeout):
                raise HTTPError(request.full_url, 302, 'Found',
                                {'Location': 'https://attacker.invalid'}, None)
        with patch('previous_release.build_opener', return_value=Opener()):
            with self.assertRaisesRegex(PreviousReleaseError, 'HTTP 302'):
                request_json(f'https://api.github.com/repos/{REPOSITORY}/releases', 'token')

    def test_metadata_token_cannot_be_sent_to_an_unrelated_server(self):
        with patch('previous_release.build_opener') as opener:
            with self.assertRaisesRegex(PreviousReleaseError, 'Unsafe GitHub release API URL'):
                request_json('https://attacker.invalid/repos/owner/repo/releases', 'token')
            opener.return_value.open.assert_not_called()

    def test_fetch_writes_only_verified_metadata_and_cleans_partial_download(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'baseline'
            def download(selected, destination, token):
                destination.write_bytes(APK)
            with patch('previous_release.releases', return_value=[release(907)]), patch(
                    'previous_release.download_apk', side_effect=download):
                value = fetch(repository=REPOSITORY, tag_prefix=PREFIX, before_build=910,
                              output=output, token='secret-token')
            metadata = json.loads((output / 'previous-release.json').read_text())
            self.assertEqual(value, metadata)
            self.assertEqual(hashlib.sha256(APK).hexdigest(), metadata['sha256'])
            self.assertNotIn('secret-token', json.dumps(metadata))
            with patch('previous_release.releases') as request:
                with self.assertRaises(FileExistsError):
                    fetch(repository=REPOSITORY, tag_prefix=PREFIX, before_build=910,
                          output=output, token='token')
                request.assert_not_called()
            failed = Path(folder) / 'failed'
            def broken(selected, destination, token):
                destination.write_bytes(b'partial')
                raise PreviousReleaseError('bad digest')
            with patch('previous_release.releases', return_value=[release(907)]), patch(
                    'previous_release.download_apk', side_effect=broken):
                with self.assertRaisesRegex(PreviousReleaseError, 'bad digest'):
                    fetch(repository=REPOSITORY, tag_prefix=PREFIX, before_build=910,
                          output=failed, token='token')
            self.assertFalse(failed.exists())


if __name__ == '__main__':
    unittest.main()
