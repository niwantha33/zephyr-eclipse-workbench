# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[2] / "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/serial_console.py"
spec = importlib.util.spec_from_file_location("serial_console", MODULE)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class FakePort:
    def __init__(self, data):
        self.data = list(data)
        self.closed = False

    def read(self, size):
        return self.data.pop(0)[:size] if self.data else b""

    def close(self):
        self.closed = True


class Tick:
    def __init__(self):
        self.value = 0

    def __call__(self):
        self.value += 0.05
        return self.value


class SerialConsoleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zew-serial-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_raw_capture_and_host_reception_time_not_target_time(self):
        stream = FakePort([b"boot\r\ncounter=1\n", b"broken:\xff\n", b"unfinished"])
        output = []
        folder = subject.capture("COM8", 115200, self.root, 0.3,
                                 serial_factory=lambda **kwargs: stream,
                                 read_clock=Tick(), show=output.append)
        self.assertTrue(stream.closed)
        self.assertEqual((folder / "raw.bin").read_bytes(),
                         b"boot\r\ncounter=1\nbroken:\xff\nunfinished")
        receipt = json.loads((folder / "receipt.json").read_text())
        self.assertEqual(receipt["schema"], "zew-uart-1")
        self.assertEqual(receipt["lines"], 4)
        self.assertEqual(receipt["truncated_lines"], 1)
        messages = [json.loads(x) for x in (folder / "messages.jsonl").read_text().splitlines()]
        self.assertTrue(all(x["target_timestamp"] is None for x in messages))
        self.assertTrue(all(x["host_received_utc"] for x in messages))
        self.assertTrue(messages[2]["contains_replacement"])
        self.assertIn("counter=1", output[1])

    def test_byte_limit_produces_explicit_stop_reason(self):
        port = FakePort([b"abcdef"])
        folder = subject.capture("/dev/ttyACM0", 115200, self.root, 5,
                                 serial_factory=lambda **kwargs: port,
                                 read_clock=Tick(), max_bytes=4)
        self.assertEqual((folder / "raw.bin").read_bytes(), b"abcd")
        self.assertEqual(json.loads((folder / "receipt.json").read_text())["stop_reason"],
                         "byte_limit")

    def test_port_closed_if_read_fails(self):
        class FailingPort(FakePort):
            def read(self, size):
                raise OSError("port disappeared")
        port = FailingPort([])
        with self.assertRaisesRegex(OSError, "port disappeared"):
            subject.capture("COM9", 115200, self.root, 1,
                            serial_factory=lambda **kwargs: port, read_clock=Tick())
        self.assertTrue(port.closed)

    def test_invalid_settings_do_not_open_port(self):
        def fail(**kwargs):
            raise AssertionError("should not open")
        for port in ("", "COM1\nbad"):
            with self.assertRaises(ValueError):
                subject.capture(port, 115200, self.root, 1, serial_factory=fail)
        with self.assertRaises(ValueError):
            subject.capture("COM8", 0, self.root, 1, serial_factory=fail)


if __name__ == "__main__":
    unittest.main()
