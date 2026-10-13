#!/usr/bin/env python3
"""Reproduce approved Wisp launcher resources with Inkscape (developer-only).

Android builds consume checked-in PNGs; they never need Inkscape or download art.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'artwork/launcher/faraday-wisp.svg'
RES = ROOT / 'app/src/main/res'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def generate():
    source = SOURCE.read_text()
    # Preserve exact approved composition in the central 72 of adaptive 108 dp.
    # Android masks the central viewport; the remaining 18 dp is motion overscan.
    start = source.index('<g transform="translate(512 512)')
    definitions = source[source.index('<defs>'):source.index('</defs>') + 7]
    art = source[start:source.rindex('</svg>')]
    body = re.search(r'<clipPath id="body-clip"><path d="([^"]+)"', source)[1]
    face = art[art.index('<g transform="translate(66 65)'):]
    face = face[:face.rindex('</g>')]
    # Themed icons are an alpha-only silhouette with eyes/smile knocked out.
    eyes = re.findall(r'<ellipse cx="(?:57|77)"[^>]+/>', face)
    smile = re.search(r'<g transform="translate\(67 71\).*?</g>', face)[0]
    holes = ''.join(eyes) + smile
    holes = re.sub(r'fill="url\(#[^)]+\)"', 'fill="black"', holes)
    holes = holes.replace('stroke="#06394A"', 'stroke="black"')
    mono = f'<defs><mask id="face"><rect width="1024" height="1024" fill="white"/><g transform="translate(512 512) scale(7.9) translate(-72 -53)"><g transform="translate(66 65) scale(1.16) translate(-66 -65)">{holes}</g></g></mask></defs><g mask="url(#face)"><g transform="translate(512 512) scale(7.9) translate(-72 -53)"><path d="{body}" fill="white"/></g></g>'
    svg = lambda body: '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">' + body + '</svg>'
    padded = lambda body: '<g transform="translate(170.6666667 170.6666667) scale(.6666666667)">' + body + '</g>'
    variants = {
        'ic_launcher': (source, 48),
        'ic_launcher_round': (svg('<defs><clipPath id="round"><circle cx="512" cy="512" r="512"/></clipPath></defs><g clip-path="url(#round)">' + source + '</g>'), 48),
        'ic_launcher_foreground': (svg(definitions + padded(art)), 108),
        'ic_launcher_background': (svg(definitions + '<rect width="1024" height="1024" fill="url(#background)"/>'), 108),
        'ic_launcher_monochrome': (svg(padded(mono)), 108),
    }
    with tempfile.TemporaryDirectory() as temp:
        for name, (content, dp) in variants.items():
            path = Path(temp) / (name + '.svg')
            path.write_text(content)
            for density, scale in DENSITIES.items():
                out = RES / ('mipmap-' + density) / (name + '.png')
                out.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run(['inkscape', str(path), '--export-type=png', '--export-width=' + str(int(dp * scale)), '--export-height=' + str(int(dp * scale)), '--export-filename=' + str(out)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    files = [SOURCE] + sorted(RES.glob('mipmap-*/ic_launcher*.png'))
    receipt = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    (ROOT / 'artwork/launcher/sha256.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    generate()
