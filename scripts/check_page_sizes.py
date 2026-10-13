#!/usr/bin/env python3
"""Fail a release if any native ELF or direct-loaded APK entry is not 16 KB compatible.

Parses program headers and local ZIP headers rather than trusting a library name,
build flag or central-directory extra field. Compressed compact-APK libraries are
extracted by Android, so they need ELF alignment but not ZIP page alignment.
https://developer.android.com/guide/practices/page-sizes
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

PAGE_SIZE = 16384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552


def elf_segments(data, expected_machine=183):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("Expected a little-endian 64-bit ELF")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    if header[0] != 3:
        raise ValueError("Native library is not an ELF shared object")
    if header[1] != expected_machine:
        raise ValueError(f"ELF machine {header[1]} does not match shipping ARM64 machine {expected_machine}")
    offset, size, count = header[4], header[8], header[9]
    if size != 56 or count < 1 or offset + size * count > len(data):
        raise ValueError("Invalid or truncated ELF program-header table")
    segments = []
    for index in range(count):
        kind, flags, file_offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * size)
        if file_offset + file_size > len(data):
            raise ValueError(f"Truncated ELF segment {index}")
        if kind == PT_LOAD and file_size > memory_size:
            raise ValueError(f"LOAD {index} file size exceeds its memory size")
        if kind in (PT_LOAD, PT_GNU_RELRO):
            segments.append({"index": index, "type": "LOAD" if kind == PT_LOAD else "GNU_RELRO",
                             "flags": flags, "offset": file_offset, "vaddr": address, "filesz": file_size,
                             "memsz": memory_size, "alignment": alignment})
    if not any(segment["type"] == "LOAD" for segment in segments):
        raise ValueError("ELF has no loadable segments")
    return segments


def zip_data_offset(stream, entry):
    stream.seek(entry.header_offset)
    header = stream.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("Invalid ZIP local-file header")
    filename_size, extra_size = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + 30 + filename_size + extra_size


def audit_apk(path):
    path = Path(path)
    result = {"name": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
              "libraries": [], "errors": [], "passed": False}
    with path.open("rb") as stream, zipfile.ZipFile(path) as archive:
        libraries = [entry for entry in archive.infolist() if re.fullmatch(r"lib/[^/]+/[^/]+\.so", entry.filename)]
        if not libraries:
            result["errors"].append("APK contains no native libraries")
        if len({entry.filename for entry in libraries}) != len(libraries):
            result["errors"].append("APK contains duplicate native library entries")
        for entry in libraries:
            library = {"name": entry.filename, "compressed": entry.compress_type != zipfile.ZIP_STORED,
                       "sha256": "", "segments": [], "errors": []}
            try:
                if entry.filename.split("/")[1] != "arm64-v8a":
                    raise ValueError("Unexpected native ABI: the shipping release contract is ARM64 only")
                data = archive.read(entry)
                library["sha256"] = hashlib.sha256(data).hexdigest()
                library["segments"] = elf_segments(data)
                for segment in library["segments"]:
                    if segment["type"] == "LOAD":
                        alignment = segment["alignment"]
                        if alignment < PAGE_SIZE or alignment & (alignment - 1):
                            library["errors"].append(f"LOAD {segment['index']} alignment {alignment} is below 16 KB or not a power of two")
                        if (segment["vaddr"] - segment["offset"]) % PAGE_SIZE:
                            library["errors"].append(f"LOAD {segment['index']} virtual/file offsets differ modulo 16 KB")
                    elif segment["memsz"]:
                        # Bionic rounds mprotect to page boundaries. A partial
                        # RELRO page is safe when its padding contains no live
                        # writable LOAD bytes (e.g. a fully protected LOAD with
                        # a gap before the next writable LOAD). Rejecting all
                        # non-aligned ends falsely rejects those safe binaries.
                        # linker_phdr.cpp + linker_phdr_16kib_compat.cpp on AOSP.
                        start, end = segment["vaddr"], segment["vaddr"] + segment["memsz"]
                        protected_start = start // PAGE_SIZE * PAGE_SIZE
                        protected_end = (end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE
                        extra = [(protected_start, start), (end, protected_end)]
                        segment["protected_start"] = protected_start
                        segment["protected_end"] = protected_end
                        segment["end_aligned"] = end % PAGE_SIZE == 0
                        segment["safe_page_padding"] = True
                        for load in library["segments"]:
                            if load["type"] != "LOAD" or not load["flags"] & 2:
                                continue
                            load_end = load["vaddr"] + load["memsz"]
                            if any(max(left, load["vaddr"]) < min(right, load_end) for left, right in extra):
                                segment["safe_page_padding"] = False
                                library["errors"].append(f"GNU_RELRO {segment['index']} page padding overlaps writable LOAD {load['index']}")
                library["zip_data_offset"] = zip_data_offset(stream, entry)
                if not library["compressed"] and library["zip_data_offset"] % PAGE_SIZE:
                    library["errors"].append("Uncompressed library is not 16 KB ZIP aligned")
            except (ValueError, struct.error, zipfile.BadZipFile) as error:
                library["errors"].append(str(error))
            library["passed"] = not library["errors"]
            result["libraries"].append(library)
            result["errors"].extend(f"{entry.filename}: {error}" for error in library["errors"])
    result["passed"] = not result["errors"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F]{40}", args.source_commit):
        parser.error("--source-commit must identify the exact tested Git commit")
    report = {"schema": 1, "source_commit": args.source_commit, "page_size": PAGE_SIZE,
              "coverage": "all shipped native ELF LOAD/RELRO segments and direct-load APK ZIP offsets",
              "apks": [audit_apk(path) for path in args.apks]}
    report["passed"] = bool(report["apks"]) and all(apk["passed"] for apk in report["apks"])
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    for apk in report["apks"]:
        print(f"{apk['name']}: {len(apk['libraries'])} libraries; {'PASS' if apk['passed'] else 'FAIL'}")
        for error in apk["errors"]:
            print(error)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
