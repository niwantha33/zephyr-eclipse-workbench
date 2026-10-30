# SPDX-License-Identifier: Apache-2.0
"""Adversarial offline comparison/package tests; no network or hardware needed."""
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import warnings
import zipfile

from zew import main
from zew_evidence.collector import InputError, collect
from zew_evidence.compare import compare
from zew_evidence.bundle import inspect, preview, write


class OfflineEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="zew-compare-Δ ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.left, self.right = self.root / "working image", self.root / "failed image"
        for folder in (self.left, self.right):
            (folder / "zephyr").mkdir(parents=True)
            (folder / "zephyr/zephyr.elf").write_bytes(b"\x7fELF" + bytes(20))
            (folder / "zephyr/zephyr.dts").write_text("/ { };\n", encoding="utf-8")
        (self.left / "zephyr/.config").write_text(
            'CONFIG_BOARD_TARGET="nrf52840dk/nrf52840"\nCONFIG_GPIO=y\n'
            'CONFIG_SPI=n\nCONFIG_SECRET_TOKEN="password-1"\n', encoding="utf-8")
        (self.right / "zephyr/.config").write_text(
            'CONFIG_BOARD_TARGET="nrf52840dk/nrf52840"\nCONFIG_GPIO=n\n'
            'CONFIG_SPI=y\nCONFIG_SECRET_TOKEN="password-2"\n', encoding="utf-8")

    def test_diff_observations_only_with_private_changes_hidden(self):
        result = compare(collect(self.left), collect(self.right))
        diffs = {s["id"]: s for s in result["kconfig_changes"]}
        self.assertIn("kconfig:default:CONFIG_GPIO", diffs)
        self.assertEqual(diffs["kconfig:default:CONFIG_GPIO"]["left"], "y")
        self.assertEqual(diffs["kconfig:default:CONFIG_GPIO"]["right"], "n")
        self.assertIn("kconfig:default:CONFIG_SPI", diffs)
        self.assertEqual(result["status"], "observed-differences-only")
        self.assertIn("artifact:default:kconfig", [a["id"] for a in result["artifact_changes"]])
        self.assertNotIn("password-1", json.dumps(result))
        self.assertNotIn("password-2", json.dumps(result))
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertTrue(any(x["state"] == "redacted" for x in result["uncomparable"]))
        self.assertEqual(result, compare(collect(self.left), collect(self.right)))

    def test_bundle_preview_write_inspect_reproducible(self):
        graph = collect(self.left)
        plan = preview(graph)
        self.assertEqual(plan["format"], "zew-evidence-0.1")
        self.assertEqual(list(self.root.glob("*.zip")), [])
        first, second = self.root / "proof-1.zip", self.root / "proof-2.zip"
        write(graph, first)
        write(graph, second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        report = inspect(first)
        self.assertEqual(report["status"], "verified-integrity-only")
        self.assertEqual(report["graph_sha256"], plan["files"][0]["sha256"])
        raw = first.read_bytes()
        self.assertNotIn(b"password-1", raw)
        self.assertNotIn(str(self.root).encode(), raw)
        with self.assertRaises(InputError):
            write(graph, first)
        self.assertEqual(raw, first.read_bytes(), "refusal to overwrite must not mutate existing bundle")

    def test_cli_preview_and_explicit_write(self):
        output = self.root / "CLI data.zip"
        preview_text = io.StringIO()
        with contextlib.redirect_stdout(preview_text):
            self.assertEqual(main(["bundle", "--build", str(self.left)]), 0)
        self.assertFalse(output.exists())
        self.assertEqual(json.loads(preview_text.getvalue())["format"], "zew-evidence-0.1")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["bundle", "--build", str(self.left),
                                   "--output", str(output), "--write"]), 0)
        report = io.StringIO()
        with contextlib.redirect_stdout(report):
            self.assertEqual(main(["inspect", "--input", str(output)]), 0)
        self.assertEqual(json.loads(report.getvalue())["status"], "verified-integrity-only")
        diff = io.StringIO()
        with contextlib.redirect_stdout(diff):
            self.assertEqual(main(["compare", "--left", str(self.left),
                                   "--right", str(self.right)]), 0)
        self.assertTrue(json.loads(diff.getvalue())["kconfig_changes"])

    def test_tampered_hash_is_refused(self):
        legitimate = self.root / "good.zip"
        tampered = self.root / "tampered.zip"
        write(collect(self.left), legitimate)
        with zipfile.ZipFile(legitimate) as src, zipfile.ZipFile(tampered, "w") as dst:
            for name in ("manifest.json", "graph.json"):
                contents = src.read(name)
                if name == "graph.json":
                    contents = contents.replace(b"nrf52840dk", b"unexpected")
                dst.writestr(name, contents)
        with self.assertRaisesRegex(InputError, "SHA-256"):
            inspect(tampered)

    def test_traversal_symlink_duplicate_or_compressed_members_refused(self):
        graph = json.dumps(collect(self.left)).encode()
        for scenario in ("traversal", "symlink", "duplicate", "compressed"):
            with self.subTest(scenario=scenario):
                path = self.root / (scenario + ".zip")
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(path, "w") as dst:
                        if scenario == "traversal":
                            dst.writestr("../manifest.json", b"{}")
                            dst.writestr("graph.json", graph)
                        elif scenario == "duplicate":
                            dst.writestr("manifest.json", b"{}")
                            dst.writestr("manifest.json", b"{}")
                            dst.writestr("graph.json", graph)
                        else:
                            dst.writestr("manifest.json", b"{}")
                            info = zipfile.ZipInfo("graph.json")
                            if scenario == "symlink":
                                info.create_system = 3
                                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                            else:
                                info.compress_type = zipfile.ZIP_DEFLATED
                            dst.writestr(info, graph)
                with self.assertRaises(InputError):
                    inspect(path)
                self.assertFalse((self.root.parent / "manifest.json").exists())

    def test_invalid_zip_and_symlinked_input(self):
        invalid = self.root / "not-a-zip.zip"
        invalid.write_bytes(b"garbage")
        with self.assertRaises(InputError):
            inspect(invalid)
        symlink = self.root / "link.zip"
        try:
            symlink.symlink_to(invalid)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink unavailable")
        with self.assertRaises(InputError):
            inspect(symlink)

    def test_bundle_does_not_modify_build(self):
        original = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        write(collect(self.left), self.root / "artifact.zip")
        for name, data in original.items():
            self.assertEqual((self.root / name).read_bytes(), data)


if __name__ == "__main__":
    unittest.main()
