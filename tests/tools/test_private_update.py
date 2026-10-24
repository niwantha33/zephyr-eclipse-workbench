# SPDX-License-Identifier: Apache-2.0
"""Regressions for safe offline validation of the private p2 update channel."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("private_publisher", SCRIPTS / "publish_private_update.py")
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PrivateUpdateTests(unittest.TestCase):
    def test_current_source_version_is_timestamp_qualified(self):
        self.assertRegex(publisher.expected_version(), r"^\d+\.\d+\.\d+$")

    def test_prevents_overwrite_and_downgrade(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            channel = {"version": "0.1.10.202610241100"}
            publisher.preflight(channel, "0.1.11.202610241500", root / "missing")
            with self.assertRaises(ValueError):
                publisher.preflight(channel, "0.1.10.202610241100", root / "missing")
            with self.assertRaises(ValueError):
                publisher.preflight(channel, "0.1.9.202610241500", root / "missing")
            destination = root / "exists"
            destination.mkdir()
            with self.assertRaises(ValueError):
                publisher.preflight(channel, "0.1.11.202610241500", destination)

    def test_manifest_requires_matching_content_and_exact_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            version = "0.1.11.202610241500"
            folder = root / "releases" / version
            folder.mkdir(parents=True)
            files = {"p2.index": b"metadata", "content.jar": b"content", "artifacts.jar": b"artifacts"}
            lines = ["schema=1", "version=" + version, "revision=" + "a" * 40,
                     "directory=releases/" + version, "files=3"]
            for index, (name, data) in enumerate(files.items()):
                (folder / name).write_bytes(data)
                lines.extend((f"file.{index}.path={name}", f"file.{index}.size={len(data)}",
                              f"file.{index}.sha256={hashlib.sha256(data).hexdigest()}"))
            manifest = root / "channel.properties"
            manifest.write_text("\n".join(lines) + "\n")
            publisher.verify_manifest(manifest, folder, "a" * 40)
            with self.assertRaises(ValueError):
                publisher.verify_manifest(manifest, folder, "b" * 40)
            (folder / "content.jar").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                publisher.verify_manifest(manifest, folder, "a" * 40)

    def test_empty_or_broken_channel_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                publisher.preflight({"version": "not-a-version"}, "0.1.11.202610241500", root / "missing")
            file = root / "channel.properties"
            file.write_text("version=0.1.10.202610241100\nnotes=Version = old\n")
            self.assertEqual(publisher.read_channel(file)["version"], "0.1.10.202610241100")


if __name__ == "__main__":
    unittest.main()
