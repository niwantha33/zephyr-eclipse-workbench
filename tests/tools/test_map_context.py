# SPDX-License-Identifier: Apache-2.0
"""GNU map parent/child context: allocated is not the same as nonallocated."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest

from test_output_section_audit import fixture
from zew import main
from zew_evidence.collector import InputError, collect
from zew_evidence.elf_sections import inspect_sections
from zew_evidence.map_context import _records, audit


class GnuMapContextTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="zew-parent-map-Ω ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        self.elf = self.build / "zephyr/zephyr.elf"
        self.elf.write_bytes(fixture())
        self.map = self.build / "zephyr/zephyr.map"
        self.write_map()

    def write_map(self, body=None):
        if body is None:
            body = (
                "LOAD CMakeFiles/app.dir/main.o\n"
                ".text 0x00004000 0x20\n"
                " *(.text*)\n"
                " .text.main 0x00004000 0x10 CMakeFiles/app.dir/main.o\n"
                " .text.other\n"
                "             0x00004010 0x10 libkernel.a(other.o)\n"
            )
        self.map.write_text(
            "Discarded input sections\n"
            " .text.dead 0x00003000 0x11 CMakeFiles/app.dir/dead.o\n"
            "Memory Configuration\n"
            "Linker script and memory map\n" + body,
            encoding="utf-8")

    def report(self):
        return audit(self.build)["images"][0]

    def test_exact_elf_output_parent_contains_two_input_section_rows(self):
        result = self.report()
        self.assertEqual(result["status"], "gnu-map-output-context-partially-observed")
        self.assertEqual(result["confidence"], "derived")
        self.assertEqual(result["context"]["output_headers"], 1)
        self.assertEqual(result["context"]["supported_input_rows"], 2)
        self.assertEqual(result["context"]["output_header_classes"]["allocated-exact-output"], 1)
        self.assertEqual(result["context"]["input_context_classes"]["within-allocated-output"], 2)
        self.assertEqual(result["context"]["input_context_classes"]["parent-unobserved"], 0)
        data = json.dumps(audit(self.build))
        for private in (str(self.root), "main.o", "other.o", "libkernel", ".text.main"):
            self.assertNotIn(private, data)
        self.assertEqual(audit(self.build), audit(self.build))

    def test_not_allocated_elf_output_is_metadata_never_linked_content(self):
        blob = bytearray(fixture())
        # ELF32 section 1 sh_flags; clear SHF_ALLOC and SHF_EXECINSTR.
        struct.pack_into("<I", blob, 0x180 + 40 + 8, 0)
        self.elf.write_bytes(blob)
        self.assertEqual(inspect_sections(self.elf), [])
        expanded = inspect_sections(self.elf, include_unallocated=True)
        self.assertEqual(expanded[0]["allocated"], False)
        self.assertIsNone(expanded[0]["sha256"])
        result = self.report()
        self.assertEqual(result["context"]["output_header_classes"]["nonallocated-exact-output"], 1)
        self.assertEqual(result["context"]["input_context_classes"]["within-nonallocated-output"], 2)
        self.assertEqual(result["context"]["input_context_classes"]["within-allocated-output"], 0)

    def test_nonallocated_output_cannot_be_assumed_when_absent_from_elf(self):
        self.write_map(
            ".nonalloc 0x00000000 0x10\n"
            " .comment 0x00000000 0x10 lib.a(meta.o)\n")
        result = self.report()
        self.assertEqual(result["context"]["output_header_classes"]["absent-elf-output"], 1)
        self.assertEqual(result["context"]["input_context_classes"]["parent-metadata-unresolved"], 1)
        self.assertEqual(result["confidence"], "unknown")

    def test_explicit_parent_bounds_must_contain_input_range(self):
        self.write_map(
            ".text 0x4000 0x20\n"
            " .text.late 0x00005000 0x10 obj.o\n")
        result = self.report()
        self.assertEqual(result["context"]["input_context_classes"]["outside-parent-bounds"], 1)
        self.assertEqual(result["context"]["input_context_classes"]["within-allocated-output"], 0)
        self.assertEqual(result["confidence"], "unknown")

    def test_unknown_root_line_ends_parent_and_prevents_false_inclusion(self):
        self.write_map(
            ".text 0x4000 0x20\n"
            " .text.main 0x4000 0x10 obj.o\n"
            "LOAD unrelated.o\n"
            " .text.unanchored 0x4000 0x10 other.o\n")
        result = self.report()
        self.assertEqual(result["context"]["input_context_classes"]["within-allocated-output"], 1)
        self.assertEqual(result["context"]["input_context_classes"]["parent-unobserved"], 1)

    def test_duplicate_parent_and_elf_output_names_remain_ambiguous(self):
        self.write_map(
            ".text 0x4000 0x20\n"
            " .text.one 0x4000 0x10 a.o\n"
            ".text 0x4000 0x20\n"
            " .text.two 0x4010 0x10 b.o\n")
        result = self.report()
        self.assertEqual(result["context"]["output_header_classes"]["ambiguous-output-name"], 2)
        self.assertEqual(result["context"]["input_context_classes"]["parent-metadata-unresolved"], 2)

    def test_unsupported_map_and_missing_elf_are_not_confirmed(self):
        self.map.write_text("LLVM linker map without GNU ld header\n", encoding="utf-8")
        self.assertEqual(self.report()["status"], "unsupported-map-format")
        self.write_map()
        self.elf.unlink()
        self.assertEqual(self.report()["status"], "missing-elf")

    def test_snapshot_tampering_is_detected(self):
        snapshot = collect(self.build)
        self.map.write_text(self.map.read_text() + " .secret 0x4000 0x10 a.o\n")
        with self.assertRaisesRegex(InputError, "differs"):
            audit(self.build, graph=snapshot)
        self.write_map()
        snapshot = collect(self.build)
        self.elf.write_bytes(self.elf.read_bytes() + b"new")
        with self.assertRaisesRegex(InputError, "differs"):
            audit(self.build, graph=snapshot)

    def test_reject_fake_input_tokens_and_pre_layout_discard(self):
        self.write_map(
            ".text 0x4000 0x20\n"
            " .text.fake 0x4000 0x10 not-an-object.txt\n"
            " .text.ok 0x4000 0x10 libdrivers.a(adc.o)\n")
        result = self.report()
        self.assertEqual(result["context"]["supported_input_rows"], 1)
        self.assertEqual(result["context"]["input_context_classes"]["within-allocated-output"], 1)

    def test_multibuild_image_isolation_and_native_cli(self):
        other = self.root / "boot"
        (other / "zephyr").mkdir(parents=True)
        records = audit(self.root, ["app=build", "boot=boot"])["images"]
        self.assertEqual([r["image"] for r in records], ["app", "boot"])
        self.assertEqual(records[1]["status"], "unknown")
        before = {p.relative_to(self.root): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in self.root.rglob("*") if p.is_file()}
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(main(["map-context", "--build", str(self.build)]), 0)
        self.assertEqual(json.loads(stdout.getvalue())["images"][0]["status"],
                         "gnu-map-output-context-partially-observed")
        after = {p.relative_to(self.root): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
