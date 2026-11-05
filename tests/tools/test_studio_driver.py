# SPDX-License-Identifier: Apache-2.0
"""Independent levels: a configuration request is never a compiled driver."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from zew_evidence.studio_driver import inspect
from zew_evidence.collector import InputError


class StudioDriverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-driver-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source_root = self.root / "source"
        (self.source_root / "drivers").mkdir(parents=True)
        self.source = self.source_root / "drivers/adc.c"
        self.source.write_text("int main(void) { return 0; }\n")
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "zephyr/.config").write_text("CONFIG_ADC=y\n")
        self.obj = self.build / "adc.o"
        self.obj.write_bytes(b"labelled fixture object bytes; not a real compiler result")
        self.database = self.build / "compile_commands.json"
        self.database.write_text(json.dumps([{
            "directory": str(self.build), "file": str(self.source),
            "output": "adc.o", "arguments": ["cc", "-c", str(self.source), "-o", "adc.o"]}]))
        self.map = self.build / "zephyr/zephyr.map"
        self.map.write_text("Linker script and memory map\nLOAD adc.o\n"
                            " .text.main 0x1000 0x20 adc.o\n")

    def scan(self, symbol="CONFIG_ADC"):
        return inspect(self.build, "drivers/adc.c", self.source_root, symbol)

    def test_configured_command_object_map_and_runtime_stay_separate(self):
        row = self.scan()["images"][0]
        self.assertEqual(row["candidate"]["state"], "explicitly-selected-source")
        self.assertEqual(row["configured"]["state"], "enabled")
        self.assertEqual(row["compile_command"]["state"], "compile-command-observed")
        self.assertEqual(row["compiled"]["state"], "object-bytes-observed")
        self.assertEqual(row["compiled"]["compiler_execution"], "unknown")
        self.assertEqual(row["linked"]["state"], "map-assigned-section-observed")
        self.assertEqual(row["linked"]["final_elf_binding"], "unknown")
        self.assertEqual(row["initialized"]["state"], "unknown")
        self.assertEqual(row["causality"]["kconfig_to_source"], "unverified")

    def test_load_only_and_discarded_do_not_become_linked_sections(self):
        self.map.write_text("Discarded input sections\n .text 0 0x40 adc.o\n"
                            "Linker script and memory map\nLOAD adc.o\n")
        self.assertEqual(self.scan()["images"][0]["linked"]["state"], "map-load-only")
        self.map.write_text("Discarded input sections\n .text 0 0x40 adc.o\n"
                            "Linker script and memory map\n")
        self.assertEqual(self.scan()["images"][0]["linked"]["state"], "unknown")

    def test_enabled_config_does_not_invent_command_or_object(self):
        self.database.unlink()
        row = self.scan()["images"][0]
        self.assertEqual(row["configured"]["state"], "enabled")
        self.assertEqual(row["compiled"]["state"], "unknown")
        self.assertEqual(row["linked"]["state"], "unknown")

    def test_missing_object_preserves_command_only(self):
        self.obj.unlink()
        row = self.scan()["images"][0]
        self.assertEqual(row["compile_command"]["state"], "compile-command-observed")
        self.assertEqual(row["compiled"]["state"], "unknown")

    def test_config_disabled_does_not_erase_observed_object(self):
        (self.build / "zephyr/.config").write_text("# CONFIG_ADC is not set\n")
        row = self.scan()["images"][0]
        self.assertEqual(row["configured"]["state"], "disabled")
        self.assertEqual(row["compiled"]["state"], "object-bytes-observed")
        self.assertEqual(row["initialized"]["state"], "unknown")

    def test_sensitive_non_boolean_value_never_exposed(self):
        (self.build / "zephyr/.config").write_text('CONFIG_PASSWORD="dont-export"\n')
        report = self.scan("CONFIG_PASSWORD")
        self.assertEqual(report["images"][0]["configured"]["state"], "not-observed-or-redacted")
        self.assertNotIn("dont-export", json.dumps(report))
        self.assertNotIn(str(self.root), json.dumps(report))

    def test_read_only_deterministic_and_hash_anchored(self):
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        report = self.scan()
        self.assertEqual(report, self.scan())
        self.assertEqual(report["images"][0]["compiled"]["object_sha256"],
                         hashlib.sha256(self.obj.read_bytes()).hexdigest())
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_invalid_selection_rejected(self):
        with self.assertRaises(InputError):
            self.scan("ADC || HACK")
        with self.assertRaises(InputError):
            inspect(self.build, "../adc.c", self.source_root)

    def test_explicit_images_do_not_leak_application_state_into_boot(self):
        boot = self.root / "boot"
        (boot / "zephyr").mkdir(parents=True)
        report = inspect(self.root, "drivers/adc.c", self.source_root, "CONFIG_ADC",
                         ["app=build", "boot=boot"])
        self.assertEqual([row["image"] for row in report["images"]], ["app", "boot"])
        self.assertEqual(report["images"][1]["compiled"]["state"], "unknown")
        self.assertEqual(report["images"][1]["configured"]["state"], "generated-config-missing")

    def test_real_host_compiler_is_only_build_artifact_evidence(self):
        cc = shutil.which("cc")
        if cc is None:
            self.skipTest("Host C compiler unavailable; fixture tests still apply")
        # Keep the Unicode/spaced fixture directory, but pass relative source
        # and output paths to legacy MinGW tools whose argv uses an ANSI code
        # page. The evidence adapter still inspects the real Unicode paths.
        subprocess.run([cc, "-ffunction-sections", "-c", "../source/drivers/adc.c", "-o", "adc.o"],
                       cwd=self.build, check=True, capture_output=True, timeout=20)
        subprocess.run([cc, "adc.o", "-Wl,-Map=zephyr/zephyr.map",
                        "-o", "zephyr/zephyr.elf"],
                       cwd=self.build, check=True, capture_output=True, timeout=20)
        row = self.scan()["images"][0]
        self.assertEqual(row["compiled"]["state"], "object-bytes-observed")
        self.assertEqual(row["linked"]["state"], "map-assigned-section-observed")
        self.assertEqual(row["initialized"]["state"], "unknown")


if __name__ == "__main__":
    unittest.main()
