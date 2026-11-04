# SPDX-License-Identifier: Apache-2.0
"""Descriptor-based generated artifact observations must fail closed."""
from pathlib import Path
import hashlib
import tempfile
import unittest
from unittest import mock

from zew_evidence.collector import _digest, _read_observed, InputError


class EvidenceIoTests(unittest.TestCase):
    def test_regular_bytes_and_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "config"
            content = b"CONFIG_GPIO=y\n"
            file.write_bytes(content)
            expected = hashlib.sha256(content).hexdigest()
            self.assertEqual(_digest(file), (expected, len(content)))
            self.assertEqual(_read_observed(file, 1024, capture=True),
                             (expected, len(content), content))

    def test_limit_and_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "config"
            file.write_bytes(b"secret")
            with self.assertRaises(InputError):
                _read_observed(file, 5, capture=True)
            link = Path(directory) / "link"
            try:
                link.symlink_to(file)
            except (OSError, NotImplementedError):
                self.skipTest("Symlinks unavailable")
            with self.assertRaises(InputError):
                _read_observed(link, 256)

    def test_inode_replacement_before_open_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "config"
            spare = Path(directory) / "spare"
            file.write_bytes(b"original")
            spare.write_bytes(b"replacement")
            real_open = __import__("os").open

            def replace_then_open(path, flags):
                spare.replace(file)
                return real_open(path, flags)

            with mock.patch("zew_evidence.collector.os.open", side_effect=replace_then_open):
                with self.assertRaisesRegex(InputError, "changed before opening"):
                    _read_observed(file, 256)


if __name__ == "__main__":
    unittest.main()
