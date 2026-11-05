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
