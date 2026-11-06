# SPDX-License-Identifier: Apache-2.0
"""Step 2 only: lexical YAML role discovery, not schema validation."""

from pathlib import PureWindowsPath
import unittest

from zew_evidence.yaml_classification import YamlClassification, classify_yaml_path


class YamlClassificationTests(unittest.TestCase):
    def check_kind(self, path, kind, source_root="zephyr"):
        result = classify_yaml_path(path, source_root=source_root)
        self.assertIsInstance(result, YamlClassification)
        self.assertEqual(result.kind, kind)
        self.assertTrue(result.reason)

    def test_upstream_binding_and_application_binding(self):
        for root in ("zephyr", "module", "application"):
            with self.subTest(root=root):
                self.check_kind("dts/bindings/gpio/ti,mspm0-gpio.yaml",
                                "devicetree-binding", root)
                self.check_kind("dts/bindings/vendor/example.yml",
                                "devicetree-binding", root)

    def test_binding_prefix_must_start_at_source_root(self):
        self.check_kind("docs/dts/bindings/not-real.yaml", "generic-yaml")
        self.check_kind("dts/other/example.yaml", "generic-yaml")
        self.check_kind("dts/bindings.yaml", "generic-yaml")

    def test_canonical_hwmv2_names_and_locations(self):
        for root in ("zephyr", "module", "application"):
            with self.subTest(root=root):
                self.check_kind("boards/ti/lp_mspm0g3507/board.yml", "hwmv2-board", root)
                self.check_kind("soc/ti/mspm0/soc.yml", "hwmv2-soc", root)
        self.check_kind("boards/ti/board.yml", "generic-yaml")
        self.check_kind("soc/ti/soc.yml", "generic-yaml")
        self.check_kind("other/boards/ti/example/board.yml", "generic-yaml")
        self.check_kind("boards/ti/example/nested/board.yml", "generic-yaml")

    def test_requested_hardware_auxiliary_yaml_is_not_hwmv2(self):
        result = classify_yaml_path("boards/ti/demo/board.yaml", source_root="zephyr")
        self.assertEqual(result.kind, "board-auxiliary-metadata")
        self.assertEqual(result.reason, "not-hwmv2-board-yml")
        self.check_kind("boards/ti/demo/hw_metadata.yaml", "hardware-metadata")
        self.check_kind("soc/ti/mspm0/hw_metadata.yaml", "hardware-metadata")
        self.check_kind("docs/hw_metadata.yaml", "generic-yaml")
        self.check_kind("boards/ti/demo/soc.yml", "generic-yaml")
        self.check_kind("soc/ti/mspm0/board.yml", "generic-yaml")

    def test_twister_tests_and_config(self):
        for name in ("tests.yaml", "testcase.yaml", "sample.yaml"):
            self.check_kind("tests/kernel/queue/" + name, "twister-test")
            self.check_kind("samples/basic/hello_world/" + name, "twister-test")
            self.check_kind(name, "twister-test", "application")
        for name in ("test_config.yaml", "twister.yaml"):
            self.check_kind("tests/kernel/queue/" + name, "twister-config")
            self.check_kind(name, "twister-config", "application")
        self.check_kind("docs/testcase.yaml", "generic-yaml")
        self.check_kind("testcase.yaml", "generic-yaml", "zephyr")
        self.check_kind("random/sample.yaml", "generic-yaml", "application")

    def test_default_unknown_context_does_not_guess_roles(self):
        for path in ("boards/acme/demo/board.yml", "dts/bindings/demo.yaml",
                     "tests/demo/tests.yaml", "sample.yaml", "board.yaml"):
            with self.subTest(path=path):
                result = classify_yaml_path(path)
                self.assertEqual(result.kind, "generic-yaml")
                self.assertEqual(result.reason, "source-root-unverified")

    def test_generic_and_unsupported_files(self):
        for path in ("west.yml", "manifest.yaml", "foo.yaml", "CONFIG.yml", "notes.YAML"):
            self.check_kind(path, "generic-yaml")
        for path in ("README.md", "board.yml.bak", "foo.txt", "file", "", "file.xml"):
            self.check_kind(path, "unsupported" if path else "unknown")

    def test_case_sensitive_zephyr_roles(self):
        for path in ("Boards/ti/demo/board.yml", "boards/ti/demo/Board.yml",
                     "boards/ti/demo/board.YML", "soc/ti/demo/SOC.yml",
                     "dts/Bindings/demo.yaml", "tests/hello/Tests.yaml"):
            with self.subTest(path=path):
                self.check_kind(path, "generic-yaml")

    def test_windows_separators_and_unicode(self):
        self.check_kind(PureWindowsPath(r"boards\ti\lp_mspm0g3507\board.yml"), "hwmv2-board")
        self.check_kind(r"dts\bindings\gpio\ti,mspm0.yaml", "devicetree-binding")
        self.check_kind("tests/ääni/sample.yaml", "twister-test")

    def test_absolute_traversal_and_ambiguous_paths(self):
        for path in ("/boards/ti/demo/board.yml", r"C:\boards\ti\demo\board.yml",
                     r"\\host\share\board.yml", "boards/../boards/ti/demo/board.yml",
                     "boards//ti/demo/board.yml", "./tests/demo/tests.yaml",
                     "tests/./demo/tests.yaml", "tests/demo/../tests.yaml",
                     "board.yaml\x00", "../sample.yaml", "a/" * 129 + "board.yml",
                     "a" * 4097 + ".yaml"):
            with self.subTest(path=path):
                self.check_kind(path, "unknown")

    def test_invalid_callers_cannot_invent_context(self):
        with self.assertRaises(ValueError):
            classify_yaml_path("sample.yaml", source_root="board")
        with self.assertRaises(TypeError):
            classify_yaml_path(12, source_root="zephyr")
        self.check_kind("sample.yaml", "generic-yaml", "unknown")

    def test_classification_has_no_file_io_or_parser_imports(self):
        # A nonexistent source-relative path can be classified syntactically;
        # no filesystem, Zephyr checkout or YAML dependency is required.
        self.check_kind("tests/nonexistent/sample.yaml", "twister-test")
        self.check_kind("dts/bindings/does-not-exist.yaml", "devicetree-binding")


if __name__ == "__main__":
    unittest.main()
