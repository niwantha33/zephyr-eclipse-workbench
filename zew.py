#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only Zephyr evidence CLI with explicitly requested offline bundle output."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from zew_evidence.collector import InputError, SCHEMA_VERSION, collect, diagnose, why
from zew_evidence.compare import compare
from zew_evidence.bundle import preview, write, inspect


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="zew", description=__doc__)
    parser.add_argument("--version", action="version", version="zew evidence prototype 0.1.0")
    subs = parser.add_subparsers(dest="command", required=True)
    for command in ("graph", "diagnose", "why", "bundle"):
        sub = subs.add_parser(command)
        sub.add_argument("--build", required=True, type=Path, help="Existing build root; never modified")
        sub.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR",
                         help="Explicit sysbuild image within the root; repeat per image")
        if command == "why":
            sub.add_argument("symbol", help="CONFIG_... (observed; causal explanation unknown)")
        if command == "bundle":
            sub.add_argument("--output", type=Path, help="Target ZIP (required only with --write)")
            sub.add_argument("--write", action="store_true", help="Explicitly save a new ZIP; never overwrite")
    diff = subs.add_parser("compare", help="Observed differences between two generated Zephyr build roots")
    diff.add_argument("--left", type=Path, required=True)
    diff.add_argument("--right", type=Path, required=True)
    diff.add_argument("--left-image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    diff.add_argument("--right-image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    opening = subs.add_parser("inspect", help="Validate a portable ZIP without extracting it")
    opening.add_argument("--input", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "inspect":
            output = inspect(args.input)
        elif args.command == "compare":
            output = compare(collect(args.left, args.left_image),
                             collect(args.right, args.right_image))
        else:
            graph = collect(args.build, args.image)
            if args.command == "graph":
                output = graph
            elif args.command == "diagnose":
                output = diagnose(graph)
            elif args.command == "why":
                output = why(graph, args.symbol)
            elif args.write:
                if args.output is None:
                    raise InputError("--output ZIP is required when --write is specified")
                output = write(graph, args.output)
            else:
                output = preview(graph)
    except (InputError, OSError) as error:
        print(json.dumps({"schema_version": SCHEMA_VERSION, "status": "error",
                          "message": str(error)}, sort_keys=True), file=sys.stderr)
        return 2
    print(json.dumps(output, sort_keys=True, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
