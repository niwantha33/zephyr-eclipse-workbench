# SPDX-License-Identifier: Apache-2.0
"""Adapter fixtures model edtlib typed data, not a vendor pinmux decoder."""
from types import SimpleNamespace as NS
import unittest
from zew_evidence.collector import InputError
from zew_evidence.studio_resources import analyze_edt


def node(path, status="okay", parent=None, props=None):
    return NS(path=path, status=status, parent=parent, props=props or {}, pinctrls=[])


def gpio(controller, pin):
    return NS(type="phandle-array", val=[NS(controller=controller, data={"pin": pin, "flags": 0})])


class ResourceTests(unittest.TestCase):
    def test_same_controller_pin_is_only_possible_conflict(self):
        controller = node("/gpio@1")
        first = node("/leds/one", props={"gpios": gpio(controller, 2)})
        second = node("/buttons/two", props={"input-gpios": gpio(controller, 2)})
        report = analyze_edt(NS(nodes=[second, controller, first]))
        finding = report["findings"][0]
        self.assertEqual(finding["confidence"], "possible")
        self.assertEqual(finding["controller"], "/gpio@1")
        self.assertEqual(len(finding["owners"]), 2)
        self.assertEqual(report, analyze_edt(NS(nodes=[first, controller, second])))

    def test_distinct_controller_or_line_and_disabled_parent_do_not_conflict(self):
        a, b = node("/gpio@1"), node("/gpio@2")
        disabled = node("/disabled", "disabled")
        nodes = [a, b, node("/a", props={"gpios": gpio(a, 2)}),
                 node("/b", props={"gpios": gpio(b, 2)}),
                 node("/c", props={"gpios": gpio(a, 3)}),
                 node("/disabled/child", parent=disabled, props={"gpios": gpio(a, 2)})]
        self.assertEqual(analyze_edt(NS(nodes=nodes))["findings"], [])

    def test_missing_pin_cell_is_unknown_and_raw_pinmux_never_decoded(self):
        controller = node("/gpio@1")
        consumer = node("/a", props={"gpios": NS(type="phandle-array", val=[NS(controller=controller, data={"line": 2})]),
                                     "pinmux": NS(type="array", val=[123])})
        consumer.pinctrls = [NS(name="default", conf_nodes=[node("/pins/group")])]
        report = analyze_edt(NS(nodes=[consumer, controller]))
        self.assertEqual(report["unknown_references"][0]["reason"], "binding-has-no-integer-pin-cell")
        self.assertEqual(report["pinctrl_states"][0]["pad_ownership"], "unknown-vendor-encoding-not-decoded")
        self.assertEqual(report["findings"], [])

    def test_same_node_duplicate_reference_does_not_imply_multiple_owners(self):
        controller = node("/gpio")
        owner = node("/a", props={"gpios": gpio(controller, 2), "enable-gpios": gpio(controller, 2)})
        self.assertEqual(analyze_edt(NS(nodes=[owner, controller]))["findings"], [])

    def test_parent_cycle_rejected(self):
        item = node("/a")
        item.parent = item
        with self.assertRaises(InputError):
            analyze_edt(NS(nodes=[item]))

class ResourceInspectionTests(unittest.TestCase):
    def setUp(self):
        from pathlib import Path
        import tempfile
        temp = tempfile.TemporaryDirectory(prefix="resource Ω ")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.build, self.zephyr = self.root / "build", self.root / "checkout"
        (self.build / "zephyr").mkdir(parents=True)
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        library = self.zephyr / "scripts/dts/python-devicetree/src/devicetree"
        library.mkdir(parents=True)
        (library / "edtlib.py").write_text("# typed API fixture\n")
        (self.build / "CMakeCache.txt").write_text("ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n")
        self.dts = self.build / "zephyr/zephyr.dts"
        self.dts.write_text("/dts-v1/; / {};\n")

    def test_selected_checkout_adapter_preserves_build_and_labels_snapshot(self):
        from unittest import mock
        from zew_evidence import studio_resources as resources
        before = self.dts.read_bytes()
        controller = node("/gpio")
        with mock.patch.object(resources.importlib, "import_module", return_value=NS(EDT=lambda *a: NS(nodes=[controller]))):
            report = resources.inspect(self.build, self.zephyr)
        self.assertEqual(report["status"], "observed")
        self.assertEqual(report["images"][0]["provenance"]["freshness"], "unverified-build-snapshot")
        self.assertEqual(self.dts.read_bytes(), before)

    def test_wrong_checkout_and_changed_artifact_discard_evidence(self):
        from unittest import mock
        from zew_evidence import studio_resources as resources
        with mock.patch.object(resources.importlib, "import_module", return_value=NS(EDT=lambda *a: NS(nodes=[]))):
            with mock.patch.object(resources, "_digest", return_value=("changed", 0)):
                report = resources.inspect(self.build, self.zephyr)
        self.assertEqual(report["images"][0]["reason"], "generated-dts-changed-during-inspection")
        self.assertNotIn("gpio_lines", report["images"][0])
        (self.build / "CMakeCache.txt").write_text("ZEPHYR_BASE:PATH=/not-the-selected-tree\n")
        self.assertEqual(resources.inspect(self.build, self.zephyr)["images"][0]["reason"], "selected-checkout-does-not-match-build")

    def test_missing_generated_tree_remains_unknown(self):
        from zew_evidence import studio_resources as resources
        self.dts.unlink()
        report = resources.inspect(self.build, self.zephyr)
        self.assertEqual(report["status"], "partial-or-unavailable")
        self.assertEqual(report["images"][0]["reason"], "missing-generated-dts")

class UpstreamResourceIntegrationTests(unittest.TestCase):
    def test_actual_edtlib_decodes_cells_before_shared_line_diagnosis(self):
        from pathlib import Path
        import tempfile
        try:
            from devicetree import edtlib
        except ImportError:
            self.skipTest("Install upstream devicetree for typed-cell semantic integration")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bindings = root / "bindings"
            bindings.mkdir()
            (bindings / "test,gpio.yaml").write_text('''description: Synthetic test GPIO controller
compatible: "test,gpio"
properties:
  gpio-controller:
    type: boolean
  "#gpio-cells":
    type: int
    required: true
gpio-cells:
  - pin
  - flags
''')
            (bindings / "test,consumer.yaml").write_text('''description: Synthetic test consumer
compatible: "test,consumer"
properties:
  gpios:
    type: phandle-array
    required: true
''')
            dts = root / "fixture.dts"
            dts.write_text('''/dts-v1/;
/ {
    gpio0: gpio { compatible = "test,gpio"; gpio-controller; #gpio-cells = <2>; };
    pins: pins { };
    one { compatible = "test,consumer"; gpios = <&gpio0 2 0>; pinctrl-0 = <&pins>; pinctrl-names = "default"; };
    two { compatible = "test,consumer"; gpios = <&gpio0 2 1>; };
    three { compatible = "test,consumer"; gpios = <&gpio0 3 0>; };
    disabled { status = "disabled";
        child { compatible = "test,consumer"; gpios = <&gpio0 3 0>; };
    };
};
''')
            report = analyze_edt(edtlib.EDT(str(dts), [str(bindings)]))
            self.assertEqual(len(report["findings"]), 1)
            self.assertEqual(report["findings"][0]["pin"], 2)
            self.assertEqual(report["findings"][0]["confidence"], "possible")
            self.assertEqual(report["pinctrl_states"][0]["configuration_nodes"], ["/pins"])
            self.assertEqual(len(report["gpio_lines"]), 2)
