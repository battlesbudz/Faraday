#!/usr/bin/env python3
"""Assemble the ONNX Runtime 1.27.1 Java API + JNI bridge for the vision module.

The vision module (:phoneinference) needs the ORT Java API, but Microsoft never
published onnxruntime-android 1.27.1 to Maven Central. The APK already ships
Sherpa's libonnxruntime.so (1.27.1), so we need only:
  1. classes.jar -- the ai.onnxruntime.* Java API. Microsoft published 1.27.0
     to Maven Central, and the v1.27.0...v1.27.1 diff touches ZERO Java files
     (1.27.1 was a native-only patch), so the 1.27.0 JAR is the 1.27.1 API.
  2. libonnxruntime4j_jni.so -- the JNI bridge, taken prebuilt from the
     csukuangfj 1.27.1 release (same release Sherpa's runtime comes from;
     verified: DT_NEEDED libonnxruntime.so, requires OrtGetApiBase@VERS_1.27.1).

At runtime the bridge resolves Sherpa's libonnxruntime.so from the app's native
library directory -- this module packages NO private ORT copy.

Pinned inputs:
  ORT Java API: com.microsoft.onnxruntime:onnxruntime:1.27.0 from Maven Central
                (Java API identical to 1.27.1 -- verified via GitHub compare)
  ORT Android libs: csukuangfj/onnxruntime-libs v1.27.1
                   (same zip + sha256 that scripts/build_sherpa.py uses)

Outputs (under --output):
  classes.jar                          -- ai.onnxruntime.* Java API (1.27.x)
  jni/arm64-v8a/libonnxruntime4j_jni.so -- prebuilt JNI bridge, DT_NEEDED libonnxruntime.so

Follows scripts/build_sherpa.py conventions: pinned downloads with sha256
where available, a stamp file to skip redundant rebuilds.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import urllib.request
import zipfile

ORT_VERSION = '1.27.1'
# Pure-Java ORT API from Maven Central. 1.27.1 was never published, but the
# v1.27.0...v1.27.1 compare shows zero Java file changes (native-only patch).
ORT_JAVA_JAR = ('https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.27.0/onnxruntime-1.27.0.jar',
                None)  # no pinned sha256; Maven Central is content-addressed by version
# Same pinned zip + hash as scripts/build_sherpa.py (Sherpa's ORT 1.27.1).
ORT_ANDROID = ('https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.27.1/onnxruntime-android-1.27.1.zip',
               'defade26209f72cf4fa9769b18052c842833d6bef12924595d26f03b995548ca')

ABI = 'arm64-v8a'


def download(spec, directory):
    url, expected = spec
    dest = directory / url.rsplit('/', 1)[1]
    if dest.exists() and (expected is None or hashlib.sha256(dest.read_bytes()).hexdigest() == expected):
        return dest
    tmp = dest.with_suffix('.part')
    print(f'Downloading {url}', flush=True)
    urllib.request.urlretrieve(url, tmp)
    if expected is not None and hashlib.sha256(tmp.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f'Checksum mismatch: {url}')
    tmp.replace(dest)
    return dest


def build(output):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    # Stamp: rebuild when this script changes (mirrors build_sherpa.py).
    fingerprint = 'vision-ort-jni-v4-' + hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    stamp = output / 'build-stamp'
    if stamp.exists() and stamp.read_text() == ORT_VERSION + fingerprint:
        print('Vision ORT JNI up to date, skipping build.', flush=True)
        return
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True, exist_ok=True)

    work = output / 'work'
    work.mkdir(parents=True)

    # 1. Java API: the official 1.27.0 JAR from Maven Central.
    #    (Java API identical to 1.27.1 -- verified zero Java changes in the
    #    v1.27.0...v1.27.1 compare.)
    jar_path = download(ORT_JAVA_JAR, work)
    # Sanity: the JAR must contain the ai.onnxruntime API.
    with zipfile.ZipFile(jar_path) as zf:
        names = zf.namelist()
        if 'ai/onnxruntime/OrtEnvironment.class' not in names:
            raise RuntimeError('Downloaded JAR missing ai.onnxruntime API')
    shutil.copyfile(jar_path, output / 'classes.jar')
    print('classes.jar ready (ORT 1.27.x Java API from Maven Central)', flush=True)

    # 2. Prebuilt JNI bridge from the csukuangfj 1.27.1 release (same release
    #    Sherpa's libonnxruntime.so comes from -- guaranteed ABI match).
    with zipfile.ZipFile(download(ORT_ANDROID, work)) as archive:
        jni_name = f'jni/{ABI}/libonnxruntime4j_jni.so'
        if jni_name not in archive.namelist():
            raise RuntimeError(f'Missing {jni_name} in csukuangfj zip')
        jni_out = output / 'jni' / ABI
        jni_out.mkdir(parents=True)
        with archive.open(jni_name) as src_file, open(jni_out / 'libonnxruntime4j_jni.so', 'wb') as dst:
            shutil.copyfileobj(src_file, dst)
    print('Extracted prebuilt libonnxruntime4j_jni.so', flush=True)

    # 3. Sanity: the module must NOT package a private libonnxruntime.so.
    if (output / 'jni' / ABI / 'libonnxruntime.so').exists():
        raise RuntimeError('Module must not package a private libonnxruntime.so')

    stamp.write_text(ORT_VERSION + fingerprint)
    print(f'Vision ORT JNI ready: {output}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.output)
