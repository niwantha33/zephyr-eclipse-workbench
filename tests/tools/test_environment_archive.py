# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

SOURCE = (Path(__file__).resolve().parents[2] / "plugins"
          / "io.github.niwantha33.zew.ui" / "tools" / "environment_archive.py")
spec = importlib.util.spec_from_file_location("environment_archive", SOURCE)
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


class EnvironmentArchiveTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve() / "original"
        self.root.mkdir()
        self.output = self.root.parent / "private.zewenv"
        paths = {"python": "python.exe", "workspace": "workspace", "tools.sdk": "sdk",
                 "tools.cmake": "cmake.exe", "tools.ninja": "ninja.exe", "tools.git": "git.exe"}
        for key, value in paths.items():
            path = self.root / value
            if key in ("workspace", "tools.sdk"):
                path.mkdir()
            else:
                path.write_bytes(value.encode())
        for path, contents in ((".zew-managed", "managed"), (".zew-ready", "historic ready"),
                               ("workspace/.west/config", "manifest"), ("workspace/zephyr/VERSION", "4.4"),
                               ("sdk/sdk_version", "1.0.1"), ("sdk/compiler", "compiler bytes")):
            dest = self.root / path
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(contents)
        def escape(value):
            return str(value).replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=")
        (self.root / "environment.properties").write_text(
            "\n".join(key + "=" + escape(self.root / value) for key, value in paths.items()))
        (self.root / "empty").mkdir()

    def export(self):
        return archive.export_archive(self.root, self.output)

    def test_private_roundtrip_and_no_overwrite(self):
        receipt = self.export()
        self.assertEqual("exported", receipt["status"])
        self.assertEqual("payload-verified",
                         archive.archive_action("verify", self.output, expected=receipt["archive_sha256"])["status"])
        with self.assertRaises(FileExistsError):
            self.export()
        with self.assertRaises(archive.ArchiveError):
            archive.archive_action("restore", self.output, self.root)
        shutil.rmtree(self.root)
        self.assertEqual("restored-bytes", archive.archive_action("restore", self.output, self.root)["status"])
        self.assertEqual("compiler bytes", (self.root / "sdk/compiler").read_text())
        self.assertTrue((self.root / "empty").is_dir())
        self.assertTrue((self.root / ".zew-ready").is_file())

    def test_wrong_destination_rejected(self):
        self.export()
        target = self.root.parent / "another"
        with self.assertRaises(archive.ArchiveError):
            archive.archive_action("restore", self.output, target)
        self.assertFalse(target.exists())

    def test_payload_tamper_rejected_before_restore(self):
        self.export()
        bad = self.root.parent / "bad.zip"
        with zipfile.ZipFile(self.output) as old, zipfile.ZipFile(bad, "w") as new:
            for item in old.infolist():
                data = old.read(item)
                if item.filename == "payload/python.exe":
                    data = b"x" * len(data)
                new.writestr(item.filename, data)
        shutil.rmtree(self.root)
        with self.assertRaises(archive.ArchiveError):
            archive.archive_action("restore", bad, self.root)
        self.assertFalse(self.root.exists())

    def test_manifest_path_traversal_rejected(self):
        self.export()
        bad = self.root.parent / "bad.zip"
        with zipfile.ZipFile(self.output) as old, zipfile.ZipFile(bad, "w") as new:
            for item in old.infolist():
                data = old.read(item)
                if item.filename == "manifest.json":
                    manifest = json.loads(data)
                    manifest["directories"].append("../escape")
                    data = json.dumps(manifest).encode()
                new.writestr(item.filename, data)
        with self.assertRaises(archive.ArchiveError):
            archive.archive_action("verify", bad)

    def test_external_tool_rejected(self):
        outside = self.root.parent / "external.exe"
        outside.write_bytes(b"outside")
        props = self.root / "environment.properties"
        lines = props.read_text().splitlines()
        value = str(outside).replace("\\", "\\\\").replace(":", "\\:")
        props.write_text("\n".join("python=" + value if s.startswith("python=") else s for s in lines))
        with self.assertRaises(archive.ArchiveError):
            self.export()
        self.assertFalse(self.output.exists())

    def test_firmware_byte_comparison(self):
        original = self.root / "a.bin"
        rebuilt = self.root / "b.bin"
        original.write_bytes(b"firmware")
        rebuilt.write_bytes(b"firmware")
        self.assertEqual("match", archive.compare_firmware(original, rebuilt)["status"])
        rebuilt.write_bytes(b"changed")
        self.assertEqual("different", archive.compare_firmware(original, rebuilt)["status"])

    def test_privacy_and_trust_flags_required(self):
        with self.assertRaises(archive.ArchiveError):
            archive.main(["export", "--root", str(self.root), "--archive", str(self.output)])
        with self.assertRaises(archive.ArchiveError):
            archive.main(["restore", "--archive", str(self.output), "--destination", str(self.root)])

    def test_unsafe_names(self):
        for name in ("../x", "a/../x", "C:/x", "/x", "a\\b", "NUL.txt", "a.", "a//b"):
            with self.subTest(name=name), self.assertRaises(archive.ArchiveError):
                archive.safe_name(name)


if __name__ == "__main__":
    unittest.main()
