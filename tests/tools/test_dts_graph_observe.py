# SPDX-License-Identifier: Apache-2.0
"""Offline regression tests: no fake DTS references or guessed pin wiring."""
import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = (Path(__file__).resolve().parents[2] /
          "plugins/io.github.niwantha33.zew.ui/scripts/dts_graph_observe.py")
spec = importlib.util.spec_from_file_location("zew_dts_graph_observe", SCRIPT)
graph = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph)


class Prop:
    def __init__(self, kind, value=None):
        self.type = types.SimpleNamespace(name=kind)
        self.value = value
    def to_string(self):
        return str(self.value)
    def to_strings(self):
        return [str(self.value)]
    def to_node(self):
        return self.value
    def to_nodes(self):
        return self.value


class Node:
    def __init__(self, path, line, props=None):
        self.path = path
        self.name = path.rsplit("/", 1)[-1] or "/"
        self.labels = []
        self.lineno = line
        self.props = props or {}


class FakeDt:
    def __init__(self, source):
        self.root = Node("/", 1)
        self.soc = Node("/soc", 2)
        self.gpio = Node("/soc/gpio@4000", 3,
                         {"status": Prop("STRING", "okay"),
                          "compatible": Prop("STRINGS", "ti,mspm0-gpio")})
        self.leds = Node("/leds", 4)
        self.led = Node("/leds/my_red_led", 5,
                        {"numbers": Prop("COMPOUND", [0x4000, 22])})
    def node_iter(self):
        return [self.root, self.soc, self.gpio, self.leds, self.led]


class FakeEdt:
    def __init__(self, source, bindings):
        controller = types.SimpleNamespace(path="/soc/gpio@4000")
        unsupported = types.SimpleNamespace(path="/soc/absent")
        specs = [types.SimpleNamespace(controller=controller, data={"pin": 22}),
                 types.SimpleNamespace(controller=unsupported, data={"pin": 99})]
        led = types.SimpleNamespace(path="/leds/my_red_led",
                                    props={"gpios": types.SimpleNamespace(
                                        type="phandle-array", val=specs)},
                                    pinctrls=[])
        self.nodes = [led]


class DtsObserverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.zephyr = root / "workspace/zephyr"
        self.application = root / "project"
        self.build = self.application / ".zew-build/board-debug"
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree").mkdir(
            parents=True)
        (self.zephyr / "scripts/dts/python-devicetree/src/devicetree/dtlib.py").write_text(
            "# test-only stub")
        (self.zephyr / "dts/bindings").mkdir(parents=True)
        (self.build / "zephyr").mkdir(parents=True)
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n")
        (self.build / "zephyr/zephyr.dts").write_text(
            "/dts-v1/;\n/ { soc { gpio@4000 {}; }; leds { my_red_led {}; }; };\n")

    def library(self, edt=True):
        fake = types.ModuleType("devicetree")
        fake.dtlib = types.SimpleNamespace(DT=FakeDt)
        if edt:
            fake.edtlib = types.SimpleNamespace(EDT=FakeEdt)
        return mock.patch.dict(sys.modules, {"devicetree": fake}, clear=False)

    def test_verified_gpio_reference_preserves_exact_identity(self):
        with self.library():
            state, digest, reason, nodes, edges = graph.observed(
                self.zephyr, self.application, self.build)
        self.assertEqual(state, "built")
        self.assertEqual(len(digest), 64)
        self.assertEqual(len(nodes), 5)
        self.assertIn(("/leds/my_red_led", "/soc/gpio@4000", "gpio", "gpios",
                       "[0] pin=22"), edges)
        self.assertFalse(any(edge[1] == "/soc/absent" for edge in edges))
        self.assertFalse(any(edge[3] == "numbers" for edge in edges))

    def test_missing_binding_evidence_keeps_only_real_hierarchy(self):
        with self.library(edt=False):
            state, digest, reason, nodes, edges = graph.observed(
                self.zephyr, self.application, self.build)
        self.assertEqual(state, "hierarchy-only")
        self.assertEqual(edges, [])
        self.assertEqual(len(nodes), 5)
        self.assertIn("unavailable", reason)

    def test_wrong_checkout_does_not_show_other_board(self):
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=/other/checkout\n")
        state, digest, reason, nodes, edges = graph.observed(
            self.zephyr, self.application, self.build)
        self.assertEqual(state, "mismatch")
        self.assertEqual((nodes, edges), ([], []))

    def test_unbuilt_profile_does_not_invent_hardware(self):
        (self.build / "zephyr/zephyr.dts").unlink()
        state, digest, reason, nodes, edges = graph.observed(
            self.zephyr, self.application, self.build)
        self.assertEqual(state, "no-build")
        self.assertEqual((nodes, edges), ([], []))

    def test_external_parser_reads_rejected(self):
        (self.build / "zephyr/zephyr.dts").write_text(
            '/dts-v1/;\n/include/ "external.dtsi"\n')
        state, digest, reason, nodes, edges = graph.observed(
            self.zephyr, self.application, self.build)
        self.assertEqual(state, "unavailable")
        self.assertEqual((nodes, edges), ([], []))


if __name__ == "__main__":
    unittest.main()
