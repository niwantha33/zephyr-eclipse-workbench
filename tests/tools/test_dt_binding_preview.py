# SPDX-License-Identifier: Apache-2.0
"""Fixture coverage for explicit Zephyr edtlib preview and no project writes."""
import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError
from zew_evidence import dt_binding_preview as dt


class FakeEDT:
    def __init__(self, dts, bindings):
        self.source = Path(dts).read_text(encoding="utf-8")
        if "bad-overlay" in self.source:
            raise ValueError("private value and path cannot leak")
        self.bindings = bindings

    def get_node(self, path):
        if path != "/soc/gpio@1000":
            raise ValueError("missing node")
        if "missing-binding" in self.source:
            binding = None
        else:
            binding = "/fake/bindings/vendor,gpio.yaml"
        properties = {"ngpios": SimpleNamespace(
            type="int", spec=SimpleNamespace(deprecated=False))}
        if "missing-property" in self.source:
            properties.clear()
        return SimpleNamespace(binding_path=binding, status="okay", props=properties)


class DtPreviewTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="zew-dt-Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.zephyr = self.root / "zephyr"
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree").mkdir(parents=True)
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree/edtlib.py").write_text(
            "# test fixture, not Zephyr\n", encoding="utf-8")
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/;\n/ { soc { gpio@1000 { }; }; };\n", encoding="utf-8")
        self.overlay = self.root / "app.overlay"
        self.overlay.write_text("&{/soc/gpio@1000} { status = \"okay\"; };\n",
                                encoding="utf-8")
        self.node = "/soc/gpio@1000"
        self.patch = mock.patch.object(dt.importlib, "import_module",
                                       return_value=SimpleNamespace(EDT=FakeEDT))
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_built_node_property_is_from_binding_and_values_are_not_exported(self):
        report = dt.inspect(self.build, self.zephyr, self.node, prop="ngpios")
        self.assertEqual(report["status"], "interpreted-preview")
        self.assertEqual(report["images"][0]["built_node"]["property_type"], "int")
        self.assertEqual(report["images"][0]["built_node"]["binding"], "matched")
        self.assertNotIn(str(self.root), json.dumps(report))
        self.assertNotIn("gpio@1000", json.dumps(report))

    def test_overlay_preview_never_writes_to_build(self):
        before = self.dts.read_bytes(), self.overlay.read_bytes()
        report = dt.inspect(self.build, self.zephyr, self.node, "ngpios", self.overlay)
        self.assertEqual(report["images"][0]["state"], "binding-validated-preview")
        self.assertEqual((self.dts.read_bytes(), self.overlay.read_bytes()), before)
        self.assertEqual(report["overlay"]["sha256"], dt.hashlib.sha256(before[1]).hexdigest())

    def test_invalid_overlay_is_reported_without_exception_text(self):
        self.overlay.write_text("bad-overlay PRIVATE-STRING\n", encoding="utf-8")
        report = dt.inspect(self.build, self.zephyr, self.node, "ngpios", self.overlay)
        self.assertEqual(report["images"][0]["state"], "edtlib-rejected-preview")
        self.assertNotIn("PRIVATE-STRING", json.dumps(report))
        self.assertEqual(report["status"], "partial-or-unavailable")

    def test_missing_property_and_unknown_binding_not_false_confirmed(self):
        self.dts.write_text("missing-property\n", encoding="utf-8")
        report = dt.inspect(self.build, self.zephyr, self.node, "ngpios")
        self.assertEqual(report["images"][0]["built_node"]["property"], "not-interpreted-by-binding")
        self.assertEqual(report["status"], "partial-or-unavailable")
        self.dts.write_text("missing-binding\n", encoding="utf-8")
        result = dt.inspect(self.build, self.zephyr, self.node, "ngpios")
        self.assertEqual(result["images"][0]["built_node"]["binding"], "unmatched")
        self.assertEqual(result["status"], "partial-or-unavailable")

    def test_node_not_present(self):
        report = dt.inspect(self.build, self.zephyr, "/no-such-node")
        self.assertEqual(report["images"][0]["built_node"]["state"], "node-not-present")
        self.assertEqual(report["status"], "partial-or-unavailable")

    def test_cache_identity_mismatch_fails_closed(self):
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=/other-version\n", encoding="utf-8")
        report = dt.inspect(self.build, self.zephyr, self.node, "ngpios")
        self.assertEqual(report["images"][0]["state"], "build-checkout-unverified")
        self.assertEqual(report["status"], "partial-or-unavailable")

    def test_symlinks_rejected(self):
        link = self.root / "linked.overlay"
        try:
            link.symlink_to(self.overlay)
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks unavailable")
        with self.assertRaises(InputError):
            dt.inspect(self.build, self.zephyr, self.node, overlay=link)

    def test_preprocessor_overlay_fails_with_next_action(self):
        self.overlay.write_text("#include <vendor-pins.h>\n&gpio0 { status = \"okay\"; };\n",
                                encoding="utf-8")
        with self.assertRaisesRegex(InputError, "preprocessing"):
            dt.inspect(self.build, self.zephyr, self.node, overlay=self.overlay)

    def test_cli_return_codes_and_package_command(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = main(["dt-check", "--build", str(self.build), "--zephyr",
                         str(self.zephyr), "--node", self.node, "--property", "ngpios"])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(stdout.getvalue())["status"], "interpreted-preview")
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = main(["dt-check", "--build", str(self.build), "--zephyr",
                         str(self.zephyr), "--node", "/no-such-node"])
        self.assertEqual(code, 1)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["dt-check", "--build", str(self.build), "--zephyr",
                                   str(self.zephyr), "--node", "../unsafe"]), 2)

    def test_oversized_overlay_and_invalid_utf8(self):
        self.overlay.write_bytes(b"A" * (dt.MAX_OVERLAY_BYTES + 1))
        with self.assertRaises(InputError):
            dt.inspect(self.build, self.zephyr, self.node, overlay=self.overlay)
        self.overlay.write_bytes(b"\xff")
        with self.assertRaises(InputError):
            dt.inspect(self.build, self.zephyr, self.node, overlay=self.overlay)

    def test_multi_image_explicit_only(self):
        # Images are not inferred from folders or a guessed sysbuild topology.
        root = self.root / "sysbuild"
        for name in ("app", "boot"):
            image = root / name
            (image / "zephyr").mkdir(parents=True)
            (image / "zephyr/zephyr.dts").write_bytes(self.dts.read_bytes())
            (image / "CMakeCache.txt").write_text(
                "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n", encoding="utf-8")
        output = dt.inspect(root, self.zephyr, self.node, "ngpios",
                            images=["app=app", "boot=boot"])
        self.assertEqual([i["image"] for i in output["images"]], ["app", "boot"])
        self.assertEqual(output["status"], "interpreted-preview")


if __name__ == "__main__":
    unittest.main()
