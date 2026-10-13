"""Source identities and bounded downloads shared by the hosted recipe."""
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.request

HERE = Path(__file__).resolve().parent
SDK_PIN = '924e79c91542761242244e4f1651851f822e4cbb'
LITERT_PIN = '0ff28117f1cb5556d0e015bf80b773f74e2bee51'
BUNDLE = dict(bytes=2588147712, sha256='181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c',
    revision='6e5c4f1e395deb959c494953478fa5cec4b8008f',
    url='https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm')
WAV = dict(bytes=295404, sha256='9e42e31cbc41cb3c31dc4ddb1dc05fef322c61b98cc03a38c06749b2db94f6b2',
           url='https://ai.google.dev/gemma/docs/audio/roses-are.wav')
PCM_SHA = 'f57adbb58a9a9ce6f198a56166b8f8f8021ff0073f7a54fc2c5171161f933c12'
MEL_SHA = '243f69cbf70e632fd44535c33ba7f8d01fb3e9907e708880d550a5c5d3cdd851'
ROWS_SHA = 'e51d19c68f19e02ea3075720674932ddcc83948f8e4c8b507047e055b06f93e9'
# The historical rows fingerprint is diagnostic, not a cross-host numeric oracle.
ENCODER_ACCEPTANCE_CONTRACT = 'same_host_original_static_stateful_observable_equivalence_v1'
PRODUCER = 'd5c50b140ace235717e6713d287e73ccfa4f32d0090e1cceb9d00714da850a1b'


class GateError(Exception):
    def __init__(self, classification, message):
        self.classification = classification
        super().__init__(message)


def need(condition, message, category='identity_failure'):
    if not condition: raise GateError(category, message)


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''): h.update(b)
    return h.hexdigest()


def canonical(value): return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()
def digest(value): return hashlib.sha256(canonical(value)).hexdigest()
def write(path, value): Path(path).write_text(json.dumps(value, indent=2)+'\n')
def load(path): return json.loads(Path(path).read_text())
def describe(path): return {'bytes': Path(path).stat().st_size, 'sha256': sha(path)}


def verify(path, pin):
    need(Path(path).is_file(), 'Required file is absent: '+str(path))
    need(describe(path) == {k: pin[k] for k in ('bytes', 'sha256')}, 'File identity changed: '+str(path))


def download(pin, output, seconds=600):
    """One bounded transfer, no auto retries, partials never become trusted inputs."""
    output = Path(output)
    if output.exists():
        verify(output, pin)
        return
    partial = output.with_name(output.name+'.partial')
    need(not partial.exists(), 'Stale partial download; use a fresh run directory')
    start = time.monotonic()
    h = hashlib.sha256()
    size = 0
    try:
        request = urllib.request.Request(pin['url'], headers={'Range': f'bytes=0-{pin["bytes"]-1}'})
        with urllib.request.urlopen(request, timeout=60) as source, partial.open('xb') as out:
            need(source.status in (200, 206), 'Unexpected download status')
            if source.status == 206:
                need(source.headers.get('Content-Range') == f'bytes 0-{pin["bytes"]-1}/{pin["bytes"]}', 'Wrong ranged download')
            for block in iter(lambda: source.read(1024*1024), b''):
                size += len(block)
                need(size <= pin['bytes'], 'Oversized download')
                out.write(block); h.update(block)
                if time.monotonic()-start > seconds: raise GateError('download_failure', 'Download wall budget exhausted')
    except (OSError, TimeoutError) as e:
        raise GateError('download_failure', str(e)) from e
    need(size == pin['bytes'] and h.hexdigest() == pin['sha256'], 'Download checksum/size mismatch')
    partial.replace(output)


def verify_recipe_sources():
    manifest = load(HERE/'SOURCE-MANIFEST.json')
    for relative, pin in manifest['files'].items():
        p = Path(relative)
        need(not p.is_absolute() and '..' not in p.parts, 'Unsafe source manifest path')
        verify(HERE/p, pin)
    return digest(manifest)


def sdk_snapshot(sdk):
    sdk = Path(sdk)
    head = subprocess.check_output(['git', '-C', str(sdk), 'rev-parse', 'HEAD'], text=True).strip()
    need(head == SDK_PIN, 'SDK HEAD differs from exact 0.16 pin')
    need(LITERT_PIN in (sdk/'WORKSPACE').read_text(), 'LiteRT dependency pin changed')
    files = subprocess.check_output(['git', '-C', str(sdk), 'ls-files', '--cached', '--others', '--exclude-standard', '-z']).decode().split('\0')
    # Every source/config input, including staged probe sources. Large prebuilt
    # binaries have separately pinned hashes; generated Bazel trees are ignored.
    identity = {p: sha(sdk/p) for p in sorted(set(files)) if p and (sdk/p).is_file()
                and not (p.startswith('prebuilt/') and p.endswith('.so'))}
    return {'sdk_commit': head, 'litert_workspace_pin': LITERT_PIN,
            'files': identity, 'workspace_sha256': sha(sdk/'WORKSPACE')}
