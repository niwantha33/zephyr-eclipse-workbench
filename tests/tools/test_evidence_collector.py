# SPDX-License-Identifier: Apache-2.0
"""Phase-0 collector contract: actual files, stable bytes, no invented semantics."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from zew_evidence.collector import InputError, collect, diagnose, why
from zew import main


class EvidenceCollectorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-evidence-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        image = self.root / "first image"
        (image / "zephyr").mkdir(parents=True)
        (image / "zephyr/.config").write_text(
            'CONFIG_BOARD_TARGET="nrf52840dk/nrf52840"\nCONFIG_GPIO=y\n'
            '# CONFIG_ADC is not set\nCONFIG_WIFI_PASSWORD="dont-export-me"\n'
            'CONFIG_USER_NOTE="secret and \u03A9"\n', encoding="utf-8")
        (image / "zephyr/zephyr.dts").write_text('/ { secret = "do not export"; };\n')
        (image / "zephyr/zephyr.elf").write_bytes(b'\x7fELF' + bytes(16))
        self.image = image

    def test_deterministic_and_no_raw_secrets(self):
        first = collect(self.image)
        second = collect(self.image)
        self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))
        payload = json.dumps(first)
        self.assertNotIn("dont-export-me", payload)
        self.assertNotIn("do not export", payload)
        self.assertNotIn(str(self.root), payload)
        self.assertIn("artifact:default:kconfig", [n["id"] for n in first["nodes"]])
        self.assertEqual(why(first, "CONFIG_GPIO")["why_enabled"], "unknown")
        self.assertEqual(why(first, "CONFIG_ADC")["observations"][0]["observed_value"], "n")
        self.assertEqual(why(first, "CONFIG_USER_NOTE")["observations"][0]["observed_value"], "<redacted>")

    def test_public_id_does_not_export_paths(self):
        cfg = self.image / "zephyr/.config"
        cfg.write_text('CONFIG_BOARD_TARGET="/home/user/private-board"\n'
                       'CONFIG_SOC="C:/Users/person"\n'
                       'CONFIG_GPIO=y\n', encoding="utf-8")
        output = json.dumps(collect(self.image))
        self.assertNotIn("/home/user", output)
        self.assertNotIn("C:/Users", output)
        self.assertIn("<redacted>", output)

    def test_hash_changes_when_build_changes(self):
        first = collect(self.image)
        (self.image / "zephyr/.config").write_text("CONFIG_GPIO=n\n", encoding="utf-8")
        next_graph = collect(self.image)
        self.assertNotEqual(
            next(n["provenance"]["sha256"] for n in first["nodes"] if n["id"] == "artifact:default:kconfig"),
            next(n["provenance"]["sha256"] for n in next_graph["nodes"] if n["id"] == "artifact:default:kconfig"))

    def test_missing_and_receipt_conflict(self):
        (self.image / "zephyr/zephyr.dts").unlink()
        (self.image / ".zew-built-target").write_text("wrong_core")
        findings = diagnose(collect(self.image))["findings"]
        self.assertTrue(any(f["condition"] == "missing" for f in findings))
        self.assertTrue(any(f["condition"] == "conflicting-board-identity" for f in findings))

    def test_explicit_multi_image_and_escape_rejection(self):
        graph = collect(self.root, ["app=first image"])
        self.assertIn("image:app", [n["id"] for n in graph["nodes"]])
        with self.assertRaises(InputError):
            collect(self.root, ["app=../outside"])
        with self.assertRaises(InputError):
            collect(self.root, ["app=first image", "app=first image"])
        with self.assertRaises(InputError):
            why(graph, "--inject")

    def test_read_only_cli_with_unicode_path(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["graph", "--build", str(self.image)]), 0)
        document = json.loads(out.getvalue())
        self.assertEqual(document["schema_version"], "0.1.0")
        self.assertEqual(document["status"], "observations-only")
        self.assertIn("provenance", next(n for n in document["nodes"] if n["type"] == "artifact"))

    def test_symlink_is_not_followed(self):
        link = self.image / "zephyr" / "zephyr.map"
        try:
            link.symlink_to(self.image / "zephyr/.config")
        except (OSError, NotImplementedError):
            self.skipTest("Symlink unavailable on this host")
        with self.assertRaises(InputError):
            collect(self.image)

    def test_source_not_changed(self):
        paths = [(p, p.read_bytes()) for p in self.image.rglob("*") if p.is_file()]
        collect(self.image)
        self.assertEqual(paths, [(p, p.read_bytes()) for p, _ in paths])


if __name__ == "__main__":
    unittest.main()
