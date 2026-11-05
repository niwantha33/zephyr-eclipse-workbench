# SPDX-License-Identifier: Apache-2.0
"""Thin CLI surfaces for sequential Configuration Studio evidence milestones."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from .collector import InputError
from .studio_extensions import load_json


def _build(parser, images=True):
    parser.add_argument("--build", required=True, type=Path)
    if images:
        parser.add_argument("--image", action="append", default=[], metavar="NAME=RELATIVE_DIR")
        parser.add_argument("--auto-images", action="store_true", help="Read actual sysbuild domains.yaml; never guess image folders")


def _images(args):
    if args.auto_images:
        if args.image:
            raise InputError("Use explicit --image declarations or --auto-images, not both")
        from .studio_images import inspect, declarations
        return declarations(inspect(args.build))
    return args.image


def main(argv=None):
    parser = argparse.ArgumentParser(prog="zew", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    kconfig = commands.add_parser("studio-kconfig", help="CS03/04: upstream transitive Kconfig and request diagnostics")
    _build(kconfig)
    kconfig.add_argument("--snippet", type=Path)
    kconfig.add_argument("--symbol")
    kconfig.add_argument("--ninja", type=Path)
    dt = commands.add_parser("studio-dt", help="CS05: final property origin from upstream dtlib/CPP markers")
    _build(dt)
    for key in ("zephyr",):
        dt.add_argument("--" + key, type=Path, required=True)
    dt.add_argument("--node", required=True)
    dt.add_argument("--property", required=True)
    dt.add_argument("--source-root", type=Path, action="append", required=True)
    dt.add_argument("--include-values", action="store_true", help="Explicitly expose raw property bytes; may contain private values")
    driver = commands.add_parser("studio-driver", help="CS06: distinct candidate/configured/object/map/runtime evidence")
    _build(driver)
    driver.add_argument("--source", required=True)
    driver.add_argument("--source-root", type=Path, required=True)
    driver.add_argument("--symbol")
    resources = commands.add_parser("studio-resources", help="CS07: upstream decoded GPIO/pinctrl reference observations")
    _build(resources)
    resources.add_argument("--zephyr", required=True, type=Path)
    resources.add_argument("--bindings", type=Path, action="append", default=[])
    topology = commands.add_parser("studio-images", help="CS11: discover isolated images from generated sysbuild metadata")
    _build(topology, images=False)
    environment = commands.add_parser("studio-preflight", help="CS12: read-only configured environment prerequisites")
    _build(environment)
    environment.add_argument("--expected-board")
    environment.add_argument("--expected-zephyr", type=Path)
    environment.add_argument("--expect-version", action="append", default=[], metavar="TOOL=VERSION")
    compare = commands.add_parser("studio-compare", help="CS13: configuration/version/Devicetree differences by image")
    for side in ("left", "right"):
        compare.add_argument("--" + side, required=True, type=Path)
        compare.add_argument("--" + side + "-image", action="append", default=[])
        compare.add_argument("--" + side + "-zephyr", type=Path)
    diagnosis = commands.add_parser("studio-diagnose", help="CS08: normalize already extracted findings without raising confidence")
    diagnosis.add_argument("--input", type=Path, action="append", required=True)
    answers = commands.add_parser("studio-answer", help="CS15: explain an exact finding with rehashed evidence, or refuse")
    answers.add_argument("--input", type=Path, required=True)
    answers.add_argument("--finding", required=True)
    answers.add_argument("--evidence-root", type=Path, action="append", required=True)
    answers.add_argument("--expected-sha256")
    view = commands.add_parser("studio-view", help="CS17: focused recorded finding table or dependency graph")
    view.add_argument("--input", type=Path, required=True)
    view.add_argument("--format", choices=("markdown", "dot"), default="markdown")
    view.add_argument("--limit", type=int, default=100)
    view.add_argument("--focus")
    view.add_argument("--image")
    view.add_argument("--text", action="store_true", help="Print rendered content instead of the JSON envelope")
    extensions = commands.add_parser("studio-rules", help="CS18: evaluate explicit local, inert, versioned rule packs")
    extensions.add_argument("--pack", type=Path, action="append", required=True)
    extensions.add_argument("--observations", type=Path, required=True)
    extensions.add_argument("--zephyr-version")
    args = parser.parse_args(argv)
    try:
        if args.command == "studio-kconfig":
            from .studio_kconfig import inspect
            report = inspect(args.build, args.snippet, args.symbol, args.ninja, _images(args))
        elif args.command == "studio-dt":
            from .studio_devicetree import inspect
            report = inspect(args.build, args.zephyr, args.node, args.property, args.source_root,
                             _images(args), args.include_values)
        elif args.command == "studio-driver":
            from .studio_driver import inspect
            report = inspect(args.build, args.source, args.source_root, args.symbol, _images(args))
        elif args.command == "studio-resources":
            from .studio_resources import inspect
            report = inspect(args.build, args.zephyr, _images(args), args.bindings)
        elif args.command == "studio-images":
            from .studio_images import inspect
            report = inspect(args.build)
        elif args.command == "studio-preflight":
            from .studio_environment import inspect
            versions = {}
            for value in args.expect_version:
                if "=" not in value:
                    raise InputError("Use --expect-version TOOL=VERSION")
                tool, version = value.split("=", 1)
                if tool in versions:
                    raise InputError("Duplicate expected tool version")
                versions[tool] = version
            report = inspect(args.build, _images(args), args.expected_board, args.expected_zephyr, versions)
        elif args.command == "studio-compare":
            from .studio_compare import inspect
            report = inspect(args.left, args.right, args.left_image, args.right_image,
                             args.left_zephyr, args.right_zephyr)
        elif args.command == "studio-diagnose":
            from .studio_rules import normalize
            if len(args.input) > 64:
                raise InputError("Too many input diagnosis reports")
            report = normalize([load_json(path, 8 * 1024 * 1024)[0] for path in args.input])
        elif args.command == "studio-answer":
            from .studio_answers import answer
            report = answer(load_json(args.input, 8 * 1024 * 1024)[0], args.finding,
                            args.evidence_root, args.expected_sha256)
        elif args.command == "studio-view":
            from .studio_visualization import render
            report = render(load_json(args.input, 8 * 1024 * 1024)[0], args.format, args.limit,
                            args.focus, args.image)
        else:
            from .studio_extensions import evaluate
            report = evaluate(args.pack, load_json(args.observations)[0], args.zephyr_version)
    except (InputError, OSError, UnicodeError) as error:
        print(json.dumps({"status": "error", "message": str(error)}, sort_keys=True), file=sys.stderr)
        return 2
    if args.command == "studio-view" and args.text:
        print(report["content"], end="")
    else:
        print(json.dumps(report, sort_keys=True, ensure_ascii=False, indent=2, allow_nan=False))
    # Unavailable evidence remains visible and returns a nonzero diagnostic
    # status; successful JSON emission is not release/hardware qualification.
    if report.get("status") in ("partial", "partial-or-unavailable", "metadata-unavailable", "refused"):
        return 1
    if args.command == "studio-preflight" and any(row["state"] != "metadata-inspected" for row in report["images"]):
        return 1
    if args.command == "studio-kconfig" and any(f["rule_id"] != "CS-KCONFIG-VALID" for f in report["findings"]):
        return 1
    if args.command in ("studio-diagnose", "studio-rules") and any(f["severity"] == "error" for f in report["findings"]):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
