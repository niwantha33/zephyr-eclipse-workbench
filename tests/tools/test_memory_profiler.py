# SPDX-License-Identifier: Apache-2.0
"""GNU ld memory-map parser tests; these are not hardware/MPU validation."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "scripts/memory_profiler.py"
SPEC = importlib.util.spec_from_file_location("memory_profiler", SOURCE)
profiler = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(profiler)

GNU_MAP = """Archive member included to satisfy reference by file (symbol)

Discarded input sections
 .text.debug    0x00000000        0x40 debug.o

Memory Configuration

Name             Origin             Length             Attributes
FLASH            0x00000000         0x00010000         xr
SRAM             0x20000000         0x00008000         xrw
*default*        0x00000000         0xffffffff

Linker script and memory map
LOAD main.o
.text             0x00000000       0x60
 *(.text*)
 .text.main       0x00000000       0x60 main.o
.data             0x20000000       0x10 load address 0x00000060
 .data.main       0x20000000       0x10 main.o
.bss              0x20000010       0x20
 .bss.main        0x20000010       0x20 main.o
"""


class MemoryProfilerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="zew memory Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.map = self.root / "zephyr.map"
        self.map.write_text(GNU_MAP, encoding="utf-8")

    def test_regions_and_end_exclusive(self):
        result = profiler.inspect_map(self.map)
        self.assertEqual(result["status"], "memory-regions-observed")
        flash, ram = result["memory_regions"]
        self.assertEqual((flash["kind"], flash["origin"], flash["end_exclusive"]),
                         ("flash", 0, 0x10000))
        self.assertEqual((ram["kind"], ram["origin"], ram["end_exclusive"]),
                         ("ram", 0x20000000, 0x20008000))
        self.assertEqual(flash["linker_attributes"], "xr")
        self.assertEqual(len(result["memory_regions"]), 2)  # not *default*

    def test_sections_distinguish_vma_from_lma_and_input_rows(self):
        records = profiler.inspect_map(self.map)["output_sections"]
        self.assertEqual([r["name"] for r in records], [".text", ".data", ".bss"])
        self.assertEqual([r["vma_region"] for r in records], ["FLASH", "SRAM", "SRAM"])
        self.assertEqual(records[1]["load_address"], 0x60)
        self.assertEqual(records[1]["lma_region"], "FLASH")
        self.assertEqual(records[1]["load_end_exclusive"], 0x70)

    def test_mpu_is_unavailable_even_if_linker_has_xrw_flags(self):
        result = profiler.inspect_map(self.map)["mpu"]
        self.assertEqual(result["status"], "not-determinable-from-linker-map")
        self.assertEqual(result["regions"], [])
        self.assertEqual(result["confidence"], "unknown")

    def test_missing_memory_table_and_unmapped_sections_are_not_fabricated(self):
        self.map.write_text("Linker script and memory map\n.text 0x1000 0x20\n")
        result = profiler.inspect_map(self.map)
        self.assertEqual(result["status"], "memory-regions-unavailable")
        self.assertEqual(result["memory_regions"], [])
        self.assertIsNone(result["output_sections"][0]["vma_region"])

    def test_wrapped_output_sections(self):
        self.map.write_text(GNU_MAP + ".rodata\n 0x00000080 0x10\n")
        result = profiler.inspect_map(self.map)
        self.assertEqual(result["output_sections"][-1]["name"], ".rodata")

    def test_ambiguous_region_overlap_not_assigned(self):
        self.map.write_text(GNU_MAP.replace("SRAM             0x20000000", "SRAM             0x00000000"))
        result = profiler.inspect_map(self.map)
        self.assertIsNone(result["output_sections"][0]["vma_region"])

    def test_format_errors_are_explicit(self):
        with self.assertRaises(profiler.MapFormatError):
            profiler.parse_map(".text 0x1000 0x20\n")
        with self.assertRaises(profiler.MapFormatError):
            profiler.parse_map(GNU_MAP.replace("SRAM             0x20000000", "SRAM             INVALID"))

    def test_digest_and_deterministic_read_only_json(self):
        before = self.map.read_bytes()
        a = profiler.inspect_map(self.map)
        self.assertEqual(a, profiler.inspect_map(self.map))
        self.assertEqual(a["provenance"]["sha256"], hashlib.sha256(before).hexdigest())
        self.assertEqual(self.map.read_bytes(), before)
        self.assertNotIn(str(self.root), json.dumps(a))

    def test_cli_outputs_valid_json_and_refuses_overwrite(self):
        cmd = [sys.executable, str(SOURCE), "--map", str(self.map)]
        stdout = subprocess.run(cmd, capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(stdout.stdout)["memory_regions"][0]["name"], "FLASH")
        dest = self.root / "memory.json"
        subprocess.run(cmd + ["--output", str(dest)], check=True, capture_output=True)
        self.assertEqual(json.loads(dest.read_text())["mpu"]["regions"], [])
        repeated = subprocess.run(cmd + ["--output", str(dest)], capture_output=True)
        self.assertEqual(repeated.returncode, 2)

    def test_reject_symlinked_map(self):
        link = self.root / "alias.map"
        try:
            link.symlink_to(self.map)
        except (OSError, NotImplementedError):
            self.skipTest("No symlinks on this host")
        with self.assertRaises(profiler.MapFormatError):
            profiler.inspect_map(link)


if __name__ == "__main__":
    unittest.main()
