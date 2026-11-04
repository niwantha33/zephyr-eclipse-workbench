# SPDX-License-Identifier: Apache-2.0
"""Deterministic publishing handoff and archive-lock regression checks."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "scripts" / "site_tree_digest.py"
spec = importlib.util.spec_from_file_location("site_tree_digest", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SecurityPublishingTests(unittest.TestCase):
    def test_site_digest_rejects_modified_or_renamed_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "features").mkdir()
            for name, payload in (("features/zew.jar", b"original"), ("content.jar", b"a"), ("artifacts.jar", b"b")):
                (root / name).write_bytes(payload)
            before = module.digest_site(root)
            self.assertEqual(before, module.digest_site(root))
            (root / "features/zew.jar").write_bytes(b"modified")
            after = module.digest_site(root)
            self.assertNotEqual(before, after)
            (root / "features/zew.jar").rename(root / "features/renamed.jar")
            self.assertNotEqual(after, module.digest_site(root))

    def test_site_digest_rejects_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("a", "b", "c"):
                (root / name).write_bytes(b"x")
            try:
                (root / "alias").symlink_to(root / "a")
            except (OSError, NotImplementedError):
                self.skipTest("Symlinks unavailable")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                module.digest_site(root)

    def test_eclipse_sha512_lock_is_complete(self):
        checksum = (ROOT / "scripts/eclipse-platform-4.41-linux.sha512").read_text(encoding="ascii")
        expected, filename = checksum.rstrip("\n").split("  ")
        self.assertEqual(filename, "eclipse-platform.tar.gz")
        self.assertEqual(len(expected), 128)
        self.assertTrue(all(c in "0123456789abcdef" for c in expected))

    def test_both_workflows_verify_before_unpacking(self):
        for path in (".github/workflows/build.yml", ".github/workflows/private-update.yml"):
            workflow = (ROOT / path).read_text(encoding="utf-8")
            self.assertIn("sha512sum --check", workflow)
            self.assertIn("eclipse-platform-4.41-linux.sha512", workflow)
            self.assertLess(workflow.index("sha512sum --check"), workflow.index('tar -xzf "$RUNNER_TEMP/eclipse-platform.tar.gz"'))


if __name__ == "__main__":
    unittest.main()
