# SPDX-License-Identifier: Apache-2.0
"""Real ELF section-table fixtures; map/ELF metadata, not source causality."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest

from zew import main
from zew_evidence.collector import collect, InputError
from zew_evidence.elf_sections import inspect_sections
from zew_evidence.section_audit import audit


def fixture(bits=32, order="<", name=".text", nobits=False, duplicate=False):
    klass, bo = (1 if bits == 32 else 2), (1 if order == "<" else 2)
    ident = b"\x7fELF" + bytes([klass, bo, 1, 0]) + bytes(8)
    name_blob = b"\x00" + name.encode() + b"\x00.shstrtab\x00"
    name_offset = 1
    string_offset = len(name) + 2
    is_64 = bits == 64
    hfmt = "HHIQQQIHHHHHH" if is_64 else "HHIIIIIHHHHHH"
    pfmt = "IIQQQQQQ" if is_64 else "IIIIIIII"
    sfmt = "IIQQQQIIQQ" if is_64 else "IIIIIIIIII"
    hsize, psize, ssize = (64, 56, 64) if is_64 else (52, 32, 40)
    shoff = 0x180
    shnum = 4 if duplicate else 3
    shstrndx = shnum - 1
    address = 0x4030 if nobits else 0x4000
    size = 0x10 if nobits else 0x20
    header = struct.pack(order + hfmt, 2, 62 if is_64 else 40, 1,
                         0x4000, hsize, shoff, 0, hsize, psize, 1,
                         ssize, shnum, shstrndx)
    if is_64:
        ph = struct.pack(order + pfmt, 1, 5, 0x100, 0x4000, 0x4000, 0x20, 0x50, 0x1000)
    else:
        ph = struct.pack(order + pfmt, 1, 0x100, 0x4000, 0x4000, 0x20, 0x50, 5, 0x1000)

    def sh(name_index, kind, flags, va, off, length, align=1):
        return struct.pack(order + sfmt, name_index, kind, flags, va, off, length, 0, 0,
                           align, 0)

    sections = sh(0, 0, 0, 0, 0, 0)
    sections += sh(name_offset, 8 if nobits else 1, 3 if nobits else 6,
                   address, 0x130 if nobits else 0x100, size, 4)
    if duplicate:
        sections += sh(name_offset, 8 if nobits else 1, 3 if nobits else 6,
                       address, 0x130 if nobits else 0x100, size, 4)
    sections += sh(string_offset, 3, 0, 0, 0x130, len(name_blob))
    result = bytearray(shoff + len(sections))
    result[:len(ident + header + ph)] = ident + header + ph
    result[0x100:0x120] = bytes(range(32))
    result[0x130:0x130 + len(name_blob)] = name_blob
    result[shoff:shoff + len(sections)] = sections
    return bytes(result)


class OutputSectionAuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="zew-output-elf-Ω ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.build = self.root / "app"
        (self.build / "zephyr").mkdir(parents=True)
        self.map_path = self.build / "zephyr/zephyr.map"
        self.elf_path = self.build / "zephyr/zephyr.elf"
        self.elf_path.write_bytes(fixture())
        self.save_map()

    def save_map(self, lines=".text 0x4000 0x20\n"):
        self.map_path.write_text(
            "Discarded input sections\n.text.discarded 0x3000 0x10\n"
            "Linker script and memory map\n" + lines
            + " .text.input 0x4000 0x10 fake.o\n",
            encoding="utf-8")

    def result(self):
        return audit(self.build)["images"][0]

    def test_real_section_bytes_hash_map_metadata_and_privacy(self):
        result = self.result()
        self.assertEqual(result["status"], "map-elf-output-layout-observed")
        self.assertEqual(result["confidence"], "derived")
        self.assertEqual(result["map_output_sections"], 1)
        self.assertEqual(result["elf_allocated_sections"], 1)
        self.assertEqual(result["comparison"]["exact-layout"], 1)
        self.assertEqual(result["comparison"]["file-backed-vma"], 1)
        self.assertEqual(result["observed_elf_section_hashes"], [
            {"sha256": hashlib.sha256(bytes(range(32))).hexdigest(), "bytes": 32}])
        self.assertEqual(result["provenance"]["elf"]["sha256"],
                         hashlib.sha256(self.elf_path.read_bytes()).hexdigest())
        data = json.dumps(audit(self.build))
        for secret in (str(self.root), "fake.o", ".text.input", ".text", "private-password"):
            self.assertNotIn(secret, data)
        self.assertEqual(audit(self.build), audit(self.build))

    def test_elf32_elf64_both_endianness(self):
        for bits in (32, 64):
            for order in ("<", ">"):
                with self.subTest(bits=bits, order=order):
                    self.elf_path.write_bytes(fixture(bits, order))
                    self.assertEqual(self.result()["comparison"]["exact-layout"], 1)
                    self.assertEqual(inspect_sections(self.elf_path)[0]["sha256"],
                                     hashlib.sha256(bytes(range(32))).hexdigest())

    def test_output_address_size_and_missing_section_are_not_equated(self):
        self.save_map(".text 0x4100 0x20\n")
        self.assertEqual(self.result()["comparison"]["address-mismatch"], 1)
        self.save_map(".text 0x4000 0x21\n")
        self.assertEqual(self.result()["comparison"]["size-mismatch"], 1)
        self.save_map(".unknown 0x4000 0x20\n")
        self.assertEqual(self.result()["comparison"]["missing-or-unallocated"], 1)

    def test_duplicates_are_not_arbitrarily_selected(self):
        self.save_map(".text 0x4000 0x20\n.text 0x4000 0x20\n")
        self.assertEqual(self.result()["comparison"]["ambiguous-map-name"], 2)
        self.save_map()
        self.elf_path.write_bytes(fixture(duplicate=True))
        self.assertEqual(self.result()["comparison"]["ambiguous-elf-name"], 1)

    def test_nobits_zero_fill_never_hashes_nonexistent_bytes(self):
        self.elf_path.write_bytes(fixture(name=".bss", nobits=True))
        self.save_map(".bss 0x4030 0x10\n")
        result = self.result()
        self.assertEqual(result["status"], "map-elf-output-layout-observed")
        self.assertEqual(result["comparison"]["zero-fill-vma"], 1)
        self.assertEqual(result["observed_elf_section_hashes"], [])

    def test_unrecognised_output_header_and_bad_elf_stay_unknown(self):
        self.save_map("section: text at 0x4000 size 0x20\n")
        self.assertEqual(self.result()["status"], "no-supported-map-output-section")
        self.elf_path.write_bytes(b"not-elf")
        self.assertEqual(self.result()["status"], "unsupported-or-invalid-elf-sections")

    def test_section_bounds_and_string_table_corruption(self):
        blob = bytearray(fixture())
        struct.pack_into("<I", blob, 0x180 + 40 + 16, 0xffffffff)
        self.elf_path.write_bytes(blob)
        with self.assertRaises(InputError):
            inspect_sections(self.elf_path)
        blob = bytearray(fixture())
        struct.pack_into("<I", blob, 0x180 + 40, 0xffffffff)
        self.elf_path.write_bytes(blob)
        with self.assertRaises(InputError):
            inspect_sections(self.elf_path)

    def test_stale_map_or_elf_snapshot_is_rejected(self):
        graph = collect(self.build)
        self.map_path.write_text(self.map_path.read_text() + "\nchanged")
        with self.assertRaisesRegex(InputError, "differs"):
            audit(self.build, graph=graph)
        self.save_map()
        graph = collect(self.build)
        self.elf_path.write_bytes(self.elf_path.read_bytes() + b"changed")
        with self.assertRaisesRegex(InputError, "differs"):
            audit(self.build, graph=graph)

    def test_missing_multi_image_remains_isolated(self):
        boot = self.root / "mcuboot"
        boot.mkdir()
        result = audit(self.root, ["app=app", "boot=mcuboot"])
        self.assertEqual([i["image"] for i in result["images"]], ["app", "boot"])
        self.assertEqual(result["images"][0]["status"], "map-elf-output-layout-observed")
        self.assertEqual(result["images"][1]["status"], "unknown")

    def test_cli_and_opt_in_link_audit_without_source_digests(self):
        output = io.StringIO()
        before = {x.relative_to(self.root): x.read_bytes()
                  for x in self.root.rglob("*") if x.is_file()}
        with contextlib.redirect_stdout(output):
            self.assertEqual(main(["section-audit", "--build", str(self.build)]), 0)
        self.assertEqual(json.loads(output.getvalue())["images"][0]["status"],
                         "map-elf-output-layout-observed")
        with contextlib.redirect_stdout(io.StringIO()) as second:
            self.assertEqual(main(["link-audit", "--build", str(self.build),
                                   "--section-evidence"]), 0)
        self.assertEqual(json.loads(second.getvalue())["images"][0]["independent_section_audit"]["status"],
                         "map-elf-output-layout-observed")
        self.assertEqual(before, {x.relative_to(self.root): x.read_bytes()
                                  for x in self.root.rglob("*") if x.is_file()})


if __name__ == "__main__":
    unittest.main()
