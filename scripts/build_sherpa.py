#!/usr/bin/env python3
"""Build the pinned Sherpa app JNI profile for Piper, Whisper, VAD and speaker checks."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

from sherpa_jni_profile import configure

ROOT = Path(__file__).resolve().parents[1]
REV = '917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e'  # v1.13.7
SOURCE = (f'https://github.com/k2-fsa/sherpa-onnx/archive/{REV}.tar.gz',
          'acf539e930283442c4237b7b23a06ebe3bff10cbc00694a4f09a3580e3c10e9e')
ORT_ANDROID = ('https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.27.1/onnxruntime-android-1.27.1.zip',
               'defade26209f72cf4fa9769b18052c842833d6bef12924595d26f03b995548ca')
ORT_HOST = ('https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.27.1/onnxruntime-linux-x64-glibc2_17-Release-1.27.1.zip',
            '3b49aa3cded130124e1822c9683f0450bd120390f14a626369fd7a02f5b5f64e')


def download(spec, directory):
    url, expected = spec
    dest = directory / url.rsplit('/', 1)[1]
    if not dest.exists() or hashlib.sha256(dest.read_bytes()).hexdigest() != expected:
        tmp = dest.with_suffix('.part')
        urllib.request.urlretrieve(url, tmp)
        if hashlib.sha256(tmp.read_bytes()).hexdigest() != expected:
            raise RuntimeError(f'Checksum mismatch: {url}')
        tmp.replace(dest)
    return dest


def source_fingerprint(root=ROOT):
    """Bind native source names and bytes as well as the profile, not just upstream revision."""
    sources = []
    for name in ('smartturn', 'whisperqnn'):
        source_dir = root / 'app/src/main/cpp' / name
        sources.extend(sorted(path for path in source_dir.iterdir() if path.is_file()))
    if not sources:
        raise ValueError('Missing app Smart Turn native sources')
    digest = hashlib.sha256()
    for path in [root / 'scripts/sherpa_jni_profile.py', *sources]:
        digest.update(path.relative_to(root).as_posix().encode() + b'\0')
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return 'app-jni-smartturn-whisper-qnn-v2-' + digest.hexdigest()


def build(output, ndk=None):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    source = output / 'source'
    fingerprint = source_fingerprint()
    stamp = output / 'source-version'
    if not stamp.exists() or stamp.read_text() != REV + fingerprint:
        shutil.rmtree(source, ignore_errors=True)
        with tarfile.open(download(SOURCE, output)) as archive:
            archive.extractall(output, members=(m for m in archive if m.isfile() or m.isdir()), filter='data')
        (output / f'sherpa-onnx-{REV}').rename(source)
        stamp.write_text(REV + fingerprint)
    if ndk:
        configure(source)
    ort = output / ('ort-android' if ndk else 'ort-host')
    if not ort.exists():
        with zipfile.ZipFile(download(ORT_ANDROID if ndk else ORT_HOST, output)) as archive:
            archive.extractall(ort)
    if ndk:
        include = ort / 'headers'
        lib = ort / 'jni/arm64-v8a'
    else:
        include = next(ort.rglob('onnxruntime_cxx_api.h')).parent
        lib = next(ort.rglob('libonnxruntime.so')).parent
    env = dict(os.environ, SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(include), SHERPA_ONNXRUNTIME_LIB_DIR=str(lib))
    cmake_dir = output / 'cmake'
    command = ['cmake', '-S', str(source), '-B', str(cmake_dir), '-DCMAKE_BUILD_TYPE=Release',
               '-DBUILD_SHARED_LIBS=OFF', '-DCMAKE_C_FLAGS=-ffunction-sections -fdata-sections',
               '-DCMAKE_CXX_FLAGS=-ffunction-sections -fdata-sections',
               '-DCMAKE_SHARED_LINKER_FLAGS=' + ('-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384' if ndk else '-Wl,--gc-sections'),
               '-DSHERPA_ONNX_ENABLE_BINARY=OFF',
               '-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF', '-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF',
               '-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF',
               '-DSHERPA_ONNX_ENABLE_C_API=' + ('OFF' if ndk else 'ON'),
               '-DSHERPA_ONNX_ENABLE_JNI=' + ('ON' if ndk else 'OFF')]
    if ndk:
        command += [f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
                    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29',
                    '-DANDROID_STL=c++_shared', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON']
    subprocess.run(command, env=env, check=True)
    target = 'sherpa-onnx-jni' if ndk else 'sherpa-onnx-c-api'
    subprocess.run(['cmake', '--build', str(cmake_dir), '--target', target,
                    '--parallel', os.environ.get('JARVIS_NATIVE_JOBS', '2')], env=env, check=True)
    if ndk:
        native = output / 'jni/arm64-v8a'
        native.mkdir(parents=True, exist_ok=True)
        shutil.copy2(next(cmake_dir.rglob('libsherpa-onnx-jni.so')), native)
        shutil.copy2(lib / 'libonnxruntime.so', native)
        # This standalone CMake build uses c++_shared; Gradle does not discover
        # its STL dependency when consuming the result through jniLibs.
        runtimes = list((ndk / 'toolchains/llvm/prebuilt').glob(
            '*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'))
        if len(runtimes) != 1:
            raise RuntimeError(f'Expected one ARM64 C++ runtime in {ndk}, found {len(runtimes)}')
        shutil.copy2(runtimes[0], native)
    print(f'Upstream Sherpa ready: {output}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--android-ndk', type=Path)
    args = parser.parse_args()
    if args.android_ndk and not (args.android_ndk / 'build/cmake/android.toolchain.cmake').is_file():
        parser.error('Install Android NDK 27.2.12479018 with sdkmanager before building.')
    build(args.output, args.android_ndk)
