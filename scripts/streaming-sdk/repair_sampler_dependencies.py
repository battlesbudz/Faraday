"""Derive the two pinned Android sampler ELFs with an explicit runtime owner.

The tool changes ELF metadata only. Original runtime code/data, symbol ABI,
relocations and RELRO are verified before the derived copy may be packaged.
"""
import hashlib
from pathlib import Path
import struct
import subprocess

PROVIDER = 'liblitertlm_jni.so'
TOOL_SHA256 = 'b699cb82300dfd08ea6c895d8829b46bea970cfa737fa34188d8256ca89404e5'
TARGETS = {
    'libLiteRtTopKOpenClSampler.so': '4404dc68786460602685cab62ddfa29035e9cfc38bb4550dec15abaaa1302a82',
    'libLiteRtTopKWebGpuSampler.so': 'c52a1cf69a92a2d2c4d3c08f5c087d1eb405f709af61c3312b215221135e18db',
}
OUTPUTS = {
    'libLiteRtTopKOpenClSampler.so': '8ce3b26c4c8eb26a13a06cd3838f9a27524032772551e4307e9c5b72ff8d756a',
    'libLiteRtTopKWebGpuSampler.so': 'e446ca3394e95b0e42157eae878511d555d14aa86097c48a676346b89f3e2277',
}
METADATA = {'.dynamic', '.dynstr', '.note.android.ident', '.note.gnu.build-id'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def android_packed_relocations(data):
    """Read APS2 relocation locations, including the Android packed RELA stream.

    Layout reference: AOSP android-11.0.0_r1/linker/linker_reloc_iterators.h.
    Addends are consumed but never interpreted as addresses to modify.
    """
    need(data[:4] == b'APS2', 'Unrecognized Android relocation encoding')
    position = 4
    def signed():
        nonlocal position
        value, shift = 0, 0
        for _ in range(10):
            need(position < len(data), 'Truncated packed relocation')
            byte = data[position]; position += 1
            value |= (byte & 127) << shift; shift += 7
            if not byte & 128:
                if byte & 64:
                    value -= 1 << shift
                need(-(1 << 63) <= value < (1 << 63), 'Packed integer overflow')
                return value
        raise ValueError('Oversized packed integer')
    count, offset = signed(), signed()
    need(0 <= count <= 1000000 and offset >= 0, 'Invalid packed relocation count/base')
    result = []
    while len(result) < count:
        size, flags = signed(), signed()
        need(0 < size <= count-len(result) and 0 <= flags <= 15, 'Invalid packed relocation group')
        need(not flags & 4 or flags & 8, 'Grouped addend without addends')
        delta = signed() if flags & 2 else None
        info = signed() if flags & 1 else None
        if flags & 8 and flags & 4:
            signed()
        for _ in range(size):
            offset += delta if flags & 2 else signed()
            entry_info = info if flags & 1 else signed()
            if flags & 8 and not flags & 4:
                signed()
            need(0 <= offset < 1 << 64 and 0 <= entry_info < 1 << 64, 'Packed relocation overflow')
            result.append((offset, entry_info))
    need(position == len(data), 'Trailing packed relocation data')
    return result


class Elf:
    def __init__(self, data):
        self.data = data
        need(len(data) >= 64 and data[:6] == b'\x7fELF\x02\x01', 'Expected ELF64 little endian')
        h = struct.unpack_from('<HHIQQQIHHHHHH', data, 16)
        need(h[0:3] == (3, 183, 1), 'Expected ARM64 shared object')
        self.phoff, self.shoff, self.phnum, self.shnum = h[4], h[5], h[9], h[11]
        need(h[8] == 56 and h[10] == 64 and 0 < self.phnum < 128 and 0 < self.shnum < 1024,
             'Unexpected ELF table layout')
        need(self.phoff + self.phnum * 56 <= len(data) and self.shoff + self.shnum * 64 <= len(data),
             'Truncated ELF tables')
        self.ph = [list(struct.unpack_from('<IIQQQQQQ', data, self.phoff + i * 56)) for i in range(self.phnum)]
        raw = [list(struct.unpack_from('<IIQQQQIIQQ', data, self.shoff + i * 64)) for i in range(self.shnum)]
        need(h[12] < len(raw), 'Missing section names')
        names = self.bytes_for(raw[h[12]])
        self.sections = {}
        self.section_order = []
        for i, section in enumerate(raw):
            name = self.string(names, section[0])
            need(name not in self.sections, 'Duplicate section name')
            self.sections[name] = (i, section)
            self.section_order.append(name)
            if section[1] != 8:
                self.bytes_for(section)
        for p in self.ph:
            need(p[2] + p[5] <= len(data), 'Truncated program segment')
            if p[0] == 1:
                need(p[5] <= p[6] and p[7] >= 16384 and not p[7] & (p[7]-1), 'Bad LOAD layout')
                need((p[3]-p[2]) % 16384 == 0, 'LOAD alignment differs from 16KB')
                need(not (p[1] & 1 and p[1] & 2), 'Writable executable LOAD')

    @staticmethod
    def string(data, offset):
        need(0 <= offset < len(data), 'String offset out of bounds')
        end = data.find(b'\0', offset)
        need(end >= 0, 'Unterminated ELF string')
        return data[offset:end].decode('utf-8', errors='strict')

    def bytes_for(self, section):
        offset, size = section[4], section[5]
        need(offset + size <= len(self.data), 'Truncated section')
        return self.data[offset:offset+size]

    def section(self, name):
        need(name in self.sections, 'Missing section ' + name)
        return self.sections[name][1]

    def content(self, name):
        return self.bytes_for(self.section(name))

    def dynamic(self):
        data = self.content('.dynamic')
        need(len(data) % 16 == 0, 'Bad dynamic table')
        entries = [struct.unpack_from('<qQ', data, i) for i in range(0, len(data), 16)]
        need(entries and entries[-1][0] == 0 and all(t != 0 for t, _ in entries[:-1]), 'Unexpected dynamic padding')
        return entries

    def needed(self):
        return [self.string(self.content('.dynstr'), value) for tag, value in self.dynamic() if tag == 1]

    def symbols(self):
        data = self.content('.dynsym')
        strings = self.content('.dynstr')
        need(len(data) % 24 == 0, 'Bad dynamic symbols')
        result = []
        for i in range(0, len(data), 24):
            name, info, other, index, value, size = struct.unpack_from('<IBBHQQ', data, i)
            section = self.section_order[index] if 0 < index < len(self.section_order) else index
            result.append((self.string(strings, name), info, other, section, value, size))
        return result


def harden_and_verify(original, patched):
    """Make the new metadata segment read-only; retain all prior RELRO coverage.

    The new segment contains no code, mutable runtime objects or relocation
    targets. Android's linker supports read-only dynamic tables. DT_DEBUG is
    forbidden here, and the final PT_DYNAMIC has PF_W cleared as well.
    """
    before, after = Elf(original), Elf(patched)
    need(PROVIDER not in before.needed(), 'Input already has provider')
    need(after.needed() == [PROVIDER] + before.needed(), 'Unexpected dependency rewrite')
    need(after.content('.dynstr').startswith(before.content('.dynstr')), 'Original string offsets/content changed')
    need(set(before.sections) == set(after.sections), 'Section inventory changed')
    need(before.symbols() == after.symbols(), 'Dynamic symbol ABI changed')
    need(all(tag not in {21, 22} for tag, _ in before.dynamic()+after.dynamic()), 'DT_DEBUG/TEXTREL not allowed')
    need(all(not value & 4 for tag, value in before.dynamic()+after.dynamic() if tag == 30), 'DF_TEXTREL not allowed')
    for elf in (before, after):
        tags = elf.dynamic()
        need([value for tag, value in tags if tag == 5] == [elf.section('.dynstr')[3]] and
             [value for tag, value in tags if tag == 10] == [elf.section('.dynstr')[5]], 'Dynamic string pointers disagree')
        dynamic = [p for p in elf.ph if p[0] == 2]
        section = elf.section('.dynamic')
        need(len(dynamic) == 1 and dynamic[0][2:4] == [section[4], section[3]] and
             dynamic[0][5:7] == [section[5], section[5]], 'Dynamic section/segment pointers disagree')
    def stable_tags(elf):
        return [(t, v) for t, v in elf.dynamic() if t not in {0, 1, 5, 10}]
    need(stable_tags(before) == stable_tags(after), 'Non-string dynamic tags changed')
    need([p for p in before.ph if p[0] == 0x6474e552] ==
         [p for p in after.ph if p[0] == 0x6474e552], 'Original RELRO changed')
    old_loads = [p for p in before.ph if p[0] == 1]
    new_loads = [(i, p) for i, p in enumerate(after.ph) if p[0] == 1 and p not in old_loads]
    need(len(new_loads) == 1 and all(p in after.ph for p in old_loads), 'Original LOAD layout changed')
    index, added = new_loads[0]
    old_end = max(p[3] + p[6] for p in old_loads)
    old_page_end = (old_end + 16383) // 16384 * 16384
    need(added[3] >= old_page_end and added[1] == 6, 'Unexpected added metadata LOAD')
    covered = set()
    for name, (_, section) in after.sections.items():
        if section[2] & 2 and section[5] and added[3] <= section[3] < added[3]+added[6]:
            need(name in METADATA and section[3]+section[5] <= added[3]+added[6], 'Mutable/code content in added LOAD')
            covered.add(name)
    need(covered == METADATA, 'Unexpected metadata segment inventory')
    for name, (_, old) in before.sections.items():
        new = after.section(name)
        if old[2] & 2 and name not in METADATA:
            need(old[2:4] == new[2:4] and old[5] == new[5], 'Allocated section moved/resized: '+name)
            if old[1] != 8 and name != '.dynsym':
                need(before.content(name) == after.content(name), 'Runtime section changed: '+name)
    # Every relocation byte is unchanged and every original allocation is below
    # the new segment. Pinned inputs cannot introduce relocation writes there.
    for name in before.sections:
        if 'rel' in name and before.section(name)[1] in {4, 9, 0x60000001, 0x60000002}:
            need(before.content(name) == after.content(name), 'Relocation bytes changed')
    relocations = android_packed_relocations(before.content('.rela.dyn'))
    plt = before.content('.rela.plt')
    need(len(plt) % 24 == 0, 'Malformed PLT relocations')
    relocations += [(offset, info) for offset, info, _ in struct.iter_unpack('<QQq', plt)]
    for offset, info in relocations:
        kind = info & 0xffffffff
        need(kind in {0, 257, 1025, 1026, 1027}, 'Unreviewed ARM64 relocation kind')
        if kind:
            need(any(p[1] & 2 and p[3] <= offset and offset+8 <= p[3]+p[6] for p in old_loads),
                 'Relocation target outside original writable storage')
    need(before.content('.note.android.ident') == after.content('.note.android.ident') and
         before.content('.note.gnu.build-id') == after.content('.note.gnu.build-id'), 'Original notes changed')
    output = bytearray(patched)
    struct.pack_into('<I', output, after.phoff + index * 56 + 4, 4)  # PF_R
    dynamic_indices = [i for i, p in enumerate(after.ph) if p[0] == 2]
    need(len(dynamic_indices) == 1, 'Expected one PT_DYNAMIC')
    dynamic_index = dynamic_indices[0]
    dynamic = after.ph[dynamic_index]
    need(added[3] <= dynamic[3] and dynamic[3]+dynamic[6] <= added[3]+added[6], 'PT_DYNAMIC outside metadata LOAD')
    struct.pack_into('<I', output, after.phoff + dynamic_index * 56 + 4, 4)
    section_index, section = after.sections['.dynamic']
    struct.pack_into('<Q', output, after.shoff + section_index * 64 + 8, section[2] & ~1)
    final = Elf(bytes(output))
    need(final.ph[index][1] == 4 and final.ph[dynamic_index][1] == 4, 'Metadata still writable')
    return bytes(output), {'original_relro_unchanged': True, 'original_runtime_sections_unchanged': True,
        'symbol_abi_unchanged': True, 'new_metadata_load_read_only': True,
        'new_metadata_sections': sorted(covered), 'relocation_targets_verified': len(relocations), 'provider': PROVIDER,
        'original_needed': before.needed(), 'derived_needed': final.needed()}


def repair(source, destination, tool):
    source, destination, tool = Path(source), Path(destination), Path(tool)
    original = source.read_bytes()
    need(source.name in TARGETS and sha(original) == TARGETS[source.name], 'Sampler input pin mismatch')
    need(sha(tool.read_bytes()) == TOOL_SHA256, 'PatchELF tool pin mismatch')
    need(not destination.exists(), 'Derived output must be fresh')
    destination.write_bytes(original)
    subprocess.run([str(tool.resolve()), '--page-size', '16384', '--add-needed', PROVIDER,
                    str(destination.resolve())], check=True, timeout=20)
    rewritten = destination.read_bytes()
    final, receipt = harden_and_verify(original, rewritten)
    need(sha(final) == OUTPUTS[source.name], 'Derived sampler output pin mismatch')
    destination.write_bytes(final)
    receipt.update(input_sha256=sha(original), input_bytes=len(original),
        intermediate_sha256=sha(rewritten), output_sha256=sha(final), output_bytes=len(final),
        tool_sha256=TOOL_SHA256, tool_version='0.18.0', model_or_sampler_code_changed=False)
    return receipt
