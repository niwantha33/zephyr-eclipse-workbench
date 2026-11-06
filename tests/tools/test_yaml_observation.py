# SPDX-License-Identifier: Apache-2.0
"""Step 3: bounded PyYAML syntax evidence, not Zephyr schema validation."""

import hashlib
import json
import unittest
from unittest import mock

from zew_evidence.yaml_observation import (
    MAX_DEPTH, MAX_NODES, MAX_SCALAR_CHARS, MAX_SOURCE_BYTES, observe_yaml,
)


class YamlObservationTests(unittest.TestCase):
    def observe(self, text, path="dts/bindings/test.yaml", source_root="zephyr"):
        result = observe_yaml(text, path=path, source_root=source_root)
        self.assertEqual(result["schema"], "zew.yaml-observation/1")
        json.dumps(result)
        return result

    def test_mapping_sequence_and_scalar_types(self):
        text = "description: TI GPIO\nrequired: true\nproperties:\n  pins:\n    - 12\n    - 0x13\n"
        report = self.observe(text)
        self.assertEqual(report["status"], "parsed")
        self.assertEqual(report["classification"]["kind"], "devicetree-binding")
        root = report["documents"][0]["root"]
        self.assertEqual(root["type"], "mapping")
        self.assertEqual([e["key"] for e in root["entries"]],
                         ["description", "required", "properties"])
        required = root["entries"][1]["value"]
        self.assertEqual((required["scalar_type"], required["value"]), ("bool", "true"))
        pins = root["entries"][2]["value"]["entries"][0]["value"]
        self.assertEqual(pins["type"], "sequence")
        self.assertEqual([n["value"] for n in pins["items"]], ["12", "0x13"])
        self.assertEqual(report["provenance"]["sha256"],
                         hashlib.sha256(text.encode()).hexdigest())

    def test_source_ranges_are_one_based_and_quoted_scalars_remain_raw(self):
        report = self.observe('name: "yes: no"\nlist:\n  - abc\n')
        rows = report["documents"][0]["root"]["entries"]
        self.assertEqual(rows[0]["key_range"]["start"], {"line": 1, "column": 1})
        self.assertEqual(rows[0]["value"]["value"], "yes: no")
        self.assertEqual(rows[1]["value"]["items"][0]["range"]["start"],
                         {"line": 3, "column": 5})

    def test_generic_yaml_without_checkout_can_be_parsed(self):
        report = observe_yaml(b"a: 1\n", path="config.yml")
        self.assertEqual(report["status"], "parsed")
        self.assertEqual(report["classification"]["kind"], "generic-yaml")
        self.assertEqual(report["provenance"]["source_root"], "unknown")

    def test_not_yaml_and_unsafe_path_do_not_parse(self):
        for path in ("readme.txt", "../secrets.yaml", r"C:\\secret.yaml"):
            with self.subTest(path=path):
                report = self.observe("valid: yaml", path=path)
                self.assertEqual(report["status"], "unsupported")
                self.assertEqual(report["documents"], [])
                self.assertIsNone(report["provenance"]["sha256"])

    def test_empty_and_document_start(self):
        for text in ("", "# comment only\n"):
            report = self.observe(text)
            self.assertEqual(report["status"], "parsed")
            self.assertEqual(report["reason"], "empty-document")
            self.assertEqual(report["documents"], [])

    def test_explicit_empty_document_is_yaml_null(self):
        report = self.observe("---\n")
        self.assertEqual(report["status"], "parsed")
        self.assertEqual(report["documents"][0]["root"]["scalar_type"], "null")

    def test_invalid_syntax_has_no_source_echo(self):
        private = "password: SECRET123\n invalid: [\n"
        report = self.observe(private)
        self.assertEqual(report["status"], "invalid")
        self.assertEqual(report["reason"], "yaml-syntax-invalid")
        self.assertNotIn("SECRET123", json.dumps(report))

    def test_duplicate_string_keys_rejected_including_nested(self):
        for text in ("a: 1\na: 2\n", "outer:\n  a: 1\n  a: 2\n"):
            self.assertEqual(self.observe(text)["reason"], "duplicate-mapping-key")

    def test_aliases_and_anchors_rejected_before_graph_creation(self):
        for text in ("a: &anchor {b: 1}\nc: *anchor\n",
                     "a: &anchor 3\n", "a: *nope\n"):
            with self.subTest(text=text):
                self.assertEqual(self.observe(text)["reason"],
                                 "aliases-or-anchors-unsupported")

    def test_custom_tags_and_merge_keys_rejected(self):
        for text in ("a: !!python/object/apply:os.system ['false']\n",
                     "a: !custom value\n", "a: {<<: other}\n"):
            with self.subTest(text=text):
                self.assertEqual(self.observe(text)["status"], "invalid")
                self.assertEqual(self.observe(text)["reason"], "unsupported-yaml-tag"
                                 if "<<" not in text else "non-string-mapping-key")

    def test_complex_and_nonstring_mapping_keys_fail_closed(self):
        for text in ("? [a, b]\n: 1\n", "1: x\n", "true: yes\n"):
            self.assertEqual(self.observe(text)["reason"], "non-string-mapping-key")

    def test_multiple_documents_rejected(self):
        self.assertEqual(self.observe("a: 1\n---\nb: 2\n")["reason"],
                         "multiple-documents-unsupported")

    def test_source_size_limit_before_parsing_or_hashing(self):
        result = self.observe("a" * (MAX_SOURCE_BYTES + 1))
        self.assertEqual(result["reason"], "maximum-source-size-exceeded")
        self.assertIsNone(result["provenance"]["sha256"])

    def test_depth_count_and_scalar_limits(self):
        deep = "[" * (MAX_DEPTH + 1) + "ok" + "]" * (MAX_DEPTH + 1)
        self.assertEqual(self.observe(deep)["reason"], "maximum-depth-exceeded")
        self.assertEqual(self.observe("- 1\n" * (MAX_NODES + 1))["reason"],
                         "maximum-node-count-exceeded")
        self.assertEqual(self.observe("a: " + "x" * (MAX_SCALAR_CHARS + 1))["reason"],
                         "maximum-scalar-length-exceeded")

    def test_unicode_source_provenance_and_byte_limit(self):
        text = "name: ääni\n"
        report = self.observe(text)
        self.assertEqual(report["provenance"]["size_bytes"], len(text.encode("utf-8")))
        self.assertEqual(report["status"], "parsed")
        self.assertEqual(self.observe(b"\xff")["reason"], "invalid-utf8")
        self.assertEqual(self.observe(b"foo: \x00")["reason"], "nul-byte-unsupported")
        self.assertEqual(self.observe("a: \ud800")["reason"], "invalid-unicode-input")

    def test_unavailable_pyyaml_is_explicit(self):
        with mock.patch.dict("sys.modules", {"yaml": None}):
            report = self.observe("a: 1")
        self.assertEqual(report["status"], "unavailable")
        self.assertEqual(report["reason"], "pyyaml-unavailable")

    def test_no_filesystem_io_or_external_calls(self):
        with mock.patch("builtins.open", side_effect=AssertionError("must not read files")):
            result = self.observe("a: b", path="tests/missing/testcase.yaml")
        self.assertEqual(result["status"], "parsed")
        self.assertEqual(result["classification"]["kind"], "twister-test")

    def test_invalid_caller_inputs_raise_programming_errors(self):
        with self.assertRaises(TypeError):
            self.observe({"source": "wrong"})
        with self.assertRaises(ValueError):
            observe_yaml("a: b", path="test.yaml", source_root="bogus")


if __name__ == "__main__":
    unittest.main()
