#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only, exact-build DTS topology observation for the native Eclipse graph.

Protocol ZEW-DTSGRAPH-1: ASCII tab-separated records, with all payloads base64
UTF-8 encoded. S(state, sha256, reason), N(path, name, status, compatible,
labels, line), E(source, target, relation, property, detail). A relation is
shown only if upstream dtlib or edtlib resolved its exact target node.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import re
import sys
from pathlib import Path

MAX_DTS_BYTES = 4 * 1024 * 1024
MAX_CACHE_BYTES = 8 * 1024 * 1024
MAX_NODES = 3500
MAX_EDGES = 6000
MAX_RECORD_BYTES = 3_000_000
FORBIDDEN = (b"/include/", b"/incbin/")


def emit(tag: str, *values: object) -> None:
    print("\t".join([tag, *(base64.b64encode(str(v).encode("utf-8")).decode("ascii")
                           for v in values)]))


def checked(path: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError("missing-or-linked-input")
    before = path.stat()
    if before.st_size > limit:
        raise ValueError("input-size-limit")
    data = path.read_bytes()
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError("input-changed")
    if len(data) > limit or b"\0" in data:
        raise ValueError("invalid-input-bytes")
    data.decode("utf-8")
    return data


def observed(zephyr: Path, application: Path, build: Path):
    zephyr = zephyr.resolve(strict=True)
    application = application.resolve(strict=True)
    if not zephyr.is_dir() or not application.is_dir():
        raise ValueError("selected-path-not-directory")
    cache = build / "CMakeCache.txt"
    generated = build / "zephyr/zephyr.dts"
    if not cache.is_file() or not generated.is_file():
        return ("no-build", "", "Select and build the exact board/profile first.", [], [])
    config = checked(cache, MAX_CACHE_BYTES).decode("utf-8")
    # CMakeCache.txt uses CRLF on Windows; trim the captured record terminator.
    # Retain interior spaces in paths such as "C:/Sandbox/Zephyr Setup".
    roots = [value.strip() for value in
             re.findall(r"(?m)^ZEPHYR_BASE:[A-Z_]+=(.*)$", config)]
    if len(roots) != 1 or Path(roots[0]).resolve(strict=False) != zephyr:
        return ("mismatch", "", "Selected build and Zephyr checkout disagree.", [], [])
    raw = checked(generated, MAX_DTS_BYTES)
    digest = hashlib.sha256(raw).hexdigest()
    if any(token in raw for token in FORBIDDEN):
        return ("unavailable", digest, "Generated DTS requests external parser reads.", [], [])
    library = zephyr / "scripts/dts/python-devicetree/src"
    if not (library / "devicetree/dtlib.py").is_file():
        return ("unavailable", digest, "Selected Zephyr checkout lacks dtlib.", [], [])
    sys.path.insert(0, str(library))
    try:
        from devicetree import dtlib
        dt = dtlib.DT(str(generated))
        nodes = list(dt.node_iter())
        if len(nodes) > MAX_NODES:
            return ("unavailable", digest, "Devicetree exceeds graph node safety limit.", [], [])
        by_path = {node.path: node for node in nodes}
        nrows = []
        edges = []
        seen_edges = set()

        def link(source, target, kind, prop, detail=""):
            if source not in by_path or target not in by_path:
                return
            if len(prop) > 120 or len(detail) > 180:
                return
            key = (source, target, kind, prop, detail)
            if key not in seen_edges:
                if len(edges) >= MAX_EDGES:
                    raise ValueError("graph-edge-limit")
                seen_edges.add(key)
                edges.append(key)

        for node in nodes:
            props = node.props
            status = props["status"].to_string() if "status" in props else "okay (default)"
            compatible = (", ".join(props["compatible"].to_strings())
                          if "compatible" in props else "")
            nrows.append((node.path, node.name, status[:80], compatible[:240],
                          ",".join(node.labels or [])[:160], int(node.lineno)))
            # dtlib knows the target identity for single/multiple phandles.
            # Never guess a reference from numeric or mixed cells.
            for prop_name, prop in props.items():
                kind = prop.type.name
                try:
                    if kind == "PHANDLE":
                        link(node.path, prop.to_node().path, "phandle", prop_name)
                    elif kind == "PHANDLES":
                        for index, target in enumerate(prop.to_nodes()):
                            link(node.path, target.path, "phandle", prop_name, str(index))
                    elif kind == "PATH":
                        link(node.path, prop.to_path().path, "path", prop_name)
                except (ValueError, KeyError, AttributeError):
                    continue

        state = "hierarchy-only"
        reason = "Observed generated DTS hierarchy; binding-aware links unavailable."
        try:
            from devicetree import edtlib
            roots = [zephyr / "dts/bindings", application / "dts/bindings"]
            roots = [str(root) for root in roots if root.is_dir() and not root.is_symlink()]
            if not roots:
                raise ValueError("binding-roots-unavailable")
            edt = edtlib.EDT(str(generated), roots)
            for node in edt.nodes:
                if node.path not in by_path:
                    continue
                for prop_name, prop in node.props.items():
                    if prop.type != "phandle-array":
                        continue
                    for index, spec in enumerate(prop.val or []):
                        if spec is None or getattr(spec, "controller", None) is None:
                            continue
                        controller = spec.controller.path
                        if prop_name == "gpios" or prop_name.endswith("-gpios"):
                            kind = "gpio"
                        elif prop_name.startswith("clocks"):
                            kind = "clock"
                        elif prop_name.startswith("interrupt"):
                            kind = "interrupt"
                        elif prop_name.startswith("resets"):
                            kind = "reset"
                        else:
                            kind = "phandle-array"
                        cells = getattr(spec, "data", None)
                        # Cell names come from edtlib's selected controller binding;
                        # never interpret undocumented vendor raw pinmux encodings.
                        named = ""
                        if isinstance(cells, dict) and isinstance(cells.get("pin"), int):
                            named = "pin=" + str(cells["pin"])
                        link(node.path, controller, kind, prop_name,
                             "[" + str(index) + "]" + (" " + named if named else ""))
                for state_record in getattr(node, "pinctrls", []):
                    for config in getattr(state_record, "conf_nodes", []):
                        link(node.path, config.path, "pinctrl", "pinctrl",
                             str(state_record.name)[:80])
            state = "built"
            reason = "Generated DTS + selected-checkout edtlib references; runtime unverified."
        except (ImportError, ValueError, OSError, AttributeError, TypeError):
            # A binding failure must never invent a GPIO/clock relationship.
            reason = "Generated hierarchy available; edtlib references unavailable."
        if hashlib.sha256(checked(generated, MAX_DTS_BYTES)).hexdigest() != digest:
            return ("unavailable", "", "Generated DTS changed during inspection.", [], [])
        if len(nrows) + len(edges) > 8500:
            raise ValueError("graph-record-limit")
        return (state, digest, reason, nrows, edges)
    finally:
        sys.path.pop(0)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zephyr", required=True, type=Path)
    parser.add_argument("--application", required=True, type=Path)
    parser.add_argument("--build", required=True, type=Path)
    args = parser.parse_args()
    print("ZEW-DTSGRAPH-1")
    try:
        state, digest, reason, nodes, edges = observed(
            args.zephyr, args.application, args.build)
        # No partial success report: format and size are bounded before emit.
        records = [("S", state, digest, reason)]
        records += [("N", *row) for row in nodes]
        records += [("E", *row) for row in edges]
        budget = sum(len("\t".join(map(str, row))) * 2 + 16 for row in records)
        if budget > MAX_RECORD_BYTES:
            records = [("S", "unavailable", "", "Graph protocol safety limit exceeded.")]
        for row in records:
            emit(*row)
    except Exception as error:
        # Never publish exception messages or source content from user files.
        emit("S", "unavailable", "", "Selected Devicetree could not be interpreted: "
             + type(error).__name__)


if __name__ == "__main__":
    main()
