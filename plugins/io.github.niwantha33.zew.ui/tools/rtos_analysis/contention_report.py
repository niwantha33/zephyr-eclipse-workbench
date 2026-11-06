#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only saved CTF contention report. No target/probe interaction.

Versioned bounded TSV with base64-encoded UTF-8 names and explanations.
A numeric threshold re-runs semantic detection (never guesses missing events).
"""
from __future__ import annotations

import argparse
import base64
from pathlib import Path

from contention_detector import ContentionDetector, DEFAULT_THRESHOLD_US
from semantic_trace import SemanticTrace, SemanticTraceParser


def _b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def serialize(report) -> str:
    lines = ["\t".join((
        "ZEW_CONTENTION", "1", str(int(report.inversion_enabled)),
        str(int(report.incomplete)), str(int(report.decoder_unavailable)),
        str(report.observed_resource_events), _b64(report.error),
    ))]
    for finding in report.findings:
        def prio(value):
            return "" if value is None else str(value)
        lines.append("\t".join((
            "F", finding.kind, _b64(finding.object_name), _b64(finding.waiter),
            _b64(finding.holder), str(finding.duration_ns),
            _b64(finding.medium_thread), prio(finding.waiter_priority),
            prio(finding.holder_priority), prio(finding.medium_priority),
            str(int(finding.holder_unknown)), _b64(finding.explanation),
        )))
    return "\n".join(lines) + "\n"


def analyze(source, threshold_us: int = DEFAULT_THRESHOLD_US) -> str:
    trace = source if isinstance(source, SemanticTrace) else SemanticTraceParser().parse(source)
    return serialize(ContentionDetector().detect(trace, threshold_us))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ctf", required=True, type=Path, help="Saved CTF directory")
    parser.add_argument("--threshold-us", type=int, default=DEFAULT_THRESHOLD_US)
    args = parser.parse_args()
    if (args.ctf.is_symlink() or not args.ctf.is_dir()
            or not (args.ctf / "metadata").is_file()
            or not (args.ctf / "channel0_0").is_file()):
        parser.error("Select a saved CTF directory with metadata and channel0_0")
    if not 0 <= args.threshold_us <= 86_400_000_000:
        parser.error("--threshold-us must be between 0 and 86400000000")
    print(analyze(args.ctf, args.threshold_us), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
