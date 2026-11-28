# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("security_lock_inventory", ROOT / "scripts/security_lock_inventory.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def row(**changes):
    return dict(name="Example_Pkg", version="1.2.3", sha256="a" * 64,
                url="https://files.pythonhosted.org/packages/example.whl") | changes


class SecurityInventoryTests(unittest.TestCase):
    def test_normalized_and_pinned(self):
        self.assertEqual(module.inventory({"schema": 1, "packages": [row()]}),
                         [("example-pkg", "1.2.3")])

    def test_rejects_injection_invalid_digest_and_untrusted_url(self):
        for changes in ({"name": "pkg\nother"}, {"version": ">=1"}, {"sha256": "bad"},
                        {"url": "https://files.pythonhosted.org@evil.example/p.whl"},
                        {"url": "http://files.pythonhosted.org/p.whl"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                module.inventory({"schema": 1, "packages": [row(**changes)]})

    def test_empty_schema_and_duplicate_rejected(self):
        for document in ({"schema": 2, "packages": [row()]}, {"schema": 1, "packages": []},
                         {"schema": 1, "packages": [row(), row(name="example-pkg")]}):
            with self.assertRaises(ValueError):
                module.inventory(document)

    def test_all_real_locks_export(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            module.export(output)
            for lock in module.LOCKS:
                document = json.loads((module.TOOLS / lock).read_text(encoding="utf-8"))
                lines = (output / (Path(lock).stem + "-requirements.txt")).read_text().splitlines()
                self.assertEqual(len(lines), len(document["packages"]))
                self.assertTrue(all("==" in line for line in lines))


if __name__ == "__main__":
    unittest.main()
