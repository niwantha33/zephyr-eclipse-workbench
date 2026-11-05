# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from zew_evidence.studio_visualization import render
from zew_evidence.collector import InputError


class StudioVisualizationTests(unittest.TestCase):
    def setUp(self):
        self.report = {"freshness": "unverified", "findings": [
            {"id": "CS-KCONFIG-UNDEFINED:app:CONFIG_TEST", "image": "app", "severity": "warning",
             "confidence": "derived", "message": "No definition in the selected tree.",
             "evidence": [{"path": "/private/project/prj.conf", "sha256": "a" * 64}]}]}

    def test_markdown_is_deterministic_and_explicit_about_unknown_state(self):
        first = render(self.report)
        self.assertEqual(first, render(self.report))
        self.assertIn("Freshness: unverified", first["content"])
        self.assertIn("CS-KCONFIG-UNDEFINED", first["content"])
        self.assertNotIn("/private/project", first["content"])
        self.assertEqual(first["displayed"], 1)
        self.assertFalse(first["truncated"])

    def test_markdown_and_dot_escape_hostile_values(self):
        hostile = '<script>alert(1)</script>|[click](javascript:bad)\n\x1b[0m\u202e'
        self.report["findings"][0]["message"] = hostile
        markdown = render(self.report)["content"]
        self.assertNotIn("<script>", markdown)
        self.assertNotIn("[click](javascript:", markdown)
        self.assertNotIn("\x1b", markdown)
        self.assertNotIn("\u202e", markdown)
        self.report["findings"][0]["id"] = 'x"]; injected [label="bad'
        dot = render(self.report, "dot")["content"]
        self.assertIn('x\\\"]; injected', dot)
        self.assertNotIn("/private/project", dot)
        self.assertIn("SHA-256 " + "a" * 64, dot)

    def test_empty_stale_and_truncated_views(self):
        empty = render({"findings": []})
        self.assertIn("No recorded findings", empty["content"])
        self.report["freshness"] = "stale"
        self.report["findings"].append({"id": "SECOND"})
        small = render(self.report, max_findings=1)
        self.assertTrue(small["truncated"])
        self.assertEqual(small["freshness"], "stale")
        self.assertIn("Displayed 1 of 2", small["content"])

    def graph_report(self):
        return {"findings": [], "images": [{"image": "app", "freshness": "unverified", "graphs": [
            {"root": "GPIO", "coverage": "reachable-expressions", "nodes": [
                {"symbol": "GPIO", "value": "n", "visibility": "n"},
                {"symbol": "SOC", "value": "n", "visibility": "y"}],
             "edges": [{"from": "GPIO", "to": "SOC", "kind": "direct_dep"}]}]}]}

    def test_focused_graph_preserves_reference_kind_and_truncation(self):
        graph = self.graph_report()
        result = render(graph, "dot", focus="CONFIG_GPIO", image="app")
        self.assertIn('n0 -> n1 [label="direct_dep"]', result["content"])
        self.assertIn("not automatic fixes", result["content"])
        self.assertEqual(result["total"], 2)
        limited = render(graph, "markdown", max_findings=1, focus="GPIO")
        self.assertTrue(limited["truncated"])
        self.assertIn("GPIO", limited["content"])
        graph["images"][0]["graphs"][0]["coverage"] = "truncated"
        self.assertTrue(render(graph, focus="GPIO")["truncated"])

    def test_ambiguous_image_roots_duplicates_and_bad_limits_rejected(self):
        graph = self.graph_report()
        other = copy.deepcopy(graph["images"][0])
        other["image"] = "boot"
        graph["images"].append(other)
        with self.assertRaisesRegex(InputError, "unambiguous"):
            render(graph, focus="GPIO")
        self.assertEqual(render(graph, focus="GPIO", image="boot")["image"], "boot")
        self.report["findings"] *= 2
        with self.assertRaisesRegex(InputError, "Duplicate"):
            render(self.report)
        for count in (0, 501, True):
            with self.assertRaises(InputError):
                render({}, max_findings=count)
        with self.assertRaises(InputError):
            render({}, "html")


if __name__ == "__main__":
    unittest.main()
