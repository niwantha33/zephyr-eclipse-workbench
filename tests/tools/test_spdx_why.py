# SPDX-License-Identifier: Apache-2.0
"""Build-scoped SPDX input observations, not final firmware-inclusion assertions."""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError
from zew_evidence.inclusion import explain

CONTEXT = "https://spdx.org/rdf/3.0.1/spdx-context.jsonld"


def node(kind, ident, name=None):
    item = {"type": kind, "spdxId": ident}
    if name is not None:
        item["name"] = name
    return item


def rel(kind, src, to, scope="build"):
    return {"type": "LifecycleScopedRelationship", "relationshipType": kind,
            "from": src, "to": to, "scope": scope}


class SpdxWhyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-spdx-why-Ω ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.image = self.root / "image one"
        (self.image / "zephyr").mkdir(parents=True)
        (self.image / "zephyr/.config").write_text(
            "CONFIG_BUILD_OUTPUT_META=y\nCONFIG_GPIO=y\nCONFIG_API_TOKEN=private-token\n", encoding="utf-8")
        self.documents = self.image / "spdx"
        self.documents.mkdir()
        self.app = self.documents / "app.jsonld"
        self.build = self.documents / "build.jsonld"
        self.file_id = "urn:file:alpha"
        self.package_id = "urn:package:ti"
        self.build_id = "urn:build:static-library"
        self.write_json(self.app, [
            node("software_File", self.file_id, "drivers/adc/sensor.c"),
            node("software_Package", self.package_id, "hal_ti"),
        ])
        self.write_json(self.build, [
            node("build_Build", self.build_id),
            node("build_Build", "urn:build:unrelated"),
            rel("hasInput", self.build_id, [self.file_id, self.package_id]),
            rel("hasOutput", self.build_id, ["urn:artifact:library"]),
            rel("usesTool", self.build_id, ["urn:tool:compiler"]),
            rel("hasInput", "urn:not-a-build", ["urn:file:other"]),
            rel("hasInput", self.build_id, ["urn:file:other"], "test"),
        ])

    def write_json(self, path, elements):
        path.write_text(json.dumps({"@context": CONTEXT, "@graph": elements}),
                        encoding="utf-8")

    def test_direct_file_input_attached_to_exact_upstream_artifacts(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        result = explain(self.image, "source", "drivers/adc/sensor.c")
        self.assertEqual(result["status"], "upstream-spdx-input-explanation")
        obs = result["images"][0]
        self.assertEqual(obs["status"], "observed-direct-build-input")
        self.assertEqual(obs["confidence"], "confirmed")
        self.assertEqual(obs["matching_elements"], 1)
        self.assertEqual(obs["builds_with_direct_input"], 1)
        self.assertEqual(obs["build_relationships"][0]["output_relationship_targets"], 1)
        self.assertEqual(obs["build_relationships"][0]["output_causality"], "unknown")
        import hashlib
        self.assertEqual(obs["build_relationships"][0]["build_document"]["sha256"],
                         hashlib.sha256(self.build.read_bytes()).hexdigest())
        self.assertEqual(obs["build_relationships"][0]["element_document"]["sha256"],
                         hashlib.sha256(self.app.read_bytes()).hexdigest())
        payload = json.dumps(result)
        self.assertNotIn("drivers/adc/sensor.c", payload)
        self.assertNotIn(str(self.root), payload)
        self.assertNotIn("private-token", payload)
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()})
        self.assertEqual(result, explain(self.image, "source", "drivers/adc/sensor.c"))

    def test_module_is_upstream_package_input_not_every_file_compiled(self):
        result = explain(self.image, "module", "hal_ti")
        self.assertEqual(result["images"][0]["status"], "observed-direct-build-input")
        self.assertEqual(result["query"]["name"], "<not-exported>")
        self.assertIn("does not imply every file", json.dumps(result))
        self.assertNotIn("hal_ti", json.dumps(result))

    def test_unrelated_or_unscoped_edges_do_not_infer_inclusion(self):
        self.write_json(self.app, [node("software_File", "urn:file:other", "unrelated.c")])
        result = explain(self.image, "source", "unrelated.c")
        self.assertEqual(result["images"][0]["status"], "no-direct-build-input-observed")
        self.assertEqual(result["images"][0]["confidence"], "unknown")
        self.assertEqual(result["images"][0]["builds_with_direct_input"], 0)
        self.assertIn("unknown", json.dumps(result["limitations"]).lower())

    def test_ambiguous_exact_source_name_is_not_resolved_by_guessing(self):
        self.write_json(self.app, [
            node("software_File", self.file_id, "drivers/adc/sensor.c"),
            node("software_File", "urn:file:other", "drivers/adc/sensor.c"),
        ])
        result = explain(self.image, "source", "drivers/adc/sensor.c")
        self.assertEqual(result["images"][0]["status"], "ambiguous-spdx-element")
        self.assertEqual(result["images"][0]["matching_elements"], 2)
        self.assertEqual(result["images"][0]["confidence"], "unknown")

    def test_missing_build_or_absent_element_is_unknown(self):
        self.build.unlink()
        self.assertEqual(explain(self.image, "source", "drivers/adc/sensor.c")["images"][0]["status"],
                         "missing-spdx3-build-evidence")
        self.write_json(self.build, [node("build_Build", self.build_id)])
        result = explain(self.image, "source", "drivers/adc/sensor.c")
        self.assertEqual(result["images"][0]["status"], "no-direct-build-input-observed")
        self.assertEqual(explain(self.image, "source", "other.c")["images"][0]["status"],
                         "name-not-found-in-spdx-documents")

    def test_forged_conflicting_element_ids_rejected(self):
        self.write_json(self.build, [
            node("build_Build", self.build_id),
            node("software_File", self.file_id, "different.c"),
        ])
        with self.assertRaisesRegex(InputError, "Conflicting SPDX identity"):
            explain(self.image, "source", "drivers/adc/sensor.c")

    def test_spdx_changed_after_evidence_snapshot_is_error_not_current(self):
        from unittest import mock
        from zew_evidence import inclusion
        original = inclusion._read
        def change_in_between(path, limit):
            if path.name == "build.jsonld":
                self.write_json(self.build, [node("build_Build", "urn:build:changed")])
            return original(path, limit)
        with mock.patch.object(inclusion, "_read", side_effect=change_in_between):
            with self.assertRaisesRegex(InputError, "differs from the Evidence Graph"):
                explain(self.image, "source", "drivers/adc/sensor.c")

    def test_cli_keeps_config_symbol_unchanged_and_requires_one_subject(self):
        for args, expected in [
            (["why", "--source", "drivers/adc/sensor.c"], "observed-direct-build-input"),
            (["why", "--module", "hal_ti"], "observed-direct-build-input"),
        ]:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(main([*args, "--build", str(self.image)]), 0)
            self.assertEqual(json.loads(out.getvalue())["images"][0]["status"], expected)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["why", "CONFIG_GPIO", "--build", str(self.image)]), 0)
        self.assertEqual(json.loads(out.getvalue())["why_enabled"], "unknown")
        for args in (["why"], ["why", "CONFIG_GPIO", "--source", "main.c"]):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(main([*args, "--build", str(self.image)]), 2)

    def test_input_paths_not_permitted_to_escape_or_leak(self):
        for value in ("../secret.c", "/home/user/main.c", "C:/Users/private.c",
                      "sub//dir", "sub/./dir", "..", "\x1b[31m"):
            with self.subTest(value=value):
                with self.assertRaises(InputError):
                    explain(self.image, "source", value)

    def test_explicit_multi_image_does_not_attribute_app_input_to_boot(self):
        boot = self.root / "boot"
        (boot / "zephyr").mkdir(parents=True)
        (boot / "zephyr/.config").write_text("CONFIG_GPIO=n\n", encoding="utf-8")
        result = explain(self.root, "source", "drivers/adc/sensor.c",
                         ["app=image one", "boot=boot"])
        self.assertEqual([x["image"] for x in result["images"]], ["app", "boot"])
        self.assertEqual(result["images"][0]["status"], "observed-direct-build-input")
        self.assertEqual(result["images"][0]["evidence_graph_anchor"]["node_id"],
                         "artifact:app:spdx_3_build")
        self.assertEqual(result["images"][1]["status"], "missing-spdx3-build-evidence")
        self.assertIsNone(result["images"][1]["evidence_graph_anchor"])

    def test_duplicate_symlink_and_malformed_spdx_are_rejected(self):
        self.build.write_text('{"@context":"https://spdx.org/rdf/3.0.1/spdx-context.jsonld",'
                              '"@graph":[],"@graph":[]}', encoding="utf-8")
        with self.assertRaises(InputError):
            explain(self.image, "source", "drivers/adc/sensor.c")
        self.build.unlink()
        try:
            self.build.symlink_to(self.app)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink unsupported")
        with self.assertRaises(InputError):
            explain(self.image, "source", "drivers/adc/sensor.c")


if __name__ == "__main__":
    unittest.main()
