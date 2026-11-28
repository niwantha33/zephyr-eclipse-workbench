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
    def to_num(self):
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

    def test_mspm0_bus_pins_match_generated_cells_against_selected_hal(self):
        table = self.zephyr.parent / graph.TI_PIN_DEFINITIONS
        table.parent.mkdir(parents=True)
        table.write_text("""
            uart0_tx_pa10: uart0_tx_pa10 { pinmux = <MSP_PINMUX(21, MSPM0_PIN_FUNCTION_2)>; };
            i2c1_scl_pb2: i2c1_scl_pb2 { pinmux = <MSP_PINMUX(15, MSPM0_PIN_FUNCTION_4)>; };
            spi0_poci_pb19: spi0_poci_pb19 { pinmux = <MSP_PINMUX(45, MSPM0_PIN_FUNCTION_3)>; };
            canfd0_canrx_pa13: canfd0_canrx_pa13 { pinmux = <MSP_PINMUX(35, MSPM0_PIN_FUNCTION_6)>; };
        """)
        header = self.zephyr / "include/zephyr/dt-bindings/pinctrl/mspm0-pinctrl.h"
        header.parent.mkdir(parents=True)
        header.write_text("#define MSP_PINMUX(pincm, function) (((pincm - 1) << 0x10) | function)\n")
        root = Node("/", 1, {"compatible": Prop("STRINGS", "ti,mspm0g3507")})
        mux = Node("/pinctrl", 2, {"compatible": Prop("STRINGS", "ti,mspm0-pinctrl")})
        paths = {mux.path: mux}
        pins = graph.ti_bus_pins(self.zephyr, root)
        for bus, value, signal, pad in (
                ("uart0", 0x140002, "TX", "PA10"),
                ("i2c1", 0xe0004, "SCL", "PB2"),
                ("spi0", 0x2c0003, "POCI", "PB19"),
                ("canfd0", 0x220006, "CANRX", "PA13")):
            source = Node("/bus", 3)
            source.labels = [bus]
            target = Node("/pinctrl/custom_overlay_label", 4, {"pinmux": Prop("NUM", value)})
            self.assertEqual(graph.ti_bus_pin(pins, paths, source, target), (signal, pad, value))
            source.labels = ["another_controller"]
            self.assertIsNone(graph.ti_bus_pin(pins, paths, source, target))
            source.labels = [bus]
            target.props["pinmux"] = Prop("NUM", value ^ 0x1f)
            self.assertIsNone(graph.ti_bus_pin(pins, paths, source, target))
        header.write_text("#define MSP_PINMUX(pincm, function) (pincm | function)\n")
        self.assertEqual(graph.ti_bus_pins(self.zephyr, root), {})

    def test_vendor_pin_values_use_the_selected_header_definitions(self):
        include = self.zephyr / "include/zephyr/dt-bindings/pinctrl"
        include.mkdir(parents=True)
        cases = [
            ("st,stm32-pinctrl",
             {"STM32_PORT_SHIFT": 9, "STM32_PORT_MASK": 31, "STM32_LINE_SHIFT": 5,
              "STM32_LINE_MASK": 15, "STM32_MODE_SHIFT": 0, "STM32_MODE_MASK": 31},
             0x47, ("AF7", "PA2"), 0x10047),
            ("nordic,nrf-pinctrl",
             {"NRF_PIN_POS": 0, "NRF_PIN_MSK": 511, "NRF_FUN_POS": 24,
              "NRF_FUN_MSK": 255, "NRF_FUN_UART_TX": 0},
             0x06, ("UART_TX", "P0.06"), 0x1ff),
            ("espressif,esp32-pinctrl",
             {"ESP32_PIN_NUM_SHIFT": 0, "ESP32_PIN_NUM_MASK": 63, "ESP32_PIN_SIGI_SHIFT": 6,
              "ESP32_PIN_SIGI_MASK": 511, "ESP32_PIN_SIGO_SHIFT": 15, "ESP32_PIN_SIGO_MASK": 511},
             (511 << 6) | (14 << 15) | 43, ("OUT14", "GPIO43"), 1 << 30),
        ]
        for compatible, definitions, value, expected, invalid in cases:
            with self.subTest(compatible=compatible):
                header = graph.PIN_HEADERS[compatible]
                (include / header).write_text("".join(
                    f"#define {key} {number:#x}U\n" for key, number in definitions.items()))
                fields = graph.pin_definitions(self.zephyr, header)
                self.assertEqual(graph.vendor_pin_value(compatible, fields, value), expected)
                self.assertIsNone(graph.vendor_pin_value(compatible, fields, invalid))
                self.assertIsNone(graph.vendor_pin_value(compatible, {}, value))

    def test_windows_crlf_cache_preserves_verified_selected_checkout(self):
        # CMake writes CRLF cache records on Windows, with spaces supported
        # in application/checkout paths. The trailing \r is NOT part of the path.
        (self.build / "CMakeCache.txt").write_bytes(
            ("ZEPHYR_BASE:PATH=" + str(self.zephyr)).encode("utf-8") + b"\r\n")
        with self.library():
            state, digest, reason, nodes, edges = graph.observed(
                self.zephyr, self.application, self.build)
        self.assertEqual(state, "built")
        self.assertEqual(len(digest), 64)
        self.assertIn(("/leds/my_red_led", "/soc/gpio@4000", "gpio", "gpios",
                       "[0] pin=22"), edges)

    def test_exact_board_cache_rejects_other_vendor_core_and_missing_identity(self):
        for expected, cache, matches in (
            ("lp_mspm0g3507/mspm0g3507", "CACHED_BOARD:STRING=lp_mspm0g3507/mspm0g3507\nBOARD:STRING=lp_mspm0g3507", True),
            ("esp32s3_devkitc/esp32s3/appcpu", "CACHED_BOARD:STRING=esp32s3_devkitc/esp32s3/procpu", False),
            ("nucleo_f446re/stm32f446xx", "BOARD:STRING=nucleo_f446re", False),
            ("nrf52840dk/nrf52840", "BOARD_TARGET:STRING=nrf52840dk/nrf52840", True),
            ("qemu_x86/atom", "BOARD:STRING=qemu_x86/atom", True),
            ("qemu_x86/atom", "CACHED_BOARD:STRING=qemu_x86/atom\nCACHED_BOARD:STRING=qemu_x86/atom", False),
            ("qemu_x86/atom", "CACHED_BOARD:STRING=other\nBOARD_TARGET:STRING=qemu_x86/atom", False),
            ("lp_mspm0g3507", "BOARD:STRING=lp_mspm0g3507", True),
            ("qemu_x86/atom", "", False),
            ("qemu_x86/atom", "CACHED_BOARD:STRING=\nBOARD:STRING=qemu_x86/atom", False),
        ):
            with self.subTest(expected=expected, cache=cache):
                (self.build / "CMakeCache.txt").write_bytes(
                    ("ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n" + cache + "\n")
                    .replace("\n", "\r\n").encode("utf-8"))
                with self.library():
                    state, digest, reason, nodes, edges = graph.observed(
                        self.zephyr, self.application, self.build, expected)
                if matches:
                    self.assertEqual("built", state)
                    self.assertTrue(nodes)
                    self.assertTrue(edges)
                else:
                    self.assertEqual("mismatch", state)
                    self.assertEqual("", digest)
                    self.assertEqual(([], []), (nodes, edges))

    def test_wrong_checkout_does_not_show_other_board(self):
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=/other/checkout\n")
        state, digest, reason, nodes, edges = graph.observed(
            self.zephyr, self.application, self.build)
        self.assertEqual(state, "mismatch")
        self.assertEqual((nodes, edges), ([], []))

    def test_foreign_application_cache_does_not_show_other_project(self):
        foreign = self.application.parent / "another-app"
        foreign.mkdir()
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n"
            "APPLICATION_SOURCE_DIR:PATH=" + str(foreign) + "\n")
        state, digest, reason, nodes, edges = graph.observed(
            self.zephyr, self.application, self.build)
        self.assertEqual(state, "mismatch")
        self.assertEqual((nodes, edges), ([], []))

    def test_foreign_cmake_home_directory_rejected(self):
        foreign = self.application.parent / "other-app"
        foreign.mkdir()
        (self.build / "CMakeCache.txt").write_text(
            "ZEPHYR_BASE:PATH=" + str(self.zephyr) + "\n"
            "CMAKE_HOME_DIRECTORY:INTERNAL=" + str(foreign) + "\n")
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
