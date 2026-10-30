# SPDX-License-Identifier: Apache-2.0
"""SPDX status uses upstream-generated format; fixtures do not imply SBOM compliance."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from zew import main
from zew_evidence.collector import InputError
from zew_evidence.spdx import inspect_build

CONTEXT = "https://spdx.org/rdf/3.0.1/spdx-context.jsonld"


def doc(profile=True):
    elements = [
        {"type": "SpdxDocument", "spdxId": "urn:document"},
        {"type": "build_Build", "spdxId": "urn:build"},
        {"type": "Tool", "spdxId": "urn:tool"},
    ] if profile else [{"type": "SpdxDocument", "spdxId": "urn:document"}]
    if profile:
        for kind, target in (("hasInput", "urn:source"), ("hasOutput", "urn:image"), ("usesTool", "urn:tool")):
            elements.append({"type": "LifecycleScopedRelationship", "relationshipType": kind,
                             "from": "urn:build", "to": [target], "scope": "build"})
        # This is not attributable to the build, and must not be counted.
        elements.append({"type": "Relationship", "relationshipType": "hasInput",
                         "from": "urn:foreign", "to": ["urn:unrelated"]})
        # An unscoped lifecycle relation must also not be counted.
        elements.append({"type": "LifecycleScopedRelationship", "relationshipType": "hasOutput",
                         "from": "urn:build", "to": ["urn:other"], "scope": "test"})
    return {"@context": CONTEXT, "@graph": elements}


class SpdxEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="zew-sbom-Ω ")
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.image = self.root / "app image"
        (self.image / "zephyr").mkdir(parents=True)
        (self.image / "zephyr/.config").write_text(
            "CONFIG_BUILD_OUTPUT_META=y\nCONFIG_PASSWORD=custom-secret-value\n", encoding="utf-8")

    def add_doc(self, name, content=None, ext=".jsonld"):
        directory = self.image / "spdx"
        directory.mkdir(exist_ok=True)
        path = directory / (name + ext)
        path.write_text(json.dumps(doc() if content is None else content), encoding="utf-8")
        return path

    def test_no_sbom_reports_upstream_command_without_changes(self):
        before = {p.relative_to(self.root): p.read_bytes()
                  for p in self.root.rglob("*") if p.is_file()}
        result = inspect_build(self.image)
        self.assertEqual(result["images"][0]["status"], "missing-build-sbom")
        self.assertEqual(result["images"][0]["metadata_config"]["value"], "y")
        self.assertIn("west spdx", result["images"][0]["next_action"])
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes()
                                  for p in self.root.rglob("*") if p.is_file()})
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertNotIn("custom-secret-value", json.dumps(result))

    def test_actual_spdx3_shape_counts_only_build_scoped_relationships(self):
        path = self.add_doc("build")
        result = inspect_build(self.image)
        report = result["images"][0]
        self.assertEqual(report["status"], "spdx3-build-evidence-observed")
        spdx = next(x for x in report["documents"] if x["document"] == "build")
        self.assertEqual(spdx["provenance"]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        prof = spdx["build_profile"]
        self.assertEqual(prof["build_elements"], 1)
        self.assertEqual(prof["observed_build_relationships"],
                         {"hasInput": 1, "hasOutput": 1, "usesTool": 1})
        self.assertEqual(result, inspect_build(self.image))
        self.assertNotIn("urn:source", json.dumps(result))
        self.assertNotIn(str(self.root), json.dumps(result))

    def test_explicit_sysbuild_images_and_read_only_cli(self):
        self.add_doc("build")
        other = self.root / "mcuboot"
        (other / "zephyr").mkdir(parents=True)
        (other / "zephyr/.config").write_text("CONFIG_BUILD_OUTPUT_META=n\n", encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(main(["sbom", "--build", str(self.root),
                                   "--image", "app=app image", "--image", "boot=mcuboot"]), 0)
        parsed = json.loads(output.getvalue())
        self.assertEqual([x["image"] for x in parsed["images"]], ["app", "boot"])
        self.assertEqual(parsed["images"][0]["status"], "spdx3-build-evidence-observed")
        self.assertEqual(parsed["images"][1]["metadata_config"]["value"], "n")

    def test_spdx_2_is_presence_only_and_no_profile_invention(self):
        p = self.image / "spdx"
        p.mkdir()
        (p / "build.spdx").write_text("SPDXVersion: SPDX-2.3\n", encoding="utf-8")
        result = inspect_build(self.image)
        self.assertEqual(result["images"][0]["status"], "spdx2-only")
        # Prefer SPDX 3 only where actually present.
        self.add_doc("build", doc(profile=False))
        result = inspect_build(self.image)
        self.assertEqual(result["images"][0]["status"], "spdx3-without-observed-build-profile")

    def test_invalid_document_and_duplicate_keys_rejected(self):
        path = self.add_doc("build")
        bad = [
            b'{"@context":"https://example.com/evil","@graph":[]}',
            b'{"@context":"https://spdx.org/rdf/3.0.1/spdx-context.jsonld","@graph":{}}',
            b'{"@context":"https://spdx.org/rdf/3.0.1/spdx-context.jsonld","@graph":[],"@graph":[]}',
            b"\xff\xfe",
            (b"[" * 3000 + b"0" + b"]" * 3000),
        ]
        for raw in bad:
            with self.subTest(raw=raw[:25]):
                path.write_bytes(raw)
                with self.assertRaises(InputError):
                    inspect_build(self.image)

    def test_symlink_or_oversized_document_not_processed(self):
        source = self.root / "outside.jsonld"
        source.write_text(json.dumps(doc()), encoding="utf-8")
        directory = self.image / "spdx"
        directory.mkdir()
        linked = directory / "build.jsonld"
        try:
            linked.symlink_to(source)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink unsupported")
        with self.assertRaises(InputError):
            inspect_build(self.image)
        linked.unlink()
        # This is an intentionally oversized sparse input: never read it all.
        with linked.open("wb") as out:
            out.seek(16 * 1024 * 1024 + 4)
            out.write(b"\x00")
        with self.assertRaisesRegex(InputError, "bounded"):
            inspect_build(self.image)

    def test_cannot_treat_unrelated_source_relationship_as_build_proof(self):
        d = doc(profile=False)
        d["@graph"].append({"type": "LifecycleScopedRelationship", "relationshipType": "hasInput",
                            "from": "urn:unknown", "scope": "build", "to": ["urn:secret"]})
        self.add_doc("build", d)
        self.assertEqual(inspect_build(self.image)["images"][0]["status"],
                         "spdx3-without-observed-build-profile")

    def test_metadata_off_and_version_formats(self):
        (self.image / "zephyr/.config").write_text(
            "# CONFIG_BUILD_OUTPUT_META is not set\nCONFIG_API_TOKEN=private\n", encoding="utf-8")
        report = inspect_build(self.image)["images"][0]
        self.assertEqual(report["metadata_config"]["value"], "n")
        self.assertNotIn("private", json.dumps(report))
        p = self.add_doc("app", {"@context": CONTEXT, "@graph": []})
        self.assertEqual(inspect_build(self.image)["images"][0]["documents"][0]["format"], "spdx3")


if __name__ == "__main__":
    unittest.main()
