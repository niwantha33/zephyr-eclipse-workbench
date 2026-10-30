# SPDX-License-Identifier: Apache-2.0
"""Evidence levels: command, object bytes, map LOAD, map section; no ELF proof."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError, collect
from zew_evidence.linker import inspect


class LinkerEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-linker-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source_root = self.root / "source tree"
        (self.source_root / "drivers").mkdir(parents=True)
        self.source = self.source_root / "drivers/my adc.c"
        self.source.write_text("int adc(void) { return 4; }", encoding="utf-8")
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "zephyr/.config").write_text("CONFIG_ADC=y\nCONFIG_PASSWORD=private\n")
        self.object = self.build / "CMakeFiles" / "app.dir" / "drivers" / "my adc.c.o"
        self.object.parent.mkdir(parents=True)
        self.object.write_bytes(b"object-binary-bytes")
        self.compdb = self.build / "compile_commands.json"
        self.entry = {"directory": str(self.build), "file": str(self.source),
                      "output": self.object.relative_to(self.build).as_posix(),
                      "command": "cc -DPASSWORD=leak-me -o 'private' -c my_adc.c"}
        self.rows = [self.entry]
        self.write_database()
        self.map = self.build / "zephyr/zephyr.map"
        self.write_map()

    def write_database(self):
        self.compdb.write_text(json.dumps(self.rows), encoding="utf-8")

    def write_map(self, *, section=True, load=True):
        rel = self.object.relative_to(self.build).as_posix()
        self.map.write_text(
            "Archive member included to satisfy reference by file (symbol)\n"
            "Discarded input sections\n"
            " .text.discarded 0x00000000 0x2a " + rel + "\n"
            "Memory Configuration\n"
            "Linker script and memory map\n"
            + ("LOAD " + rel + "\n" if load else "")
            + (" .text.adc 0x00004000 0x20 " + rel + "\n" if section else "")
            + " .data.zero 0x00008000 0x0 " + rel + "\n"
            + " .text.unrelated 0x00004020 0xf unrelated.o\n"
            + " .text.archive 0x00004030 0xff libdrivers.a(my adc.c.o)\n",
            encoding="utf-8")

    def scan(self):
        return inspect(self.build, "drivers/my adc.c", self.source_root)

    def test_exact_object_and_assigned_section_with_hash_provenance(self):
        before = self.map.read_bytes()
        report = self.scan()["images"][0]
        self.assertEqual(report["status"], "map-section-contribution-observed")
        self.assertEqual(report["matching_compile_commands"], 1)
        self.assertEqual(report["distinct_declared_outputs"], 1)
        self.assertEqual(report["observed_object_sha256"],
                         hashlib.sha256(self.object.read_bytes()).hexdigest())
        self.assertEqual(report["map_observation"]["load_entries"], 1)
        self.assertEqual(report["map_observation"]["assigned_section_entries"], 1)
        self.assertEqual(report["map_observation"]["assigned_section_bytes"], 32)
        self.assertEqual(report["provenance"]["linker_map"]["sha256"],
                         hashlib.sha256(before).hexdigest())
        text = json.dumps(self.scan())
        self.assertNotIn("leak-me", text)
        self.assertNotIn("private", text)
        self.assertNotIn(str(self.root), text)
        self.assertNotIn("my adc.c", text)
        self.assertNotIn("CMakeFiles", text)
        self.assertEqual(before, self.map.read_bytes())
        self.assertEqual(self.scan(), self.scan())

    def test_load_only_is_not_section_contribution(self):
        self.write_map(section=False)
        report = self.scan()["images"][0]
        self.assertEqual(report["status"], "map-load-observed")
        self.assertEqual(report["map_observation"]["assigned_section_bytes"], 0)

    def test_discarded_section_is_not_contribution(self):
        self.write_map(section=False, load=False)
        report = self.scan()["images"][0]
        self.assertEqual(report["status"], "unknown-map-contribution")
        self.assertEqual(report["map_observation"]["assigned_section_bytes"], 0)

    def test_missing_declared_output_does_not_guess_object_name(self):
        self.rows = [{k:v for k,v in self.entry.items() if k != "output"}]
        self.write_database()
        self.assertEqual(self.scan()["images"][0]["status"], "missing-unambiguous-object-output")
        self.assertIsNone(self.scan()["images"][0]["observed_object_sha256"])

    def test_ambiguous_outputs_never_select_arbitrary_object(self):
        second = self.build / "second.o"
        second.write_bytes(b"another object")
        new = dict(self.entry)
        new["output"] = "second.o"
        self.rows.append(new)
        self.write_database()
        report = self.scan()["images"][0]
        self.assertEqual(report["status"], "ambiguous-object-outputs")
        self.assertEqual(report["distinct_declared_outputs"], 2)

    def test_object_not_found_and_linker_map_missing(self):
        self.object.unlink()
        self.assertEqual(self.scan()["images"][0]["status"], "declared-object-not-found")
        self.object.write_bytes(b"restored")
        self.map.unlink()
        self.assertEqual(self.scan()["images"][0]["status"], "object-observed-map-missing")

    def test_unknown_map_format_fails_to_claim_contribution(self):
        self.map.write_text("LLD text is not GNU ld linker map\n"
                            " .text.foo 0x1000 0x10 CMakeFiles/app.dir/drivers/my adc.c.o\n",
                            encoding="utf-8")
        record = self.scan()["images"][0]
        self.assertEqual(record["status"], "unsupported-map-format")
        self.assertEqual(record["map_observation"]["assigned_section_bytes"], 0)

    def test_map_hash_changed_since_graph_snapshot_rejected(self):
        graph = collect(self.build)
        self.map.write_text(self.map.read_text() + "\nthird-party-changed-map\n")
        with self.assertRaisesRegex(InputError, "differs"):
            inspect(self.build, "drivers/my adc.c", self.source_root, graph=graph)

    def test_symlink_object_rejected(self):
        original = self.root / "external-object"
        original.write_bytes(b"malicious")
        self.object.unlink()
        try:
            self.object.symlink_to(original)
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks not supported")
        with self.assertRaises(InputError):
            self.scan()

    def test_image_isolation_missing_boot_map(self):
        boot = self.root / "boot"
        (boot / "zephyr").mkdir(parents=True)
        report = inspect(self.root, "drivers/my adc.c", self.source_root,
                         ["app=build", "boot=boot"])["images"]
        self.assertEqual([x["image"] for x in report], ["app", "boot"])
        self.assertEqual(report[0]["status"], "map-section-contribution-observed")
        self.assertEqual(report[1]["status"], "unknown")
        self.assertIsNone(report[1]["provenance"]["linker_map"])

    def test_command_and_optional_why_enrichment(self):
        for args in (["link", "--source", "drivers/my adc.c", "--source-root", str(self.source_root)],
                     ["why", "--source", "drivers/my adc.c", "--source-root", str(self.source_root),
                      "--link-evidence"]):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(main([*args, "--build", str(self.build)]), 0)
            parsed = json.loads(output.getvalue())
            result = parsed["images"][0]
            if args[0] == "why":
                result = result["linker_map"]
            self.assertEqual(result["status"], "map-section-contribution-observed")
            self.assertNotIn("leak-me", output.getvalue())
            self.assertNotIn(str(self.root), output.getvalue())


if __name__ == "__main__":
    unittest.main()
