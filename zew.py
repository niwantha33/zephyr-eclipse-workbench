#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Phase-0 read-only Zephyr evidence CLI. JSON output on stdout, errors on stderr."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from zew_evidence.collector import InputError, SCHEMA_VERSION, collect, diagnose, why


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="zew", description=__doc__)
    parser.add_argument("--version", action="version", version="zew evidence prototype 0.1.0")
    subs = parser.add_subparsers(dest="command", required=True)
    for command in ("graph", "diagnose", "why"):
        sub = subs.add_parser(command)
        sub.add_argument("--build", required=True, type=Path, help="Existing build root; never modified")
        sub.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR",
                         help="Explicit sysbuild image within the root; repeat per image")
        if command == "why":
            sub.add_argument("symbol", help="CONFIG_... (observed, not causally explained)")
    args = parser.parse_args(argv)
    try:
        graph = collect(args.build, args.image)
        output = graph if args.command == "graph" else (
            diagnose(graph) if args.command == "diagnose" else why(graph, args.symbol))
    except (InputError, OSError) as error:
        print(json.dumps({"schema_version": SCHEMA_VERSION, "status": "error",
                          "message": str(error)}, sort_keys=True), file=sys.stderr)
        return 2
    print(json.dumps(output, sort_keys=True, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
