"""Exercise the metadata guards on the exact public sampler binaries; no inference."""
import argparse
import json
from pathlib import Path
import struct
import tempfile

from repair_sampler_dependencies import Elf, TARGETS, OUTPUTS, harden_and_verify, repair, sha


def verify(input_dir, tool):
    checks = []
    with tempfile.TemporaryDirectory(prefix='sampler-contracts-') as temp:
        for name in TARGETS:
            original = (input_dir / name).read_bytes()
            path = Path(temp) / name
            receipt = repair(input_dir/name, path, tool)
            final = path.read_bytes()
            # Recreate the exact intermediate to test guards before hardening.
            import subprocess
            intermediate = Path(temp)/(name+'.intermediate')
            intermediate.write_bytes(original)
            subprocess.run([str(tool.resolve()), '--page-size', '16384', '--add-needed',
                            'liblitertlm_jni.so', str(intermediate)], check=True, timeout=20)
            patched = intermediate.read_bytes()
            result, _ = harden_and_verify(original, patched)
            assert result == final and sha(final) == OUTPUTS[name]
            checks.append(name+': exact output and reproducibility')
            elf, old = Elf(patched), Elf(original)

            def rejected(label, data):
                try:
                    harden_and_verify(original, bytes(data))
                except ValueError:
                    checks.append(name+': rejects '+label)
                else:
                    raise AssertionError(label)

            for section, label in [('.text', 'code corruption'), ('.rodata', 'readonly data corruption'),
                                   ('.rela.plt', 'relocation corruption'),
                                   ('.note.android.ident', 'platform note corruption')]:
                data = bytearray(patched)
                data[elf.section(section)[4]] ^= 1
                rejected(label, data)
            data = bytearray(patched)
            index = next(i for i, p in enumerate(elf.ph) if p[0] == 0x6474e552)
            struct.pack_into('<Q', data, elf.phoff+index*56+40, 16384)
            rejected('RELRO coverage change', data)
            data = bytearray(patched)
            index = next(i for i, p in enumerate(elf.ph) if p[0] == 1 and p not in old.ph)
            struct.pack_into('<I', data, elf.phoff+index*56+4, 7)
            rejected('metadata writable-executable', data)
            data = bytearray(patched)
            struct.pack_into('<q', data, elf.section('.dynamic')[4], 21)
            rejected('dynamic debugger write tag', data)
            data = bytearray(patched)
            data[elf.section('.dynstr')[4]+1] ^= 1
            rejected('old string-table prefix mutation', data)
            data = bytearray(patched)
            flag_index = next(i for i, (tag, _) in enumerate(elf.dynamic()) if tag == 30)
            flag_offset = elf.section('.dynamic')[4] + flag_index*16 + 8
            struct.pack_into('<Q', data, flag_offset, struct.unpack_from('<Q', data, flag_offset)[0] | 4)
            rejected('DF_TEXTREL', data)
            data = bytearray(patched)
            string_index = next(i for i, (tag, _) in enumerate(elf.dynamic()) if tag == 5)
            string_offset = elf.section('.dynamic')[4] + string_index*16 + 8
            struct.pack_into('<Q', data, string_offset, elf.section('.dynstr')[3]+1)
            rejected('dynamic string pointer mismatch', data)
            rejected('missing provider', original)
    return {'passed': True, 'check_count': len(checks), 'checks': checks,
            'android_load_proven': False, 'scope': 'Exact-input ELF derivation and mutation guards only'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', type=Path, required=True)
    parser.add_argument('--tool', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(verify(args.input_dir, args.tool), indent=2)+'\n')
