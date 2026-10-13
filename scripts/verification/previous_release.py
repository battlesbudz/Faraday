#!/usr/bin/env python3
"""Fetch a digest-verified earlier numbered release APK for a real upgrade test.

The caller supplies the release namespace. Never substitute an APK from another
branch, an Actions artifact, or an unsigned rebuild when no baseline is available.
The Android harness must additionally compare the installed/candidate signatures.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


API_ROOT = 'https://api.github.com'
MAX_METADATA_BYTES = 4 * 1024 * 1024
MAX_APK_BYTES = 1024 * 1024 * 1024
MAX_PAGES = 100
CHUNK_BYTES = 1024 * 1024
REPOSITORY = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}')
TAG_PREFIX = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,119}build\.')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')


class PreviousReleaseError(ValueError):
    """A safe, unambiguous upgrade baseline could not be established."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def positive_integer(value, label):
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise PreviousReleaseError(f'Invalid {label}')
    return value


def validate_inputs(repository, tag_prefix, before_build):
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        raise PreviousReleaseError('Invalid repository')
    if not isinstance(tag_prefix, str) or not TAG_PREFIX.fullmatch(tag_prefix):
        raise PreviousReleaseError('Invalid numbered-release tag prefix')
    positive_integer(before_build, 'current build number')


def asset_url_is_safe(url, repository, asset_id):
    if not isinstance(url, str):
        return False
    parsed = urlparse(url)
    return (parsed.scheme == 'https' and parsed.netloc == 'api.github.com' and
            parsed.path == f'/repos/{repository}/releases/assets/{asset_id}' and
            not parsed.params and not parsed.query and not parsed.fragment)


def storage_redirect_is_safe(url):
    if not isinstance(url, str):
        return False
    parsed = urlparse(url)
    try:
        return (parsed.scheme == 'https' and parsed.port in (None, 443) and
                not parsed.username and not parsed.password and not parsed.params and
                not parsed.fragment and parsed.hostname in (
                    'release-assets.githubusercontent.com', 'objects.githubusercontent.com'))
    except ValueError:
        return False


def github_request(url, token, accept='application/vnd.github+json'):
    parsed = urlparse(url)
    if (parsed.scheme != 'https' or parsed.netloc != 'api.github.com' or
            not parsed.path.startswith('/repos/') or parsed.params or parsed.fragment):
        raise PreviousReleaseError('Unsafe GitHub release API URL')
    return Request(url, headers={'Accept': accept, 'Authorization': f'Bearer {token}',
                                 'X-GitHub-Api-Version': '2022-11-28'})


def request_json(url, token):
    # Metadata must stay on the GitHub API. urllib's default redirect handler
    # retains Authorization, so it must never follow a metadata redirect.
    opener = build_opener(NoRedirect)
    try:
        with opener.open(github_request(url, token), timeout=30) as response:
            if response.status != 200:
                raise PreviousReleaseError('Release metadata did not return HTTP 200')
            length = response.headers.get('Content-Length')
            if length is not None and (int(length) < 0 or int(length) > MAX_METADATA_BYTES):
                raise PreviousReleaseError('Release metadata is oversized')
            data = response.read(MAX_METADATA_BYTES + 1)
            if len(data) > MAX_METADATA_BYTES:
                raise PreviousReleaseError('Release metadata is oversized')
            return json.loads(data)
    except PreviousReleaseError:
        raise
    except HTTPError as error:
        raise PreviousReleaseError(f'Release metadata request failed (HTTP {error.code})') from error
    except (URLError, ValueError, OSError) as error:
        raise PreviousReleaseError('Release metadata request failed') from error


def releases(repository, token):
    """Read bounded pagination; publication order is not build-number order."""
    if not REPOSITORY.fullmatch(repository):
        raise PreviousReleaseError('Invalid repository')
    result, identifiers = [], set()
    for page in range(1, MAX_PAGES + 1):
        query = urlencode({'per_page': 100, 'page': page})
        payload = request_json(f'{API_ROOT}/repos/{repository}/releases?{query}', token)
        if not isinstance(payload, list) or len(payload) > 100:
            raise PreviousReleaseError('Invalid release metadata page')
        for release in payload:
            if not isinstance(release, dict):
                raise PreviousReleaseError('Invalid release metadata')
            identifier = positive_integer(release.get('id'), 'release ID')
            if identifier in identifiers:
                raise PreviousReleaseError('Duplicate release across metadata pages')
            identifiers.add(identifier)
            result.append(release)
        if len(payload) < 100:
            return result
    raise PreviousReleaseError('Release pagination exceeds safety limit')


def select_release(values, *, repository, tag_prefix, before_build):
    """Choose the newest earlier build with one intact published release APK."""
    validate_inputs(repository, tag_prefix, before_build)
    if not isinstance(values, list):
        raise PreviousReleaseError('Invalid release metadata collection')
    numbered = re.compile(re.escape(tag_prefix) + r'([1-9][0-9]*)')
    eligible = []
    for release in values:
        if not isinstance(release, dict) or release.get('draft') is not False:
            continue
        tag = release.get('tag_name')
        match = numbered.fullmatch(tag) if isinstance(tag, str) else None
        if not match:
            continue
        build = int(match.group(1))
        if build >= before_build:
            continue
        published_at = release.get('published_at')
        # GitHub supplies an RFC3339 UTC timestamp. Missing publication evidence
        # must not silently turn a draft/placeholder into an upgrade baseline.
        if not isinstance(published_at, str) or not re.fullmatch(
                r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', published_at):
            continue
        try:
            datetime.fromisoformat(published_at.replace('Z', '+00:00'))
        except ValueError:
            continue
        assets = release.get('assets')
        if not isinstance(assets, list):
            continue
        apks = [asset for asset in assets if isinstance(asset, dict) and
                asset.get('name') == 'app-release.apk']
        if len(apks) > 1:
            raise PreviousReleaseError(f'Ambiguous release APK: {tag}')
        if not apks:
            continue
        asset = apks[0]
        try:
            release_id = positive_integer(release.get('id'), 'release ID')
            asset_id = positive_integer(asset.get('id'), 'release asset ID')
            size = positive_integer(asset.get('size'), 'release APK size')
        except PreviousReleaseError:
            continue
        digest = asset.get('digest')
        if (size > MAX_APK_BYTES or asset.get('state') != 'uploaded' or
                not isinstance(digest, str) or not DIGEST.fullmatch(digest) or
                not asset_url_is_safe(asset.get('url'), repository, asset_id)):
            continue
        eligible.append({'repository': repository, 'tag': tag, 'build': build,
                         'release_id': release_id, 'asset_id': asset_id,
                         'size_bytes': size, 'sha256': digest.removeprefix('sha256:'),
                         'published_at': published_at, 'asset_url': asset['url']})
    if not eligible:
        raise PreviousReleaseError('No earlier digest-verified release APK in the requested tag namespace')
    newest = max(value['build'] for value in eligible)
    winners = [value for value in eligible if value['build'] == newest]
    if len(winners) != 1:
        raise PreviousReleaseError('Ambiguous earlier numbered release')
    return winners[0]


def copy_verified(response, output, expected_size, expected_digest):
    length = response.headers.get('Content-Length')
    if length is not None and int(length) != expected_size:
        raise PreviousReleaseError('Release APK download size mismatch')
    total, digest = 0, hashlib.sha256()
    with output.open('xb') as destination:
        while True:
            block = response.read(min(CHUNK_BYTES, expected_size - total + 1))
            if not block:
                break
            total += len(block)
            if total > expected_size:
                raise PreviousReleaseError('Release APK download size mismatch')
            digest.update(block)
            destination.write(block)
    if total != expected_size:
        raise PreviousReleaseError('Release APK download size mismatch')
    if digest.hexdigest() != expected_digest:
        raise PreviousReleaseError('Release APK download digest mismatch')


def download_apk(selected, output, token):
    """Send the token only to the same-repository asset API, never to storage."""
    repository, asset_id = selected['repository'], selected['asset_id']
    if (not asset_url_is_safe(selected['asset_url'], repository, asset_id) or
            not isinstance(selected['sha256'], str) or
            not DIGEST.fullmatch('sha256:' + selected['sha256']) or
            positive_integer(selected['size_bytes'], 'release APK size') > MAX_APK_BYTES):
        raise PreviousReleaseError('Invalid selected release APK')
    opener = build_opener(NoRedirect)
    try:
        try:
            response = opener.open(github_request(selected['asset_url'], token,
                                                 'application/octet-stream'), timeout=30)
        except HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise PreviousReleaseError(f'Release APK download failed (HTTP {error.code})') from error
            response = error
        with response:
            if response.status == 200:
                copy_verified(response, output, selected['size_bytes'], selected['sha256'])
                return
            if response.status not in (301, 302, 303, 307, 308):
                raise PreviousReleaseError('Invalid release APK download response')
            redirect = response.headers.get('Location')
        if not storage_redirect_is_safe(redirect):
            raise PreviousReleaseError('Unsafe release APK download redirect')
        # The second request has no credential and rejects any further redirect.
        with opener.open(Request(redirect, headers={'Accept': 'application/octet-stream'}), timeout=30) as response:
            if response.status != 200:
                raise PreviousReleaseError('Invalid release APK storage response')
            copy_verified(response, output, selected['size_bytes'], selected['sha256'])
    except PreviousReleaseError:
        raise
    except HTTPError as error:
        raise PreviousReleaseError(f'Release APK storage request failed (HTTP {error.code})') from error
    except (URLError, ValueError, OSError) as error:
        raise PreviousReleaseError('Release APK download failed') from error


def fetch(*, repository, tag_prefix, before_build, output, token):
    validate_inputs(repository, tag_prefix, before_build)
    if not token:
        raise PreviousReleaseError('Missing GitHub token')
    # Refuse an existing directory before making any requests or writing files.
    output.mkdir(parents=True, exist_ok=False)
    try:
        selected = select_release(releases(repository, token), repository=repository,
                                  tag_prefix=tag_prefix, before_build=before_build)
        download_apk(selected, output / 'app-release.apk', token)
        metadata = {key: value for key, value in selected.items() if key != 'asset_url'}
        metadata.update({'schema': 1, 'apk': 'app-release.apk',
                         'download_source': 'GitHub numbered release'})
        with (output / 'previous-release.json').open('x') as stream:
            json.dump(metadata, stream, indent=2, sort_keys=True)
            stream.write('\n')
        return metadata
    except Exception:
        shutil.rmtree(output, ignore_errors=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--tag-prefix', required=True)
    parser.add_argument('--before-build', required=True, type=int)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = fetch(repository=args.repository, tag_prefix=args.tag_prefix,
                       before_build=args.before_build, output=args.out,
                       token=os.getenv('GITHUB_TOKEN'))
        print(f"Fetched prior release {result['tag']} (SHA-256 {result['sha256']})")
        return 0
    except (OSError, PreviousReleaseError) as error:
        print(f'Previous release acquisition failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
