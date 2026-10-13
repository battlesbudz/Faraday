#!/usr/bin/env python3
"""Fail builds if approved launcher artwork is missing, stale or overridden."""
from pathlib import Path
import hashlib
import json
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
NAMES = {'ic_launcher': 48, 'ic_launcher_round': 48, 'ic_launcher_foreground': 108,
         'ic_launcher_background': 108, 'ic_launcher_monochrome': 108}


def check(root=ROOT):
    res = root / 'app/src/main/res'
    app = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot().find('application')
    for attr, name in [('icon', 'ic_launcher'), ('roundIcon', 'ic_launcher_round')]:
        if app.get(ANDROID + attr) != '@mipmap/' + name:
            raise ValueError('Main application must use approved ' + attr)
    expected = {'artwork/launcher/faraday-wisp.svg'}
    for density, scale in DENSITIES.items():
        for name, dp in NAMES.items():
            p = res / ('mipmap-' + density) / (name + '.png')
            expected.add(str(p.relative_to(root)))
            data = p.read_bytes()
            if data[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', data[16:24]) != (int(dp * scale),) * 2:
                raise ValueError('Invalid launcher PNG: ' + str(p))
    receipt = json.loads((root / 'artwork/launcher/sha256.json').read_text())
    if set(receipt) != expected:
        raise ValueError('Launcher receipt must cover exactly source and all PNGs')
    for name, digest in receipt.items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Stale launcher artwork: ' + name)
    for level in (26, 33):
        for name in ('ic_launcher', 'ic_launcher_round'):
            icon = ET.parse(res / f'mipmap-anydpi-v{level}/{name}.xml').getroot()
            expected_layers = ['background', 'foreground'] + (['monochrome'] if level == 33 else [])
            if icon.tag != 'adaptive-icon' or [x.tag for x in icon] != expected_layers:
                raise ValueError('Invalid adaptive launcher layers')
            for layer in icon:
                if layer.get(ANDROID + 'drawable') != '@mipmap/ic_launcher_' + layer.tag:
                    raise ValueError('Wrong adaptive launcher layer: ' + layer.tag)
    for source in (root / 'app/src').iterdir():
        if not source.is_dir():
            continue
        if source.name != 'main':
            if any((source / 'res').glob('**/ic_launcher*')):
                raise ValueError('Source-set launcher override: ' + source.name)
        manifest = source / 'AndroidManifest.xml'
        if manifest.exists():
            application = ET.parse(manifest).getroot().find('application')
            if application is not None:
                for node in application.iter():
                    for attr in ('icon', 'roundIcon'):
                        if node.get(ANDROID + attr) is not None and not (source.name == 'main' and node is application):
                            raise ValueError('Manifest launcher override: ' + source.name)
    return 'Approved Wisp launcher resources verified for all source sets.'


if __name__ == '__main__':
    print(check())
