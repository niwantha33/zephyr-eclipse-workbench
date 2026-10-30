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
from zew_evidence.freshness import verify
from zew_evidence.spdx import inspect_build
from zew_evidence.inclusion import explain
from zew_evidence.compilation import inspect as inspect_compilation
from zew_evidence.linker import inspect as inspect_linker
from zew_evidence.link_audit import audit as audit_link_map
from zew_evidence.section_audit import audit as audit_elf_sections


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="zew", description=__doc__)
    parser.add_argument("--version", action="version", version="zew evidence prototype 0.1.1")
    subs = parser.add_subparsers(dest="command", required=True)
    for command in ("graph", "diagnose", "why", "bundle"):
        sub = subs.add_parser(command)
        sub.add_argument("--build", required=True, type=Path, help="Existing build root; never modified")
        sub.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR",
                         help="Explicit sysbuild image within the root; repeat per image")
        if command == "why":
            sub.add_argument("symbol", nargs="?", help="CONFIG_... (observed; causal explanation unknown)")
            lookup = sub.add_mutually_exclusive_group()
            lookup.add_argument("--source", help="Exact SPDX source file name (no suffix guessing)")
            lookup.add_argument("--module", help="Exact SPDX package/module name")
            sub.add_argument("--source-root", type=Path, help="Explicit local source root for compiler evidence")
            sub.add_argument("--link-evidence", action="store_true",
                             help="Inspect object and GNU linker-map observations; requires --source and --source-root")
            sub.add_argument("--range-audit", action="store_true",
                             help="Attach independent GNU archive and ELF range audit; requires --link-evidence")
        if command == "bundle":
            sub.add_argument("--output", type=Path, help="Target ZIP (required only with --write)")
            sub.add_argument("--write", action="store_true", help="Explicitly save a new ZIP; never overwrite")
    compiler = subs.add_parser("compile", help="Read-only exact source entry in compile_commands.json")
    compiler.add_argument("--build", required=True, type=Path)
    compiler.add_argument("--source", required=True)
    compiler.add_argument("--source-root", required=True, type=Path)
    compiler.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    linked = subs.add_parser("link", help="Read-only source to object to GNU linker-map observations")
    linked.add_argument("--build", type=Path, required=True)
    linked.add_argument("--source", required=True)
    linked.add_argument("--source-root", type=Path, required=True)
    linked.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    linked.add_argument("--range-audit", action="store_true", help="Add per-image ELF PT_LOAD and GNU archive observations")
    audit = subs.add_parser("link-audit", help="Read-only per-image GNU archive/ELF/map range audit")
    audit.add_argument("--build", type=Path, required=True)
    audit.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    audit.add_argument("--section-evidence", action="store_true",
                       help="Append independent GNU output-section to ELF metadata checks")
    sections = subs.add_parser("section-audit", help="Read-only GNU output-section to ELF byte-hash evidence")
    sections.add_argument("--build", type=Path, required=True)
    sections.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    diff = subs.add_parser("compare", help="Compare two builds or verified offline bundles")
    lg = diff.add_mutually_exclusive_group(required=True)
    lg.add_argument("--left", type=Path, help="Generated build root")
    lg.add_argument("--left-bundle", type=Path, help="Portable evidence ZIP")
    rg = diff.add_mutually_exclusive_group(required=True)
    rg.add_argument("--right", type=Path, help="Generated build root")
    rg.add_argument("--right-bundle", type=Path, help="Portable evidence ZIP")
    diff.add_argument("--left-image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    diff.add_argument("--right-image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
    opening = subs.add_parser("inspect", help="Validate and inspect a portable ZIP without extracting")
    opening.add_argument("--input", type=Path, required=True)
    opening.add_argument("--show-graph", action="store_true",
                         help="Show integrity-validated, sanitised graph stored in archive")
    sbom = subs.add_parser("sbom", help="Inspect locally generated upstream west SPDX build evidence")
    sbom.add_argument("--build", type=Path, required=True, help="Existing Zephyr build root")
    sbom.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR",
                      help="Explicit multi-image sub-builds, without auto-generating SPDX")
    checking = subs.add_parser("verify", help="Check archived artifact hashes against an existing build")
    checking.add_argument("--input", type=Path, required=True, help="Integrity-checked evidence ZIP")
    checking.add_argument("--build", type=Path, required=True, help="Existing build root to check without modification")
    args = parser.parse_args(argv)
    try:
        if args.command == "section-audit":
            output = audit_elf_sections(args.build, args.image)
        elif args.command == "link-audit":
            output = audit_link_map(args.build, args.image)
            if args.section_evidence:
                sections = audit_elf_sections(args.build, args.image)
                by_image = {item["image"]: item for item in sections["images"]}
                for item in output["images"]:
                    item["independent_section_audit"] = by_image[item["image"]]
                output["limitations"].extend(sections["limitations"])
        elif args.command == "link":
            output = inspect_linker(args.build, args.source, args.source_root, args.image)
            if args.range_audit:
                checks = audit_link_map(args.build, args.image)
                indexed = {row["image"]: row for row in checks["images"]}
                for row in output["images"]:
                    row["independent_elf_range_audit"] = indexed[row["image"]]
                output["limitations"].extend(checks["limitations"])
        elif args.command == "compile":
            output = inspect_compilation(args.build, args.source, args.source_root, args.image)
        elif args.command == "sbom":
            output = inspect_build(args.build, args.image)
        elif args.command == "verify":
            output = verify(inspect(args.input, include_graph=True)["graph"], args.build)
        elif args.command == "inspect":
            output = inspect(args.input, include_graph=args.show_graph)
        elif args.command == "compare":
            if (args.left_bundle and args.left_image) or (args.right_bundle and args.right_image):
                raise InputError("--left-image/--right-image apply only to live build roots")
            left = inspect(args.left_bundle, include_graph=True)["graph"] if args.left_bundle else collect(args.left, args.left_image)
            right = inspect(args.right_bundle, include_graph=True)["graph"] if args.right_bundle else collect(args.right, args.right_image)
            output = compare(left, right)
        elif args.command == "why" and (args.source is not None or args.module is not None):
            if args.symbol is not None:
                raise InputError("Use a CONFIG_ symbol or --source/--module, not both")
            if args.source_root is not None and args.source is None:
                raise InputError("--source-root requires --source")
            if args.link_evidence and (args.source is None or args.source_root is None):
                raise InputError("--link-evidence requires --source and --source-root")
            if args.range_audit and not args.link_evidence:
                raise InputError("--range-audit requires --link-evidence")
            output = explain(args.build, "source" if args.source is not None else "module",
                             args.source if args.source is not None else args.module, args.image)
            if args.source_root is not None:
                compilation = inspect_compilation(args.build, args.source, args.source_root, args.image)
                matches = {x["image"]: x for x in compilation["images"]}
                for item in output["images"]:
                    item["compile_database"] = matches[item["image"]]
                output["limitations"].extend(compilation["limitations"])
            if args.link_evidence:
                linked_evidence = inspect_linker(args.build, args.source, args.source_root, args.image)
                mapped = {x["image"]: x for x in linked_evidence["images"]}
                for item in output["images"]:
                    item["linker_map"] = mapped[item["image"]]
                output["limitations"].extend(linked_evidence["limitations"])
                if args.range_audit:
                    checks = audit_link_map(args.build, args.image)
                    indexed = {row["image"]: row for row in checks["images"]}
                    for row in output["images"]:
                        row["independent_elf_range_audit"] = indexed[row["image"]]
                    output["limitations"].extend(checks["limitations"])
        else:
            if args.command == "why" and args.link_evidence:
                raise InputError("--link-evidence requires --source and --source-root")
            if args.command == "why" and args.range_audit:
                raise InputError("--range-audit requires --link-evidence")
            if args.command == "why" and args.source_root is not None:
                raise InputError("--source-root only applies to --source")
            graph = collect(args.build, args.image)
            if args.command == "graph":
                output = graph
            elif args.command == "diagnose":
                output = diagnose(graph)
            elif args.command == "why":
                if args.symbol is None:
                    raise InputError("Specify a CONFIG_ symbol, --source or --module")
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
    return 1 if args.command == "verify" and output["status"] != "recorded-artifacts-match" else 0


if __name__ == "__main__":
    raise SystemExit(main())
