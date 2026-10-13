import struct
import tempfile
import unittest
from pathlib import Path
import zipfile

from check_page_sizes import PAGE_SIZE, audit_apk, elf_segments


def elf(load_alignment=PAGE_SIZE, second_alignment=PAGE_SIZE, relro_end=PAGE_SIZE, vaddr=0,
        writable_address=PAGE_SIZE, relro_start=0, writable_memsz=128):
    data = bytearray(512)
    data[:16] = b"\x7fELF\x02\x01\x01" + b"\x00" * 9
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, 183, 1, 0, 64, 0, 0, 64, 56, 3, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, vaddr, 0, 200, 200, load_alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 1, 6, 256, writable_address + 256, 0, 128, writable_memsz, second_alignment)
    struct.pack_into("<IIQQQQQQ", data, 176, 0x6474E552, 4, 0, relro_start, 0, 0, relro_end - relro_start, 1)
    return bytes(data)


class NativePageSizesTest(unittest.TestCase):
    def apk(self, data, compressed=False, aligned=True):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "app.apk"
        name = "lib/arm64-v8a/libfixture.so"
        info = zipfile.ZipInfo(name)
        info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
        if aligned:
            # A real extra-field TLV; local-file payload starts at 16384.
            padding = PAGE_SIZE - (30 + len(name.encode()))
            info.extra = struct.pack("<HH", 0xD935, padding - 4) + b"\x00" * (padding - 4)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(info, data)
        return path

    def test_aligned_direct_load_and_compressed_extracted_libraries_pass(self):
        for compressed in (False, True):
            with self.subTest(compressed=compressed):
                result = audit_apk(self.apk(elf(), compressed=compressed, aligned=not compressed))
                self.assertTrue(result["passed"], result["errors"])
                self.assertEqual(1, len(result["libraries"]))
                self.assertEqual(64, len(result["libraries"][0]["sha256"]))

    def test_second_load_segment_is_checked(self):
        result = audit_apk(self.apk(elf(second_alignment=4096)))
        self.assertFalse(result["passed"])
        self.assertIn("LOAD 1 alignment", " ".join(result["errors"]))

    def test_wrong_machine_under_arm64_path_is_rejected(self):
        data = bytearray(elf())
        struct.pack_into("<H", data, 18, 62)  # EM_X86_64 under an arm64-v8a ZIP path.
        result = audit_apk(self.apk(data))
        self.assertFalse(result["passed"])
        self.assertIn("machine 62", " ".join(result["errors"]))

    def test_load_file_size_exceeding_memory_size_is_rejected(self):
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 40, 199)  # First LOAD filesz=200, memsz=199.
        result = audit_apk(self.apk(data))
        self.assertFalse(result["passed"])
        self.assertIn("file size exceeds", " ".join(result["errors"]))

    def test_aligned_value_does_not_hide_offset_incongruence(self):
        result = audit_apk(self.apk(elf(vaddr=4096)))
        self.assertFalse(result["passed"])
        self.assertIn("offsets differ", " ".join(result["errors"]))

    def test_uncompressed_zip_payload_alignment_is_required(self):
        result = audit_apk(self.apk(elf(), aligned=False))
        self.assertFalse(result["passed"])
        self.assertIn("ZIP aligned", " ".join(result["errors"]))

    def test_compression_does_not_waive_elf_alignment(self):
        result = audit_apk(self.apk(elf(load_alignment=4096), compressed=True, aligned=False))
        self.assertFalse(result["passed"])
        self.assertIn("LOAD 0 alignment", " ".join(result["errors"]))

    def test_relro_page_padding_must_not_protect_other_writable_data(self):
        result = audit_apk(self.apk(elf(relro_end=4096, writable_address=0, writable_memsz=8192)))
        self.assertFalse(result["passed"])
        self.assertIn("GNU_RELRO", " ".join(result["errors"]))

    def test_relro_prefix_must_not_protect_other_writable_data(self):
        result = audit_apk(self.apk(elf(relro_start=4096, relro_end=PAGE_SIZE, writable_address=0)))
        self.assertFalse(result["passed"])
        self.assertIn("GNU_RELRO", " ".join(result["errors"]))

    def test_relro_unaligned_end_with_unmapped_padding_is_safe(self):
        result = audit_apk(self.apk(elf(relro_end=4096)))
        self.assertTrue(result["passed"], result["errors"])

    def test_relro_fully_covering_own_writable_load_is_safe(self):
        result = audit_apk(self.apk(elf(relro_end=4096, writable_address=0)))
        self.assertTrue(result["passed"], result["errors"])

    def test_truncated_or_wrong_elf_fails_instead_of_omitting_library(self):
        for data in (b"not ELF", elf()[:180]):
            with self.subTest(data=data[:8]):
                self.assertFalse(audit_apk(self.apk(data))["passed"])
        with self.assertRaisesRegex(ValueError, "program-header"):
            elf_segments(elf()[:180])

    def test_absence_of_native_libraries_is_not_a_pass(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "empty.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", b"fixture")
        self.assertFalse(audit_apk(path)["passed"])


if __name__ == "__main__":
    unittest.main()
