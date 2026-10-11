#!/usr/bin/env python3
"""Package only pinned precompiled-model v75 QNN object code and notices as gzip assets.

No ORT replacement, graph compiler, other SoCs, weights or executable downloads.
Qualcomm object code is distributed only within this application, not as an SDK.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct
import zipfile

PROVIDER_SHA256 = '169add91932e27196e0ab704f53fe8cd517af3ecee0d4a73fbf14513ab4551a6'
RUNTIME_SHA256 = 'b507656e4031d8aa6a7ab0fecf725b941096737ad7440ad7ea288f7c9cc9d743'
HOST = ('libonnxruntime_providers_qnn.so', 'libQnnSystem.so', 'libQnnHtp.so', 'libQnnHtpV75Stub.so')
DSP = 'libQnnHtpV75Skel.so'
FILES = {
    'libonnxruntime_providers_qnn.so': (4281832, '21556616b76a82a9c5d3226c2b35f275263447235f356a13b5f208e5fa469fae'),
    'libQnnSystem.so': (4068296, 'ca9e5429c77380e1c3f5a401f00288915ec2f6cc478979bafd57c665391d432f'),
    'libQnnHtp.so': (3979248, '30e2981965e687d71626c91397867da81247c639e598f49fa5d139cbb21255d0'),
    'libQnnHtpV75Stub.so': (791696, 'eca06fdf5bba9d9a291eae6d53ca46c7432e57be6c8e0565a628f2acc353b0a1'),
    'libQnnHtpV75Skel.so': (18693300, '3e9774b74769915b4f54364f8fc25887b3439561a970dca57c9f4dc9612b38af'),
}

def check_elf(data, machine):
    if data[:4] != b'\x7fELF' or data[5] != 1 or struct.unpack_from('<H', data, 18)[0] != machine:
        raise ValueError('Unexpected QNN ELF architecture')
    if machine == 183:
        if data[4] != 2: raise ValueError('QNN host library must be ELF64')
        phoff, = struct.unpack_from('<Q', data, 32)
        phentsize, phnum = struct.unpack_from('<HH', data, 54)
        loads = 0
        for index in range(phnum):
            kind, _, offset, vaddr, _, _, _, align = struct.unpack_from('<IIQQQQQQ', data, phoff + index * phentsize)
            if kind == 1:
                loads += 1
                if align < 16384 or offset % 16384 != vaddr % 16384: raise ValueError('QNN host library is not 16 KB page compatible')
        if not loads: raise ValueError('QNN host library has no load segments')

def prepare(provider, runtime, output):
    for path, digest in ((provider, PROVIDER_SHA256), (runtime, RUNTIME_SHA256)):
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest: raise ValueError('Pinned public QNN AAR checksum mismatch')
    assets = output / 'assets'
    if output.exists(): shutil.rmtree(output)
    target = assets / 'whisper-qnn-runtime'; target.mkdir(parents=True)
    entries = []
    with zipfile.ZipFile(provider) as p, zipfile.ZipFile(runtime) as r:
        for name in (*HOST, DSP):
            archive = p if name == HOST[0] else r
            data = archive.read('jni/arm64-v8a/' + name)
            if (len(data), hashlib.sha256(data).hexdigest()) != FILES[name]: raise ValueError('Pinned QNN library checksum mismatch')
            check_elf(data, 164 if name == DSP else 183)
            (target / (name + '.gz')).write_bytes(gzip.compress(data, compresslevel=9, mtime=0))
            entries.append(dict(name=name, asset=name + '.gz', bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), architecture='hexagon-v75' if name == DSP else 'aarch64'))
        licenses = assets / 'licenses/whisper-qnn'; licenses.mkdir(parents=True)
        for name in ('LICENSE.pdf', 'NOTICE.txt'): (licenses / name).write_bytes(r.read(name))
    manifest = dict(schema=1, qnn_plugin='2.6.0', qairt='2.50.0', ort='1.27.1', htp='v75', files=entries)
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'receipt.json').write_text(json.dumps(manifest | dict(provider_aar_sha256=PROVIDER_SHA256, runtime_aar_sha256=RUNTIME_SHA256), indent=2) + '\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('provider', type=Path); parser.add_argument('runtime', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args(); prepare(args.provider, args.runtime, args.output)
