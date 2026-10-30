# SPDX-License-Identifier: Apache-2.0
"""Compilation entries are observations, not proof the compiler or linker ran."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from zew import main
from zew_evidence.collector import InputError, collect
from zew_evidence.compilation import inspect


class CompilationEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-compile-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sources = self.root / "my sources"
        (self.sources / "drivers").mkdir(parents=True)
        self.source = self.sources / "drivers/adc.c"
        self.source.write_text("int adc(void) { return 42; }", encoding="utf-8")
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "zephyr/.config").write_text("CONFIG_GPIO=y\n", encoding="utf-8")
        self.db = self.build / "compile_commands.json"
        self.rows = [
            {"directory": str(self.build), "file": str(self.source),
             "command": "cc -DPRIVATE_PASSWORD=secret-value -c drivers/adc.c"},
            {"directory": str(self.build), "file": str(self.source),
             "arguments": ["cc", "-c", str(self.source)]},
            {"directory": "C:/unavailable-other-host", "file": "drivers/adc.c",
             "command": "cc -c drivers/adc.c"},
        ]
        self.save()

    def save(self):
        self.db.write_text(json.dumps(self.rows), encoding="utf-8")

    def test_exact_source_snapshot_privacy_and_determinism(self):
        before = self.db.read_bytes()
        result = inspect(self.build, "drivers/adc.c", self.sources)
        report = result["images"][0]
        self.assertEqual(report["status"], "compile-command-observed")
        self.assertEqual(report["matching_commands"], 2)
        self.assertEqual(report["total_entries"], 3)
        self.assertEqual(report["provenance"]["sha256"], hashlib.sha256(before).hexdigest())
        self.assertEqual(report["provenance"]["node_id"], "artifact:default:compiler_database")
        text = json.dumps(result)
        self.assertNotIn("secret-value", text)
        self.assertNotIn("PRIVATE_PASSWORD", text)
        self.assertNotIn(str(self.root), text)
        self.assertNotIn("drivers/adc.c", text)
        self.assertEqual(before, self.db.read_bytes())
        self.assertEqual(result, inspect(self.build, "drivers/adc.c", self.sources))

    def test_no_entry_is_unknown_and_does_not_mean_excluded(self):
        self.rows = [self.rows[2]]
        self.save()
        report = inspect(self.build, "drivers/adc.c", self.sources)["images"][0]
        self.assertEqual(report["status"], "no-matching-command-observed")
        self.assertEqual(report["confidence"], "unknown")
        self.assertIn("unknown", json.dumps(inspect(self.build, "drivers/adc.c", self.sources)["limitations"]))

    def test_missing_database_and_exact_file_paths(self):
        self.db.unlink()
        report = inspect(self.build, "drivers/adc.c", self.sources)["images"][0]
        self.assertEqual(report["status"], "missing-compiler-database")
        self.assertIsNone(report["provenance"])
        with self.assertRaises(InputError):
            inspect(self.build, "../private.c", self.sources)
        with self.assertRaises(InputError):
            inspect(self.build, "missing.c", self.sources)

    def test_tamper_after_collector_hash_fails_closed(self):
        snapshot = collect(self.build)
        self.rows = [self.rows[2]]
        self.save()
        with self.assertRaisesRegex(InputError, "differs"):
            inspect(self.build, "drivers/adc.c", self.sources, graph=snapshot)

    def test_hostile_or_malformed_json_is_rejected(self):
        samples = [
            '{"file":"invalid"}',
            '[{"file":"one","file":"two","directory":"/tmp","command":"cc"}]',
            '[{"file":"one","directory":"/tmp"}]',
            '[true]',
            "not valid JSON",
            "[",
            "[] " * 60,
        ]
        for content in samples:
            with self.subTest(content=content[:35]):
                self.db.write_text(content, encoding="utf-8")
                with self.assertRaises(InputError):
                    inspect(self.build, "drivers/adc.c", self.sources)

    def test_relative_compile_path_resolved_against_exact_directory(self):
        self.rows = [{"directory": str(self.sources), "file": "drivers/adc.c", "command": "cc -c drivers/adc.c"}]
        self.save()
        self.assertEqual(inspect(self.build, "drivers/adc.c", self.sources)["images"][0]["matching_commands"], 1)

    def test_cli_enriches_spdx_why_without_asserting_elf_inclusion(self):
        for args in (["compile", "--source", "drivers/adc.c", "--source-root", str(self.sources)],
                     ["why", "--source", "drivers/adc.c", "--source-root", str(self.sources)]):
            with self.subTest(command=args[0]):
                out = io.StringIO()
                with contextlib.redirect_stdout(out):
                    self.assertEqual(main([*args, "--build", str(self.build)]), 0)
                parsed = json.loads(out.getvalue())
                report = parsed["images"][0]
                if args[0] == "why":
                    report = report["compile_database"]
                self.assertEqual(report["status"], "compile-command-observed")
                self.assertNotIn("secret-value", out.getvalue())
                self.assertNotIn(str(self.sources), out.getvalue())

    def test_image_isolation(self):
        other = self.root / "boot"
        other.mkdir()
        out = inspect(self.root, "drivers/adc.c", self.sources, ["app=build", "boot=boot"])
        self.assertEqual([x["image"] for x in out["images"]], ["app", "boot"])
        self.assertEqual(out["images"][0]["matching_commands"], 2)
        self.assertEqual(out["images"][1]["status"], "missing-compiler-database")


if __name__ == "__main__":
    unittest.main()
