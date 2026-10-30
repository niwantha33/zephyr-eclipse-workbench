# SPDX-License-Identifier: Apache-2.0
"""Read-only GNU ar + ELF32/64 program header and map evidence fixtures."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError, collect
from zew_evidence.elf_ranges import coverage, load_segments
from zew_evidence.gnu_archive import observe_member
from zew_evidence.link_audit import audit


def elf_bytes(bits=32, endian="<", vma=0x4000, files=0x30, memory=0x50):
    klass = 1 if bits == 32 else 2
    order = 1 if endian == "<" else 2
    ident = b"\x7fELF" + bytes([klass, order, 1, 0]) + bytes(8)
    if bits == 32:
        header = struct.pack(endian + "HHIIIIIHHHHHH",
                             2, 40, 1, vma, 52, 0, 0, 52, 32, 1, 0, 0, 0)
        ph = struct.pack(endian + "IIIIIIII", 1, 0x100, vma, vma, files, memory, 5, 0x1000)
    else:
        header = struct.pack(endian + "HHIQQQIHHHHHH",
                             2, 62, 1, vma, 64, 0, 0, 64, 56, 1, 0, 0, 0)
        ph = struct.pack(endian + "IIQQQQQQ", 1, 5, 0x100, vma, vma, files, memory, 0x1000)
    prefix = ident + header + ph
    return prefix + bytes(max(0, 0x100 - len(prefix))) + b"X" * files


def ar_entry(name, data):
    header = (name.encode("ascii").ljust(16, b" ") + b"0".ljust(12, b" ")
              + b"0".ljust(6, b" ") + b"0".ljust(6, b" ")
              + b"100644".ljust(8, b" ") + str(len(data)).encode().ljust(10, b" ")
              + b"`\n")
    assert len(header) == 60
    return header + data + (b"\n" if len(data) % 2 else b"")


class ArchiveElfAuditTests(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory(prefix="zew-ar-elf-Ω ")
        self.addCleanup(self.t.cleanup)
        self.root = Path(self.t.name)
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "lib").mkdir()
        self.elf = self.build / "zephyr/zephyr.elf"
        self.elf.write_bytes(elf_bytes())
        self.archive = self.build / "lib/drivers.a"
        self.archive.write_bytes(b"!<arch>\n" + ar_entry("adc.o/", b"member-secret-data"))
        self.map = self.build / "zephyr/zephyr.map"
        self.write_map()

    def write_map(self, *, line=" .text.adc 0x00004000 0x10 lib/drivers.a(adc.o)\n"):
        self.map.write_text(
            "Discarded input sections\n"
            " .text.discarded 0x00000100 0x30 lib/drivers.a(adc.o)\n"
            "Linker script and memory map\n" + line,
            encoding="utf-8")

    def report(self):
        return audit(self.build)["images"][0]

    def test_elf32_and_elf64_both_endian_and_range_classes(self):
        for bits in (32, 64):
            for endian in ("<", ">"):
                with self.subTest(bits=bits, endian=endian):
                    self.elf.write_bytes(elf_bytes(bits, endian))
                    segments = load_segments(self.elf)
                    self.assertEqual(segments, [(0x4000, 0x30, 0x50)])
                    self.assertEqual(coverage(segments, 0x4000, 0x10), "file-backed-vma")
                    self.assertEqual(coverage(segments, 0x4040, 0x10), "zero-fill-vma")
                    self.assertEqual(coverage(segments, 0x4020, 0x20), "mixed-file-and-zero-fill-vma")
                    self.assertEqual(coverage(segments, 0x4060, 0x10), "uncovered")

    def test_valid_map_and_member_are_independently_observed(self):
        result = self.report()
        self.assertEqual(result["status"], "map-elf-vma-ranges-consistent")
        self.assertEqual(result["confidence"], "derived")
        self.assertEqual(result["map_sections"], 1)
        self.assertEqual(result["elf_ranges"]["section_ranges"]["file-backed-vma"], 1)
        self.assertEqual(result["archive"]["referenced_unique_members"], 1)
        self.assertEqual(result["archive"]["status_counts"]["exact-archive-member-observed"], 1)
        self.assertEqual(result["archive"]["member_hash_evidence"][0]["member_sha256"],
                         hashlib.sha256(b"member-secret-data").hexdigest())
        self.assertEqual(result["provenance"]["elf"]["sha256"],
                         hashlib.sha256(self.elf.read_bytes()).hexdigest())
        payload = json.dumps(audit(self.build))
        for private in (str(self.root), "adc.o", "drivers.a", "member-secret-data"):
            self.assertNotIn(private, payload)
        self.assertEqual(audit(self.build), audit(self.build))

    def test_zero_fill_and_uncovered_ranges(self):
        self.write_map(line=" .bss.adc 0x00004040 0x10 lib/drivers.a(adc.o)\n")
        self.assertEqual(self.report()["elf_ranges"]["section_ranges"]["zero-fill-vma"], 1)
        self.write_map(line=" .text.outside 0x00005000 0x10 lib/drivers.a(adc.o)\n")
        self.assertEqual(self.report()["status"], "elf-range-conflict-or-unknown")
        self.assertEqual(self.report()["elf_ranges"]["section_ranges"]["uncovered"], 1)

    def test_malformed_elf_is_unknown_and_missing_evidence(self):
        self.elf.write_bytes(b"not an ELF")
        self.assertEqual(self.report()["status"], "unsupported-or-invalid-elf")
        self.elf.unlink()
        result = self.report()
        self.assertEqual(result["status"], "missing-elf")
        self.assertIsNone(result["provenance"]["elf"])

    def test_elf_bounds_rejection(self):
        blob = bytearray(elf_bytes())
        # ELF32 p_filesz at program header offset 52+16
        struct.pack_into("<I", blob, 52 + 16, 0xffffffff)
        self.elf.write_bytes(blob)
        with self.assertRaises(InputError):
            load_segments(self.elf)

    def test_map_and_elf_tampering_after_graph_capture_is_rejected(self):
        snapshot = collect(self.build)
        self.map.write_text(self.map.read_text() + " .text.new 0x4000 0x1 other.o\n")
        with self.assertRaisesRegex(InputError, "differs"):
            audit(self.build, graph=snapshot)
        self.write_map()
        snapshot = collect(self.build)
        self.elf.write_bytes(self.elf.read_bytes() + b"extra")
        with self.assertRaisesRegex(InputError, "differs"):
            audit(self.build, graph=snapshot)

    def test_regular_archive_duplicate_members_rejected_as_ambiguous(self):
        self.archive.write_bytes(b"!<arch>\n"
                                 + ar_entry("adc.o/", b"first")
                                 + ar_entry("adc.o/", b"second"))
        info = observe_member(self.archive, "adc.o")
        self.assertEqual(info["status"], "ambiguous-member")
        self.assertEqual(self.report()["archive"]["status_counts"]["ambiguous-member"], 1)

    def test_gnu_long_names_and_bsd_extended_member(self):
        table = b"very_long_adc_member_name.o/\n"
        self.archive.write_bytes(b"!<arch>\n"
                                 + ar_entry("//", table)
                                 + ar_entry("/0", b"long-data"))
        self.assertEqual(observe_member(self.archive, "very_long_adc_member_name.o")["status"],
                         "exact-archive-member-observed")
        member = b"bsd_extended_adc_member.o"
        self.archive.write_bytes(b"!<arch>\n"
                                 + ar_entry("#1/" + str(len(member)), member + b"bsd-object"))
        self.assertEqual(observe_member(self.archive, member.decode())["status"],
                         "exact-archive-member-observed")

    def test_thin_and_missing_archive_remain_unknown(self):
        self.archive.write_bytes(b"!<thin>\n")
        self.assertEqual(self.report()["archive"]["status_counts"]["unsupported-thin-archive"], 1)
        self.archive.unlink()
        self.assertEqual(self.report()["archive"]["status_counts"]["unavailable-archive"], 1)

    def test_discarded_only_not_counted_as_map_section(self):
        self.write_map(line="")
        self.assertEqual(self.report()["map_sections"], 0)
        self.assertEqual(self.report()["status"], "no-nonzero-map-input-sections")

    def test_image_isolation(self):
        boot = self.root / "mcuboot"
        (boot / "zephyr").mkdir(parents=True)
        reports = audit(self.root, ["app=build", "boot=mcuboot"])["images"]
        self.assertEqual([r["image"] for r in reports], ["app", "boot"])
        self.assertEqual(reports[0]["status"], "map-elf-vma-ranges-consistent")
        self.assertEqual(reports[1]["status"], "unknown")
        self.assertIn("zephyr/zephyr.map", reports[1]["missing_evidence"])

    def test_cli_and_read_only_binary_evidence(self):
        original = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["link-audit", "--build", str(self.build)]), 0)
        self.assertEqual(json.loads(out.getvalue())["images"][0]["status"],
                         "map-elf-vma-ranges-consistent")
        self.assertEqual(original, {p.relative_to(self.root): p.read_bytes()
                                    for p in self.root.rglob("*") if p.is_file()})


if __name__ == "__main__":
    unittest.main()
