# SPDX-License-Identifier: Apache-2.0
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock

from zew_evidence import studio_compare as comparison
from zew_evidence.collector import InputError


class StudioComparisonTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="studio-compare-Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.left, self.right = self.root / "left", self.root / "right"
        for folder in (self.left, self.right):
            (folder / "zephyr").mkdir(parents=True)
            (folder / "zephyr/.config").write_text('CONFIG_GPIO=y\nCONFIG_BOARD_TARGET="board/core"\n')
            (folder / "zephyr/zephyr.dts").write_text('/dts-v1/; / { value = <1>; };')
            (folder / "build_info.yml").write_text('zephyr:\n  version: "4.4.0"\ntoolchain:\n  version: "0.17.0"\n')

    def test_image_scoped_configs_versions_and_determinism(self):
        (self.right / "zephyr/.config").write_text('CONFIG_GPIO=n\nCONFIG_BOARD_TARGET="board/core"\n')
        report = comparison.inspect(self.left, self.right)
        self.assertEqual(report, comparison.inspect(self.left, self.right))
        self.assertEqual(report["images"][0]["versions"]["left"]["values"]["zephyr"], "4.4.0")
        self.assertEqual(report["images"][0]["identity"]["left"]["CONFIG_BOARD_TARGET"], "board/core")
        self.assertEqual(report["kconfig_changes"][0]["right"], "n")
        self.assertIn("unknown", report["causality"])
        self.assertEqual(report["images"][0]["devicetree"]["left"]["state"], "unknown")

    def test_explicit_images_never_cross_compare(self):
        for parent in (self.left, self.right):
            (parent / "boot/zephyr").mkdir(parents=True)
            (parent / "boot/zephyr/.config").write_text('CONFIG_GPIO=n\n')
        result = comparison.inspect(self.left, self.right, ["app=.", "boot=boot"], ["app=."])
        self.assertEqual([(x["image"], x["state"]) for x in result["images"]],
                         [("app", "partial"), ("boot", "removed")])
        with self.assertRaises(InputError):
            comparison.inspect(self.left, self.right, ["app=../right"])

    def test_private_versions_aliases_and_duplicates_are_not_exported(self):
        for payload in ('zephyr:\n  version: "private/path/password"\n',
                        'zephyr: &a {version: "private"}\nx: *a\n',
                        'zephyr: {version: "4.4.0", version: "4.5.0"}\n'):
            (self.left / "build_info.yml").write_text(payload)
            report = comparison.inspect(self.left, self.right)
            self.assertEqual(report["images"][0]["versions"]["left"]["state"], "unknown")
            self.assertNotIn("password", json.dumps(report))

    def test_semantic_dts_is_compared_using_upstream_values_without_literals(self):
        class Tree:
            def __init__(self, path):
                self.value = b"\x00\x00\x00\x02" if "<2>" in Path(path).read_text() else b"\x00\x00\x00\x01"
            def node_iter(self):
                return iter([types.SimpleNamespace(path="/", props={"value": types.SimpleNamespace(
                    type=types.SimpleNamespace(name="NUM"), value=self.value)})])
        (self.right / "zephyr/zephyr.dts").write_text('/dts-v1/; / { value = <2>; };')
        with mock.patch.object(comparison, "_build_checkout", return_value=True), \
             mock.patch.object(comparison, "_selected_edtlib", return_value=self.root), \
             mock.patch.object(comparison.importlib, "import_module", return_value=types.SimpleNamespace(DT=Tree)):
            report = comparison.inspect(self.left, self.right, left_zephyr=self.root, right_zephyr=self.root)
        changes = report["images"][0]["semantic_differences"]
        self.assertEqual(changes[0]["property"], "/:value")
        self.assertEqual(changes[0]["right"]["value_sha256"], hashlib.sha256(b"\0\0\0\2").hexdigest())
        self.assertEqual(changes[0]["cause"], "unknown")

    def test_checkout_mismatch_and_external_includes_are_unknown(self):
        with mock.patch.object(comparison, "_build_checkout", return_value=False):
            report = comparison.inspect(self.left, self.right, left_zephyr=self.root, right_zephyr=self.root)
        self.assertEqual(report["images"][0]["devicetree"]["left"]["reason"], "build-checkout-unverified")
        (self.left / "zephyr/zephyr.dts").write_text('/include/ "/etc/passwd"')
        with mock.patch.object(comparison, "_build_checkout", return_value=True):
            report = comparison.inspect(self.left, self.right, left_zephyr=self.root)
        self.assertEqual(report["images"][0]["devicetree"]["left"]["reason"], "non-self-contained-generated-dts")

    def test_no_build_or_source_mutation(self):
        files = {p: p.read_bytes() for root in (self.left, self.right) for p in root.rglob("*") if p.is_file()}
        comparison.inspect(self.left, self.right)
        self.assertEqual(files, {p: p.read_bytes() for p in files})


if __name__ == "__main__":
    unittest.main()
