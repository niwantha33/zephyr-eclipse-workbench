# SPDX-License-Identifier: Apache-2.0
"""CI probe contract tests; actual compiler execution is in separate smoke/matrix."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.evidence_firmware_probe import validate
from test_output_section_audit import fixture


class RealFirmwareProbeContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-probe-scope-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "test application"
        (self.app / "src").mkdir(parents=True)
        (self.app / "src/main.c").write_text("int main(void){return 0;}\n", encoding="utf-8")
        self.build = self.root / "generated"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "zephyr/.config").write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        (self.build / "zephyr/zephyr.elf").write_bytes(fixture())
        (self.build / "zephyr/zephyr.map").write_text(
            "Linker script and memory map\n"
            ".text 0x4000 0x20\n"
            " .text.fn 0x4000 0x10 fake.o\n", encoding="utf-8")
        (self.build / "compile_commands.json").write_text(json.dumps([
            {"directory": str(self.build),
             "file": str(self.app / "src/main.c"),
             "command": "cc -DPRIVATE_SECRET=dont-share -c src/main.c"}]))

    def test_probe_is_deterministic_read_only_and_unprivileged(self):
        before = {p.relative_to(self.root): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in self.root.rglob("*") if p.is_file()}
        first = validate(self.build, self.app)
        self.assertEqual(first, validate(self.build, self.app))
        self.assertEqual(first["compilation"]["status"], "compile-command-observed")
        self.assertEqual(first["link_range"]["map_sections"], 1)
        self.assertEqual(first["elf_sections"]["status"], "map-elf-output-layout-observed")
        self.assertIn("no runtime or flashed-device", first["claim_limit"])
        payload = json.dumps(first)
        self.assertNotIn(str(self.root), payload)
        self.assertNotIn("dont-share", payload)
        after = {p.relative_to(self.root): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_missing_artifact_refuses_false_verification(self):
        (self.build / "zephyr/zephyr.elf").unlink()
        with self.assertRaisesRegex(ValueError, "Missing mandatory"):
            validate(self.build, self.app)

    def test_corrupt_elf_cannot_claim_success(self):
        (self.build / "zephyr/zephyr.elf").write_bytes(b"bad ELF")
        with self.assertRaises(ValueError):
            validate(self.build, self.app)


if __name__ == "__main__":
    unittest.main()
