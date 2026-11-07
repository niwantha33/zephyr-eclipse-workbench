#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Export evidence-only memory profiles from SAVED Zephyr CTF traces.

Version 1 wire format is bounded UTF-8/base64 tab-separated text. Empty
byte/time fields mean UNAVAILABLE, never zero. A truncated CTF never emits
the end-of-trace imbalance sentence.
"""
from __future__ import annotations

import argparse
import base64
from pathlib import Path

from semantic_trace import SemanticTrace, SemanticTraceParser
from memory_profiler import MemoryProfiler

MAX_WIRE_STEPS = 5000
MAX_WIRE_POOLS = 512


def _b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def _numeric(value: int | None) -> str:
    return "" if value is None else str(value)


def serialize(profile) -> str:
    step_limited = len(profile.steps) > MAX_WIRE_STEPS
    pool_limited = len(profile.pools) > MAX_WIRE_POOLS
    diagnostics = "; ".join(profile.diagnostics[:12])
    if pool_limited:
        diagnostics += "; Only first 512 pools displayed" if diagnostics else "Only first 512 pools displayed"
    if step_limited:
        diagnostics += "; Step history limited to 5000 rows" if diagnostics else "Step history limited to 5000 rows"
    fields = (
        "ZEW_MEMORY", "1", str(profile.memory_events),
        str(int(profile.incomplete)), str(int(profile.decoder_unavailable)),
        str(int(profile.count_complete)), str(int(profile.bytes_complete)),
        _numeric(profile.current_count), _numeric(profile.current_bytes),
        _numeric(profile.peak_bytes), _numeric(profile.peak_timestamp_ns),
        _b64(profile.message), _b64(profile.imbalance_line),
        _b64(diagnostics[:2000]), str(int(step_limited)), str(int(pool_limited)),
    )
    lines = ["\t".join(fields)]
    for pool in profile.pools[:MAX_WIRE_POOLS]:
        lines.append("\t".join((
            "P", _b64(pool.name), str(pool.alloc_count), str(pool.free_count),
            _numeric(pool.current_count), _numeric(pool.current_bytes),
            _numeric(pool.peak_bytes),
        )))
    for step in profile.steps[:MAX_WIRE_STEPS]:
        lines.append("\t".join((
            "S", str(step.timestamp_ns),
            _numeric(step.outstanding_count),
            _numeric(step.outstanding_bytes),
        )))
    return "\n".join(lines) + "\n"


def analyze(source) -> str:
    trace = source if isinstance(source, SemanticTrace) else SemanticTraceParser().parse(source)
    return serialize(MemoryProfiler().calculate(trace))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ctf", type=Path, required=True, help="Saved CTF directory")
    args = parser.parse_args()
    ctf = args.ctf
    if (ctf.is_symlink() or not ctf.is_dir()
            or not (ctf / "metadata").is_file()
            or not (ctf / "channel0_0").is_file()):
        parser.error("Select saved CTF directory containing metadata and channel0_0")
    print(analyze(ctf), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
