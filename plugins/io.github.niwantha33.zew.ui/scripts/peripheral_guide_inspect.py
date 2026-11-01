#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Native thin wire adapter over byte-identical zew peripheral_guide code.

The caller explicitly chose to show local source diffs. No source is edited.
Never export raw exceptions; only a bounded diff from the headless guide.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from zew_evidence.collector import InputError
from zew_evidence.peripheral_guide import guide

ZERO = "0" * 64


def fields_for(report):
    draft = report["proposal"]
    diff = draft["visible_diff"] or ""
    if len(diff.encode("utf-8")) > 102400:
        raise InputError("Oversized selected source diff")
    # Unified diff preserves previous contents as context. Only additions
    # are copyable: never accidentally copy existing overlay into an append.
    additions = []
    for line in diff.splitlines(keepends=True):
        if line.startswith("+") and not line.startswith("+++ "):
            additions.append(line[1:])
    addition = "".join(additions)
    if draft["available"] and (not addition or
            hashlib.sha256(addition.encode("utf-8")).hexdigest()
            != draft["source_addition_sha256"]):
        raise InputError("Unable to recover exact original proposed addition")
    return (
        report["status"], report["request_type"],
        report["node_sha256"], report["property_sha256"],
        report["generated_dts_sha256"],
        report["overlay"]["original_sha256"] or ZERO,
        draft["post_change_overlay_sha256"] or ZERO,
        report["source"]["binding"], report["source"]["confidence"],
        report["source"]["property_type"], report["source"]["generated_status"],
        report["overlay"]["mode"], diff, addition,
    )


def wire(fields):
    print("ZEW-GUIDE-1")
    print("\t".join(base64.b64encode(str(item).encode("utf-8")).decode("ascii")
                    for item in fields))


def main(argv=None):
    parser = argparse.ArgumentParser()
    for arg in ("build", "zephyr", "node", "application", "overlay"):
        parser.add_argument("--" + arg, required=True)
    opts = parser.add_mutually_exclusive_group(required=True)
    opts.add_argument("--status", choices=("okay", "disabled"))
    opts.add_argument("--set-int")
    opts.add_argument("--set-enum")
    args = parser.parse_args(argv)
    fallback = ("unavailable", "unknown", ZERO, ZERO, ZERO, ZERO, ZERO,
                "unknown", "unknown", "unknown", "unknown", "unknown", "", "")
    try:
        response = guide(Path(args.build), Path(args.zephyr), args.node,
                         status=args.status, integer=args.set_int, enum=args.set_enum,
                         app=Path(args.application), overlay=Path(args.overlay),
                         show_diff=True)
        values = fields_for(response)
    except (InputError, OSError, ValueError, KeyError, TypeError, UnicodeError):
        values = fallback
    wire(values)
    return 0 if values[0] == "draft-requires-real-configuration" else 1


if __name__ == "__main__":
    raise SystemExit(main())
