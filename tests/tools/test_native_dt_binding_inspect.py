# SPDX-License-Identifier: Apache-2.0
"""Packaged native EDT probe: exact checkout, privacy, no user/build mutations."""
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
from unittest import TestCase, mock

SCRIPT = (Path(__file__).resolve().parents[2] /
          "plugins/io.github.niwantha33.zew.ui/scripts/dt_binding_inspect.py")
spec = importlib.util.spec_from_file_location("zew_ui_dt_binding", SCRIPT)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class FakeEDT:
    def __init__(self, filename, binding_roots):
        self.text = Path(filename).read_text(encoding="utf-8")
        self.roots = binding_roots
        if "BAD_VENDOR_SECRET" in self.text:
            raise RuntimeError("personal-secret:/very/private/path")

    def get_node(self, name):
        if name == "/missing":
            raise ValueError("Unknown private path")
        binding = None if "UNKNOWN_BINDING" in self.text else "/private/absolute/binding.yaml"
        props = {} if "NO_PROPERTY" in self.text else {
            "gpios": SimpleNamespace(type="phandle-array")}
        return SimpleNamespace(path=name, binding_path=binding, status="okay", props=props)


class NativeDtBindingTests(TestCase):
    def setUp(self):
        context = tempfile.TemporaryDirectory(prefix="zew-ui-dts-Ω ")
        self.addCleanup(context.cleanup)
        self.root = Path(context.name)
        self.zephyr = self.root / "zephyr checkout"
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        lib = self.zephyr / "scripts/dts/python-devicetree/src/devicetree/edtlib.py"
        lib.parent.mkdir(parents=True)
        lib.write_text("# selected Zephyr fixture\n", encoding="utf-8")
        self.app = self.root / "application"
        self.app.mkdir()
        self.build = self.root / "build"
        (self.build / "zephyr").mkdir(parents=True)
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/;\n/ { };\n", encoding="utf-8")
        self.cache = self.build / "CMakeCache.txt"
        self.cache.write_text(f"ZEPHYR_BASE:PATH={self.zephyr}\n", encoding="utf-8")
        self.overlay = self.app / "app.overlay"
        self.overlay.write_text("&{/soc/gpio@4000} { status = \"okay\"; };\n", encoding="utf-8")
        fake_package = SimpleNamespace(edtlib=SimpleNamespace(EDT=FakeEDT))
        patch = mock.patch.dict(sys.modules, {"devicetree": fake_package})
        patch.start()
        self.addCleanup(patch.stop)

    def test_exact_build_and_saved_overlay_both_readonly(self):
        before = self.dts.read_bytes(), self.cache.read_bytes(), self.overlay.read_bytes()
        result = probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000", "gpios")
        self.assertEqual(result[0], "interpreted")
        self.assertEqual(result[7], "none")
        preview = probe.evaluate(self.build, self.zephyr, self.app,
                                 "/soc/gpio@4000", "gpios", self.overlay)
        self.assertEqual(preview[0], "interpreted")
        self.assertEqual(preview[7], "previewed")
        self.assertEqual((self.dts.read_bytes(), self.cache.read_bytes(),
                          self.overlay.read_bytes()), before)
        self.assertNotIn(str(self.root), "\n".join(preview))

    def test_missing_property_binding_and_node_not_misrepresented(self):
        self.dts.write_text("UNKNOWN_BINDING\n", encoding="utf-8")
        result = probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000", "gpios")
        self.assertEqual(result[0], "incomplete")
        self.assertEqual(result[3], "unmatched")
        self.dts.write_text("NO_PROPERTY\n", encoding="utf-8")
        result = probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000", "gpios")
        self.assertEqual(result[4], "not-interpreted")
        result = probe.evaluate(self.build, self.zephyr, self.app, "/missing")
        self.assertEqual(result[2], "missing")
        self.assertEqual(result[0], "incomplete")

    def test_overlay_edt_error_is_redacted(self):
        self.overlay.write_text("BAD_VENDOR_SECRET\n", encoding="utf-8")
        result = probe.evaluate(self.build, self.zephyr, self.app,
                                "/soc/gpio@4000", "gpios", self.overlay)
        self.assertEqual(result[0], "rejected")
        self.assertEqual(result[8], "edtlib-rejected-overlay")
        self.assertNotIn("BAD_VENDOR_SECRET", str(result))
        self.assertNotIn("private", str(result))

    def test_generated_config_must_match_selected_checkout(self):
        another = self.root / "other/zephyr"
        another.mkdir(parents=True)
        self.cache.write_text(f"ZEPHYR_BASE:PATH={another}\n", encoding="utf-8")
        result = probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000")
        self.assertEqual(result[0], "unavailable")
        self.assertEqual(result[8], "checkout-mismatch")

    def test_reject_untrusted_overlay_path_and_cpp(self):
        outside = self.root / "outside.overlay"
        outside.write_text("BAD_VENDOR_SECRET\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unsafe-overlay-path"):
            probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000",
                           overlay=outside)
        self.overlay.write_text("#include <secret/vendor.h>\n", encoding="utf-8")
        result = probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000",
                                overlay=self.overlay)
        self.assertEqual(result[8], "preprocessing-required")

    def test_invalid_path_encoding_and_bounded_inputs(self):
        with self.assertRaisesRegex(ValueError, "invalid-node"):
            probe.evaluate(self.build, self.zephyr, self.app, "../../secret")
        self.overlay.write_bytes(b"x" * (probe.MAX_OVERLAY + 1))
        with self.assertRaisesRegex(ValueError, "input-too-large"):
            probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000",
                           overlay=self.overlay)
        self.overlay.write_bytes(b"\xff")
        with self.assertRaises(UnicodeError):
            probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000",
                           overlay=self.overlay)

    def test_wire_has_bounded_fields_and_no_direct_values(self):
        result = probe.evaluate(self.build, self.zephyr, self.app, "/soc/gpio@4000")
        out = io.StringIO()
        with mock.patch("sys.stdout", out):
            probe.wire(result)
        self.assertTrue(out.getvalue().startswith("ZEW-DTBIND-1\n"))
        self.assertNotIn(str(self.root), out.getvalue())
        self.assertNotIn("gpio@4000", out.getvalue())
        self.assertEqual(len(out.getvalue().splitlines()[1].split("\t")), 9)
