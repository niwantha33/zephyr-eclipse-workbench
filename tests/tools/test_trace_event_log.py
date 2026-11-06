# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import unittest

path = (Path(__file__).resolve().parents[2] /
        "plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/thread_event_log.py")
spec = importlib.util.spec_from_file_location("thread_event_log", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def event(name, ns, thread="1", stream=0, label=None):
    return {"kind": "event", "name": name, "thread_id": thread, "thread_name": label,
            "timestamp_ns": ns, "stream": stream}

class EventLogTests(unittest.TestCase):
    def test_switch_out_never_guesses_sleep(self):
        table = module.events_table([event("thread_switched_in", 1),
                                     event("thread_switched_out", 2),
                                     event("thread_ready", 3)])
        self.assertIn("UNKNOWN", table["rows"][1]["observed"])
        self.assertIn("UNKNOWN", table["rows"][2]["observed"])
        self.assertEqual(table["events_observed"], 3)

    def test_isr_names_other_hooks(self):
        rows = module.events_table([event("thread_name_set", 1, label="worker"),
                                    event("isr_enter", 2, None), event("isr_exit", 3, None),
                                    event("thread_switched_in", 4),
                                    event("mutex_take", 5)])["rows"]
        self.assertEqual(rows[3]["thread_name"], "worker")
        self.assertIn("ISR entry", rows[1]["observed"])
        self.assertIn("ISR exit", rows[2]["observed"])
        self.assertIn("UNKNOWN", rows[4]["observed"])

    def test_loss_clock_and_bounding(self):
        table = module.events_table([event("thread_switched_in", 10),
                                     {"kind": "discarded_events", "count": 3},
                                     event("thread_switched_out", 5, stream=1)])
        self.assertIn("reported CTF loss", table["quality"])
        self.assertIn("INCOMPLETE", table["rows"][1]["observed"])
        self.assertIn("missing/reordered", module.events_table([event("isr_enter", None)])["quality"])
        many = module.events_table([event("thread_switched_in", i) for i in range(900)])
        self.assertEqual(len(many["rows"]), 128)
        self.assertEqual(many["rows_omitted"], 772)
        self.assertIsNone(module.events_table([event("thread_sleep", None)])["rows"][0]["timestamp_ns"])
        with self.assertRaises(ValueError):
            module.events_table([], 500)

if __name__ == "__main__":
    unittest.main()
