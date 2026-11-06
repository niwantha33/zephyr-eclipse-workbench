#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline named-event sample extraction using the shared semantic CTF parser.

Wire protocol: header ZEW_TRACEPOINTS v1, declaration rows D and sample rows S.
All variable strings are base64 UTF-8; all samples are finite numeric strings.
No live target connection, no automatic source changes or startup decoding.
"""
from __future__ import annotations

import argparse
import base64
from pathlib import Path

from semantic_trace import EventKind, SemanticTraceParser
from tracepoint_config import load_project

MAX_SAMPLES = 10000


def escape(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def extract(project_root: Path, source) -> str:
    config = load_project(project_root)
    status = config.status
    incomplete, unknown, detail = False, 0, ""
    by_name = {spec.name: [] for spec in config.specs}
    if status == "active":
        normalized = SemanticTraceParser().parse(source, tracepoints=config)
        incomplete = normalized.incomplete
        unknown = normalized.unknown_count
        detail = normalized.error
        if normalized.sample_errors:
            errors = "; ".join(normalized.sample_errors[:5])
            detail = (detail + "; " if detail else "") + "Invalid samples: " + errors
        if normalized.decoder_unavailable:
            status = "decode-unavailable"
        else:
            count = 0
            for event in normalized.events:
                if event.kind != EventKind.VALUE_SAMPLE:
                    continue
                if count >= MAX_SAMPLES:
                    incomplete = True
                    detail = (detail + "; " if detail else "") + "Sample report limited to 10000 events"
                    break
                by_name[event.tracepoint_name].append((event.timestamp_ns, event.value))
                count += 1
    lines = ["\t".join((
        "ZEW_TRACEPOINTS", "1", status, escape(config.error),
        str(int(incomplete)), str(unknown), escape(detail),
    ))]
    for spec in config.specs:
        lines.append("\t".join((
            "D", escape(spec.name), escape(spec.label), spec.type,
            str(len(by_name[spec.name])),
        )))
    for name, samples in by_name.items():
        for timestamp, value in samples:
            lines.append("\t".join(("S", escape(name), str(timestamp), str(value))))
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, type=Path, help="Imported project root")
    parser.add_argument("--ctf", required=True, type=Path, help="Saved CTF folder")
    args = parser.parse_args()
    root, ctf = args.project, args.ctf
    if not root.is_dir() or root.is_symlink():
        parser.error("Project must be a local non-symlink directory")
    config = load_project(root)
    # Inactive/invalid config is a normal, non-crashing result. Do not decode.
    if config.status == "active" and (
            ctf.is_symlink() or not ctf.is_dir()
            or not (ctf / "metadata").is_file()
            or not (ctf / "channel0_0").is_file()):
        parser.error("Saved CTF directory needs metadata and channel0_0")
    print(extract(root, ctf), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
