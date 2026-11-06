# SPDX-License-Identifier: Apache-2.0
"""Step 7: selected checkout EDT adapter, safety and bounded wire tests.

The tiny edtlib fixture tests invocation/identity, not Zephyr's real semantics.
Full binding interpretation requires the exact user's Zephyr checkout.
"""
import base64
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = (Path(__file__).resolve().parents[2] /
           "plugins/io.github.niwantha33.zew.ui/scripts")
SCRIPT = SCRIPTS / "yaml_binding_inspect.py"

FAKE_EDTLIB = '''\
from pathlib import Path
import yaml

class PropertySpec:
    def __init__(self, content):
        self.type = content.get("type", "")
        self.required = content.get("required", False)
        self.deprecated = content.get("deprecated", False)
        self.description = content.get("description", "")

class Binding:
    # Fixture only: tests that adapter chooses this module and passes paths.
    def __init__(self, path, fname2path):
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
        self.included_binding_paths = []
        data = dict(raw.get("properties", {}))
        include = raw.get("include")
        if include:
            included = Path(fname2path[include])
            self.included_binding_paths.append(str(included))
            inherited = yaml.safe_load(included.read_text(encoding="utf-8"))
            data.update(inherited.get("properties", {}))
        self.compatible = raw.get("compatible")
        self.title = raw.get("title")
        self.description = raw.get("description")
        self.prop2specs = {name: PropertySpec(value) for name, value in data.items()}
'''


def unpack(data):
    rows = data.strip().splitlines()
    if not rows or rows[0] != "ZEW-YAMLBIND-1":
        raise AssertionError("Wrong binding wire header")
    fields = [base64.b64decode(x).decode("utf-8") for x in rows[1].split("\t")[1:]]
    props = [[base64.b64decode(x).decode("utf-8") for x in row.split("\t")[1:]]
             for row in rows[2:]]
    return fields, props


class YamlBindingInspectionTests(unittest.TestCase):
    def setUp(self):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        self.root = Path(d.name)
        self.zephyr = self.root / "zephyr"
        self.application = self.root / "application"
        self.application.mkdir()
        self.binding_root = self.zephyr / "dts" / "bindings"
        self.binding_root.mkdir(parents=True)
        self.path = self.binding_root / "vendor" / "example.yaml"
        self.path.parent.mkdir()
        self.path.write_text(
            "compatible: demo,device\n"
            "title: Device controller\n"
            "description: Demo device binding\n"
            "properties:\n"
            "  demo-gpios:\n"
            "    type: phandle-array\n"
            "    required: true\n"
            "    description: GPIO list\n", encoding="utf-8")
        self.library = self.zephyr / "scripts/dts/python-devicetree/src/devicetree"
        self.library.mkdir(parents=True)
        (self.library / "__init__.py").write_text("", encoding="utf-8")
        self.library_file = self.library / "edtlib.py"
        self.library_file.write_text(FAKE_EDTLIB, encoding="utf-8")

    def invoke(self, *, root=None, relative="dts/bindings/vendor/example.yaml",
               kind="zephyr", zephyr=None, application=None):
        return subprocess.run([sys.executable, str(SCRIPT),
                               "--root", str(root or self.zephyr),
                               "--relative", relative,
                               "--source-root", kind,
                               "--zephyr", str(zephyr or self.zephyr),
                               "--application", str(application or self.application)],
                              capture_output=True, text=True, timeout=15, check=False)

    def test_selected_edtlib_properties_and_provenance(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        fields, props = unpack(result.stdout)
        self.assertEqual(fields[0], "resolved")
        self.assertEqual(fields[1], "selected-upstream-edtlib")
        self.assertRegex(fields[2], r"^[0-9a-f]{64}$")
        self.assertRegex(fields[3], r"^[0-9a-f]{64}$")
        self.assertEqual(fields[4:7], ["demo,device", "Device controller",
                                        "Demo device binding"])
        self.assertEqual(fields[7], "0")
        self.assertEqual(props, [["demo-gpios", "phandle-array", "yes", "no", "GPIO list"]])
        self.assertEqual(self.path.read_text(encoding="utf-8").splitlines()[0],
                         "compatible: demo,device")

    def test_direct_application_binding_has_explicit_root(self):
        app_binding = self.application / "dts/bindings/extra.yaml"
        app_binding.parent.mkdir(parents=True)
        app_binding.write_text(
            "compatible: demo,app\n"
            "description: App override\n"
            "properties:\n"
            "  enabled:\n"
            "    type: boolean\n", encoding="utf-8")
        result = self.invoke(root=self.application, relative="dts/bindings/extra.yaml",
                             kind="application")
        self.assertEqual(unpack(result.stdout)[0][0], "resolved")

    def test_included_binding_comes_from_exact_checkout(self):
        base = self.binding_root / "base.yaml"
        base.write_text(
            "description: Shared traits\n"
            "properties:\n"
            "  status:\n"
            "    type: string\n", encoding="utf-8")
        self.path.write_text(
            "compatible: demo,device\n"
            "description: Has included properties\n"
            "include: base.yaml\n"
            "properties:\n"
            "  local:\n"
            "    type: int\n", encoding="utf-8")
        fields, props = unpack(self.invoke().stdout)
        self.assertEqual(fields[0], "resolved")
        self.assertEqual(fields[7], "1")
        self.assertEqual([row[0] for row in props], ["local", "status"])

    def test_duplicate_include_basename_is_not_guessed(self):
        self.path.write_text(
            "compatible: demo,device\n"
            "description: ambiguous\n"
            "include: base.yaml\n", encoding="utf-8")
        for category in ("shared", "different"):
            target = self.binding_root / category / "base.yaml"
            target.parent.mkdir()
            target.write_text("description: common\n", encoding="utf-8")
        fields, props = unpack(self.invoke().stdout)
        self.assertEqual((fields[0], fields[1]), ("unavailable", "ambiguous-or-missing-include"))
        self.assertEqual(props, [])

    def test_invalid_yaml_is_not_forwarded_to_edtlib(self):
        self.path.write_text("password: SECRET_VALUE\npassword: OTHER\n", encoding="utf-8")
        result = self.invoke()
        fields, props = unpack(result.stdout)
        self.assertEqual(fields[0], "unavailable")
        self.assertEqual(fields[1], "binding-syntax-or-safety-rejected")
        self.assertEqual(props, [])
        self.assertNotIn("SECRET_VALUE", result.stdout + result.stderr)

    def test_no_checkout_and_root_mismatch_fail_explicitly(self):
        self.library_file.unlink()
        fields, _ = unpack(self.invoke().stdout)
        self.assertEqual((fields[0], fields[1]), ("unavailable", "selected-edtlib-missing"))
        fields, _ = unpack(self.invoke(root=self.application, kind="zephyr").stdout)
        self.assertEqual((fields[0], fields[1]), ("unavailable", "source-root-mismatch"))

    def test_nonschema_yaml_has_no_binding_inference(self):
        path = self.zephyr / "west.yml"
        path.write_text("manifest: {}\n", encoding="utf-8")
        fields, props = unpack(self.invoke(relative="west.yml").stdout)
        self.assertEqual(fields[0], "not-binding")
        self.assertEqual(props, [])

    def test_oversized_and_unsafe_sources_rejected(self):
        self.path.write_bytes(b"a" * (256 * 1024 + 1))
        fields, _ = unpack(self.invoke().stdout)
        self.assertEqual(fields[0], "unavailable")
        fields, _ = unpack(self.invoke(relative="../private.yaml").stdout)
        self.assertEqual(fields[0], "unavailable")

    def test_include_symlink_rejected_when_supported(self):
        self.path.write_text(
            "compatible: demo,device\n"
            "description: included\n"
            "include: base.yaml\n", encoding="utf-8")
        target = self.application / "private.yaml"
        target.write_text("description: hidden\n", encoding="utf-8")
        try:
            (self.binding_root / "base.yaml").symlink_to(target)
        except (OSError, NotImplementedError):
            self.skipTest("Symlink privileges unavailable")
        fields, _ = unpack(self.invoke().stdout)
        self.assertEqual(fields[0], "unavailable")

    def test_missing_upstream_dependency_returns_unavailable(self):
        self.library_file.write_text("raise RuntimeError('SECRET from module')\n", encoding="utf-8")
        result = self.invoke()
        fields, _ = unpack(result.stdout)
        self.assertEqual(fields[0], "unavailable")
        self.assertNotIn("SECRET", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
