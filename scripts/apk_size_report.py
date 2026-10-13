#!/usr/bin/env python3
"""Report actual APK ZIP bytes, native payloads and per-entry changes; never rewrite an APK."""
import argparse
import collections
import json
from pathlib import Path
import zipfile


def inspect(apk):
    groups = collections.defaultdict(lambda: {'zip_bytes': 0, 'uncompressed_bytes': 0, 'entries': 0})
    entries = []
    with zipfile.ZipFile(apk) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            name = item.filename
            group = 'dex' if name.endswith('.dex') else name.split('/')[0] if '/' in name else 'metadata'
            entry = {'path': name, 'zip_bytes': item.compress_size, 'uncompressed_bytes': item.file_size,
                     'compression': 'stored' if item.compress_type == zipfile.ZIP_STORED else 'compressed'}
            entries.append(entry)
            groups[group]['zip_bytes'] += item.compress_size
            groups[group]['uncompressed_bytes'] += item.file_size
            groups[group]['entries'] += 1
    total = Path(apk).stat().st_size
    payload = sum(e['zip_bytes'] for e in entries)
    return {'apk_bytes': total, 'zip_overhead_bytes': total - payload,
            'groups': dict(groups), 'entries': sorted(entries, key=lambda e: (-e['zip_bytes'], e['path']))}


def markdown(report, baseline=None):
    lines = ['# APK size report', '', f"APK: {report['apk_bytes']:,} bytes ({report['apk_bytes']/1e6:.2f} MB).",
             'MB uses 1,000,000 bytes. ZIP bytes measure the download; uncompressed payload is not total installed storage.', '']
    if baseline:
        change = report['apk_bytes'] - baseline['apk_bytes']
        lines += [f"Change from baseline: {change:+,} bytes ({change/1e6:+.2f} MB).", '']
    lines += ['| Component | ZIP bytes | Uncompressed bytes |', '| --- | ---: | ---: |']
    for name, group in sorted(report['groups'].items(), key=lambda pair: -pair[1]['zip_bytes']):
        lines.append(f"| {name} | {group['zip_bytes']:,} | {group['uncompressed_bytes']:,} |")
    lines += ['', '## Largest entries', '', '| Entry | ZIP bytes | Storage | Delta from baseline |', '| --- | ---: | --- | ---: |']
    previous = {e['path']: e['zip_bytes'] for e in baseline['entries']} if baseline else {}
    for entry in report['entries'][:20]:
        delta = f"{entry['zip_bytes'] - previous.get(entry['path'], 0):+,}" if baseline else '—'
        lines.append(f"| {entry['path']} | {entry['zip_bytes']:,} | {entry['compression']} | {delta} |")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, help='JSON from an earlier report')
    args = parser.parse_args()
    report = inspect(args.apk)
    baseline = json.loads(args.baseline.read_text()) if args.baseline else None
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'apk-size.json').write_text(json.dumps(report, indent=2) + '\n')
    (args.output / 'apk-size.md').write_text(markdown(report, baseline))
    print(markdown(report, baseline))


if __name__ == '__main__':
    main()
