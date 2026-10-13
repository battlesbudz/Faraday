#!/usr/bin/env python3
"""Read APK DEX tables and audit the export contract's cross-APK method ABI.

Uses only Python's standard library. Does not execute bytecode or write to APKs.
Method-table references establish encoded dependencies, not instruction counts.
"""

import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def descriptor(name):
    return "L" + name.replace(".", "/") + ";"


def parse_dex(data, name):
    if not data.startswith(b"dex\n"):
        raise ValueError(f"Unsupported DEX format: {name}")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Overlong ULEB128")

    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + index * 4)
        _, offset = uleb(offset)
        # All audited class names/descriptors are ASCII. Other DEX strings use
        # modified UTF-8; replacement decoding cannot affect these names.
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", "replace"))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    protos = []
    for index in range(u32(72)):
        offset = u32(76) + index * 12
        params_offset = u32(offset + 8)
        params = (
            [types[u16(params_offset + 4 + p * 2)] for p in range(u32(params_offset))]
            if params_offset else []
        )
        protos.append("(" + "".join(params) + ")" + types[u32(offset + 4)])
    methods = []
    for index in range(u32(88)):
        offset = u32(92) + index * 8
        methods.append({
            "owner": types[u16(offset)],
            "name": strings[u32(offset + 4)],
            "descriptor": protos[u16(offset + 2)],
            "dex": name,
            "method_id": index,
        })
    classes = {}
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        cls = types[u32(offset)]
        parent = u32(offset + 8)
        classes[cls] = {
            "superclass": types[parent] if parent != 0xFFFFFFFF else None,
            "dex": name,
        }
        offset = u32(offset + 24)
        if not offset:
            continue
        sizes = []
        for _ in range(4):
            size, offset = uleb(offset)
            sizes.append(size)
        for _ in range(sizes[0] + sizes[1]):
            _, offset = uleb(offset)
            _, offset = uleb(offset)
        for group, size in zip(("direct", "virtual"), sizes[2:]):
            method_index = 0
            for _ in range(size):
                delta, offset = uleb(offset)
                method_index += delta
                flags, offset = uleb(offset)
                code_offset, offset = uleb(offset)
                methods[method_index].update({
                    "definition": True,
                    "access_flags": flags,
                    "method_group": group,
                    "code_offset": code_offset,
                })
    return classes, methods


def read_apk(path):
    all_classes, all_methods, dex_hashes = {}, [], {}
    apk_bytes = path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        for name in sorted(archive.namelist()):
            if not re.fullmatch(r"classes(?:\d+)?\.dex", name):
                continue
            data = archive.read(name)
            classes, methods = parse_dex(data, name)
            duplicate_classes = all_classes.keys() & classes.keys()
            if duplicate_classes:
                raise ValueError(f"Duplicate class definitions: {duplicate_classes}")
            all_classes.update(classes)
            all_methods.extend(methods)
            dex_hashes[name] = digest(data)
    return {
        "path": str(path.resolve()), "sha256": digest(apk_bytes),
        "dex_sha256": dex_hashes, "classes": all_classes, "methods": all_methods,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", required=True, type=Path)
    parser.add_argument("--test", required=True, type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    args = parser.parse_args()
    owners = (
        "androidx.activity.result.contract.ActivityResultContract",
        "androidx.activity.result.contract.ActivityResultContracts$CreateDocument",
        "com.battlesbudz.jarvis.v2.ui.BenchmarkExportFiles$createDocument$1",
    )
    class_mapping = {}
    for line in args.mapping.read_text().splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match and match[1] in owners:
            class_mapping[match[1]] = descriptor(match[2])
    if set(class_mapping) != set(owners):
        raise ValueError("The supplied mapping does not identify every audited owner")
    selected_owners = set(class_mapping.values())
    app, test = read_apk(args.app), read_apk(args.test)
    definitions = {
        (method["owner"], method["name"], method["descriptor"]): method
        for method in app["methods"] if method.get("definition")
    }

    def resolve(method):
        owner = method["owner"]
        visited = []
        while owner and owner not in visited:
            visited.append(owner)
            definition = definitions.get((owner, method["name"], method["descriptor"]))
            if definition:
                return {"resolved": True, "defining_owner": owner, "searched_owners": visited}
            owner = app["classes"].get(owner, {}).get("superclass")
        return {"resolved": False, "searched_owners": visited}

    refs = []
    for method in test["methods"]:
        if method["owner"] in selected_owners and not method.get("definition"):
            refs.append({**method, **resolve(method)})
    report = {
        "scope": "Export contract ABI encoded in supplied release app and independent instrumentation DEX tables",
        "limits": [
            "Read-only static DEX table/hierarchy audit; no bytecode execution or Android run",
            "Method references are table entries, not instruction counts or callsite attributions",
            "Only the mapped base contract, CreateDocument and production anonymous subclass are audited",
            "Superclass lookup covers these class methods; no general interface/default-method resolution",
            "The supplied old APKs do not verify the changed source or a new R8 build",
        ],
        "mapping": {"path": str(args.mapping.resolve()), "sha256": digest(args.mapping.read_bytes()), "owners": class_mapping},
        "app": {key: app[key] for key in ("path", "sha256", "dex_sha256")},
        "test": {key: test[key] for key in ("path", "sha256", "dex_sha256")},
        "app_classes": {key: value for key, value in app["classes"].items() if key in selected_owners},
        "app_method_definitions": [m for m in app["methods"] if m["owner"] in selected_owners and m.get("definition")],
        "test_external_method_references": refs,
        "unresolved_test_reference_count": sum(not ref["resolved"] for ref in refs),
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
