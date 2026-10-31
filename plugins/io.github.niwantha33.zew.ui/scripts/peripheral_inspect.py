#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Native Eclipse adapter of the exact packaged zew peripheral evidence core.

The four packaged zew_evidence modules must be byte-identical to the headless
CLI sources; enforced in tests. Only fixed enums and SHA-256s cross into Java.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
from pathlib import Path
import re
import sys

# Native managed Python may run with isolated _pth; load only the adjacent
# packaged source and exact selected Zephyr checkout modules.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from zew_evidence.collector import InputError
from zew_evidence.peripheral_evidence import inspect

ZERO = "0" * 64
_ALLOWED_ACTIONS = frozenset({
    "verify-exact-zephyr-checkout-and-generated-dts",
    "locate-node-in-generated-devicetree",
    "supply-actual-module-binding-roots",
    "review-source-overlay-and-reconfigure",
    "check-selected-property-against-upstream-binding",
    "inspect-generated-kconfig-with-kconfiglib",
    "generate-selected-build-config",
})
_NODE = re.compile(r"/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")


def protocol(report, requested_node, property_name, symbol):
    rows = report.get("images", [])
    if len(rows) != 1 or rows[0]["image"] != "default":
        raise InputError("Expected exactly one explicitly selected build image")
    row = rows[0]
    if report["node_path_sha256"] != hashlib.sha256(
            requested_node.encode("utf-8")).hexdigest():
        raise InputError("The requested node does not match the observed evidence")
    actions = row["next_actions"]
    if not isinstance(actions, list) or not set(actions) <= _ALLOWED_ACTIONS:
        raise InputError("Invalid native peripheral diagnostic actions")
    return (
        report["status"],
        row.get("generated_dts_sha256") or ZERO,
        row.get("generated_config_sha256") or ZERO,
        report["node_path_sha256"],
        report.get("symbol_sha256") or ZERO,
        row["node"]["state"],
        row["node"]["binding"],
        row["node"]["configured_status"],
        row["node"]["property"],
        row["node"]["property_type"],
        row["kconfig"]["effective"],
        "yes" if property_name is not None else "no",
        "yes" if symbol is not None else "no",
        ",".join(sorted(actions)) or "none",
    )


def write(fields):
    print("ZEW-PERIPHERAL-1")
    print("\t".join(base64.b64encode(str(x).encode("ascii")).decode("ascii")
                    for x in fields))


def main(argv=None):
    parser = argparse.ArgumentParser()
    for key in ("build", "zephyr", "node"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--property")
    parser.add_argument("--symbol")
    args = parser.parse_args(argv)
    fallback = ("unavailable", ZERO, ZERO, ZERO, ZERO,
                "unknown", "unknown", "unknown", "unknown", "unknown", "unknown",
                "yes" if args.property else "no", "yes" if args.symbol else "no",
                "verify-exact-zephyr-checkout-and-generated-dts")
    try:
        if not _NODE.fullmatch(args.node) or len(args.node) > 240:
            raise InputError("Invalid node path")
        result = inspect(Path(args.build), Path(args.zephyr), args.node,
                         prop=args.property, symbol=args.symbol)
        fields = protocol(result, args.node, args.property, args.symbol)
    except (InputError, OSError, ValueError, TypeError, KeyError, UnicodeError):
        fields = fallback
    write(fields)
    return 0 if fields[0] == "selected-observations-present" else 1


if __name__ == "__main__":
    raise SystemExit(main())
