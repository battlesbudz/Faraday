#!/usr/bin/env python3
"""Verify the exact Piper callback JNI method in packaged DEX, including R8 releases."""
import struct
import sys
import zipfile

OWNER = 'Lcom/battlesbudz/jarvis/v2/voice/SherpaPcmCallback;'


def has_callback(data):
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    def table(offset):
        return u32(offset), u32(offset + 4)
    count, base = table(56)
    strings = []
    for i in range(count):
        pos = u32(base + 4 * i)
        while data[pos] & 128:
            pos += 1
        pos += 1
        strings.append(data[pos:data.index(b'\0', pos)].decode('utf-8', errors='replace'))
    count, base = table(64)
    types = [strings[u32(base + i * 4)] for i in range(count)]
    count, base = table(96)
    if not any(types[u32(base + i * 32)] == OWNER for i in range(count)):
        return False
    count, base = table(72)
    protos = []
    for i in range(count):
        result = types[u32(base + i * 12 + 4)]
        params = u32(base + i * 12 + 8)
        args = [] if not params else [types[struct.unpack_from('<H', data, params + 4 + j * 2)[0]]
                                    for j in range(u32(params))]
        protos.append('(' + ''.join(args) + ')' + result)
    count, base = table(88)
    for i in range(count):
        owner, proto, name = struct.unpack_from('<HHI', data, base + i * 8)
        if types[owner] == OWNER and strings[name] == 'invoke' and protos[proto] == '([F)Ljava/lang/Integer;':
            return True
    return False


if __name__ == '__main__':
    with zipfile.ZipFile(sys.argv[1]) as apk:
        assert any(has_callback(apk.read(n)) for n in apk.namelist()
                   if n.startswith('classes') and n.endswith('.dex')), 'Piper callback JNI signature missing after packaging'
        native = apk.read('lib/arm64-v8a/libsherpa-onnx-jni.so')
        assert b'jarvis_session' not in native, 'Retired Paul native patch packaged'
        assert not any(n.startswith('assets/voice/paul-') or n.startswith('assets/licenses/pocket-tts') for n in apk.namelist()), 'Retired Paul assets packaged'
    print('Piper boxed callback ABI and retired-asset removal verified in APK.')
