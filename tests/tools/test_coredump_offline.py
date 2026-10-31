# SPDX-License-Identifier: Apache-2.0
"""Offline Zephyr coredump host-only and input-integrity tests."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace

MODULE = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/coredump_offline.py"
spec = importlib.util.spec_from_file_location("coredump_offline", MODULE)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class CoredumpTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="zephyr offline core ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.zephyr = self.root / "zephyr"
        (self.zephyr / "scripts/coredump").mkdir(parents=True)
        (self.zephyr / "scripts/coredump/coredump_gdbserver.py").write_text(
            "# fake runner\npass\n")
        self.elf = self.root / "zephyr.elf"
        self.elf.write_bytes(b"\x7fELF" + b"\x00" * 48)
        self.dump = self.root / "dump.bin"
        self.dump.write_bytes(b"ZE\x01\x00" + b"\x01" * 12)
        self.gdb = self.root / "gdb"
        self.gdb.write_bytes(b"FAKE GDB binary")
        self.args = dict(gdb=self.gdb, python=Path(sys.executable), elf=self.elf,
                         dump=self.dump, zephyr=self.zephyr)

    def test_requires_explicit_elf_identity_confirmation(self):
        with self.assertRaisesRegex(ValueError, "confirm-elf"):
            subject.investigate(**self.args)

    def test_runs_only_upstream_gdb_pipe_and_keeps_provenance(self):
        def runner(command, **kwargs):
            self.assertIn("--batch", command)
            self.assertIn("--nx", command)
            self.assertIn("set auto-load off", command)
            self.assertTrue(any(x.startswith("target remote | ") for x in command))
            self.assertIn("--pipe", command[command.index("-ex") + 1:] if False
                          else " ".join(command))
            self.assertNotIn("localhost:1234", " ".join(command))
            self.assertEqual(kwargs["cwd"], str(self.zephyr.resolve()))
            kwargs["stdout"].write(b"r0 0x00000000\n#0 0x1000 in main ()\n")
            return SimpleNamespace(returncode=0)
        report = subject.investigate(**self.args, confirmed=True, runner=runner)
        self.assertEqual(report["status"], "inspected")
        self.assertEqual(report["elf_match"],
                         "USER CONFIRMED — NOT independently proven by dump bytes")
        self.assertIn("main ()", report["gdb_output"])
        self.assertEqual(len(report["elf_sha256"]), 64)
        self.assertEqual(len(report["coredump_sha256"]), 64)

    def test_invalid_dump_and_elf_are_rejected(self):
        self.dump.write_bytes(b"XX\x01\x00")
        with self.assertRaisesRegex(ValueError, "ZE header"):
            subject.investigate(**self.args, confirmed=True)
        self.dump.write_bytes(b"ZE\x01\x00")
        self.elf.write_bytes(b"NOTELF")
        with self.assertRaisesRegex(ValueError, "ELF"):
            subject.investigate(**self.args, confirmed=True)

    def test_windows_pipe_metacharacters_cannot_escape(self):
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            subject.quote_pipe(r"C:\Firmware & delete\gdb.exe", "nt")
        self.assertIn('"C:\\Tools\\gdb.exe"', subject.quote_pipe(r"C:\Tools\gdb.exe", "nt"))
        self.assertEqual(subject.quote_pipe("/tmp/a b", "posix"), "'/tmp/a b'")

    def test_gdb_error_is_reported_as_unavailable_not_silent_success(self):
        def runner(_args, **kwargs):
            kwargs["stdout"].write(b"Target reported invalid core dump\n")
            return SimpleNamespace(returncode=1)
        result = subject.investigate(**self.args, confirmed=True, runner=runner)
        self.assertEqual(result["status"], "unavailable")
        self.assertEqual(result["exit_code"], 1)

    def test_missing_selected_checkout_stub_is_rejected(self):
        (self.zephyr / "scripts/coredump/coredump_gdbserver.py").unlink()
        with self.assertRaisesRegex(ValueError, "lacks"):
            subject.investigate(**self.args, confirmed=True)


if __name__ == "__main__":
    unittest.main()
