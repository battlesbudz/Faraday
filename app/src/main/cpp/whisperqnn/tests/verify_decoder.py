#!/usr/bin/env python3
"""Weight-free native contracts using the existing ORT 1.27.1 host SDK.

Usage: verify_decoder.py --ort-root PATH --jni-include PATH [--receipt PATH]
The ORT zip is checksum-pinned in scripts/build_sherpa.py. This test downloads
nothing. JNI includes may come from the installed JDK or official OpenJDK.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile


def varint(number):
    result = bytearray()
    while number >= 128:
        result.append((number & 127) | 128)
        number >>= 7
    return bytes(result + bytes([number]))


def integer(field, number):
    return varint(field << 3) + varint(number)


def message(field, data):
    data = data.encode() if isinstance(data, str) else data
    return varint((field << 3) | 2) + varint(len(data)) + data


def identity_model():
    # ONNX public wire schema: IR8/opset13 Identity(float16[1] x) -> y.
    shape = message(1, integer(1, 1))
    tensor_type = integer(1, 10) + message(2, shape)
    def value(name):
        return message(1, name) + message(2, message(1, tensor_type))
    node = message(1, 'x') + message(2, 'y') + message(4, 'Identity')
    graph = message(1, node) + message(2, 'whisper_contract_identity') + message(11, value('x')) + message(12, value('y'))
    return integer(1, 8) + message(7, graph) + message(8, integer(2, 13))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ort-root', required=True, type=Path)
    parser.add_argument('--jni-include', required=True, type=Path)
    parser.add_argument('--receipt', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    headers = next(args.ort_root.rglob('onnxruntime_cxx_api.h')).parent
    library = next(args.ort_root.rglob('libonnxruntime.so')).parent
    compiler = os.environ.get('CXX', 'g++')
    common = [compiler, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror', '-pthread'] + shlex.split(os.environ.get('CXXFLAGS', ''))
    includes = ['-I' + str(headers), '-I' + str(args.jni_include), '-I' + str(args.jni_include / 'linux')]
    commands = []
    def run(command):
        commands.append(command)
        subprocess.run(command, check=True)
    with tempfile.TemporaryDirectory(prefix='whisper-qnn-decoder-') as tmp:
        temporary = Path(tmp)
        plugin = temporary / 'fake-provider'
        plugin.mkdir()
        run(common + includes + ['-shared', '-fPIC', str(root / 'tests' / 'fake_ep_factory.cc'), '-o', str(plugin / 'libonnxruntime_providers_qnn.so')])
        run(common + includes + ['-fsyntax-only', str(root / 'whisper_qnn_jni.cc'), str(root / 'whisper_qnn_session.cc'), str(root / 'whisper_features.cc')])
        for source, dependencies, links in [
            ('decode_state_test.cc', [], []),
            ('registry_test.cc', [], []),
            ('runtime_contract_test.cc', [str(root / 'whisper_features.cc')], ['-L' + str(library), '-Wl,-rpath,' + str(library), '-lonnxruntime']),
        ]:
            output = temporary / Path(source).stem
            run(common + includes + [str(root / 'tests' / source)] + dependencies + links + ['-o', str(output)])
            extra = []
            if source == 'runtime_contract_test.cc':
                model = temporary / 'identity.onnx'
                model.write_bytes(identity_model())
                extra = [str(model), str(plugin)]
            run([str(output)] + extra)
    receipt = {'status': 'passed', 'onnx_runtime': '1.27.1',
               'checks': ['actual_native_jni_syntax', 'decoder_state_and_fp16', 'real_ort_metadata_rejection', 'real_ort_run_termination', 'terminal_cancellation_races', 'jni_handle_lifetime', 'absent_qnn_diagnosed_failure', 'shared_env_registration_lifetime', 'post_registration_failure_rollback'],
               'not_covered': ['QNN HTP execution', 'Android JNI loading', 'real Whisper acoustic accuracy', 'device latency'],
               'commands': commands}
    receipt['source_sha256'] = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(root.rglob('*')) if path.is_file() and path.suffix in {'.h', '.cc', '.py'}}
    receipt['extra_cxxflags'] = os.environ.get('CXXFLAGS', '')
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2) + '\n')
    print('Whisper QNN native host contracts passed; hardware inference remains unverified.')


if __name__ == '__main__':
    main()
