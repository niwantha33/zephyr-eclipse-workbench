<!-- SPDX-License-Identifier: Apache-2.0 -->
# Bounded CTF decoder-record regression fixtures

These are **synthetic decoder-normalized CTF event records** checked in as JSON Lines. They are **not** native CTF `metadata` plus `channel0_0` packets and **not** hardware recordings. The replay code yields the same dictionary shape as `semantic_trace.decode_ctf()`. A terminal `fixture_decoder_error` marker makes the iterator raise *after* the known prefix, simulating packet truncation; the semantic parser must preserve earlier evidence and mark the result incomplete.

| Fixture | Scenario |
|---|---|
| `complete_scheduler.jsonl` | Worker and idle paired switches, named tracepoint sample, matching sized allocation/free. The extra `size_bytes` is synthetic; stock Zephyr CTF does not provide it. |
| `truncated.jsonl` | Known scheduling and allocation prefix followed by simulated decoder failure; must not report end-of-capture balance or CPU load. |
| `no_memory_events.jsonl` | Paired switches and named sample but no allocation hooks; memory analyzer emits exact absence diagnostic, no step history. |
| `unknown_event_name.jsonl` | Known switch pair with unknown vendor event; retained in unknown count and label. |

Run: `python -m unittest discover -s tests/tools -p 'test_ctf_regression_harness.py' -v`.

This replay harness does not test Babeltrace 2 itself. A separate native integration suite should eventually include genuine, rights-cleared `metadata`+`channel0_0` captures with board, firmware SHA, Zephyr revision, decoder version and trace-loss evidence. Passing these fixtures is not hardware validation.
