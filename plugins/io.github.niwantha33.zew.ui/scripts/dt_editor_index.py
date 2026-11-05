#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only content-assist index from the selected Zephyr checkout and build.

The protocol carries base64-encoded facts, never executes project source,
unpickles build artifacts, or edits a workspace. An absent/mismatched build
deliberately produces no controller claims.
"""
from __future__ import annotations

import argparse
import base64
import re
import sys
from pathlib import Path

MAX_DTS = 4 * 1024 * 1024
MAX_CACHE = 8 * 1024 * 1024
MAX_RECORDS = 18000


def emit(kind, *values):
    print("\t".join([kind, *(base64.b64encode(str(value).encode("utf-8")).decode("ascii")
                               for value in values)]))


def checked(path: Path, limit: int) -> bytes:
    path = path.absolute()
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise ValueError("missing, linked, or oversized input")
    before = path.stat()
    data = path.read_bytes()
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError("input changed")
    if b"\0" in data:
        raise ValueError("binary input")
    data.decode("utf-8")
    return data


def index(zephyr: Path, application: Path, build: Path):
    dts = build / "zephyr" / "zephyr.dts"
    cache = build / "CMakeCache.txt"
    if not dts.is_file() or not cache.is_file():
        emit("S", "no-build", "Configure/build the selected board for verified controller assistance")
        return
    zephyr = zephyr.resolve(strict=True)
    data = checked(cache, MAX_CACHE).decode("utf-8")
    bases = re.findall(r"(?m)^ZEPHYR_BASE:[A-Z_]+=(.*)$", data)
    if len(bases) != 1 or Path(bases[0]).resolve(strict=False) != zephyr:
        emit("S", "mismatch", "Build belongs to a different Zephyr checkout; no device suggestions")
        return
    checked(dts, MAX_DTS)
    python_src = zephyr / "scripts/dts/python-devicetree/src"
    if not (python_src / "devicetree/edtlib.py").is_file():
        emit("S", "unavailable", "The selected checkout has no Python Devicetree library")
        return
    roots = [zephyr / "dts/bindings", application / "dts/bindings"]
    roots = [p for p in roots if p.is_dir() and not p.is_symlink()]
    # Project root and selected Zephyr checkout only; never search other SDKs.
    if not roots:
        emit("S", "unavailable", "No selected binding roots")
        return
    sys.path.insert(0, str(python_src))
    from devicetree import dtlib, edtlib
    dt = dtlib.DT(str(dts))
    edt = edtlib.EDT(str(dts), [str(p) for p in roots])
    # Use upstream Binding so includes, child bindings and property metadata
    # have Zephyr's semantics; do not reinterpret YAML in the editor.
    yaml_paths = sorted(str(yaml) for root in roots
                        for yaml in root.rglob("*.yaml") if yaml.is_file())
    yaml_paths += sorted(str(yaml) for root in roots
                         for yaml in root.rglob("*.yml") if yaml.is_file())
    bindings = edtlib.bindings_from_paths(yaml_paths, ignore_errors=True)
    by_path = {str(Path(binding.path).resolve()): binding
               for binding in bindings if binding.path}
    dt_nodes = {node.path: node for node in dt.node_iter()}
    ed_nodes = {node.path: node for node in edt.nodes}
    emit("S", "built", "Selected generated zephyr.dts; changes since build are not included")
    count = 0
    for path, node in dt_nodes.items():
        observed = ed_nodes.get(path)
        labels = node.labels or [""]
        compat = ", ".join(node.props["compatible"].to_strings()) if "compatible" in node.props else ""
        status = observed.status if observed is not None else "unknown"
        for label in labels:
            emit("N", label, path, status, compat, node.lineno)
            count += 1
            if count >= MAX_RECORDS:
                return
        if observed is None or not observed.binding_path:
            continue
        binding = by_path.get(str(Path(observed.binding_path).resolve()))
        # A child-binding is not the same binding as the parent device.
        if "compatible" not in node.props and node.parent and node.parent.path in ed_nodes:
            parent = ed_nodes[node.parent.path]
            parent_binding = (by_path.get(str(Path(parent.binding_path).resolve()))
                              if parent.binding_path else None)
            if parent_binding and parent_binding.child_binding:
                binding = parent_binding.child_binding
        if binding is None:
            continue
        for name, spec in binding.prop2specs.items():
            if count >= MAX_RECORDS:
                return
            enum = "\x1f".join(map(str, spec.enum or []))
            constant = str(spec.const) if spec.const is not None else ""
            emit("P", path, name, spec.type, (spec.description or "").strip()[:700],
                 "yes" if spec.required else "no",
                 "yes" if spec.deprecated else "no",
                 enum, constant, str(spec.path or binding.path or ""))
            count += 1
    for binding in bindings:
        if count >= MAX_RECORDS:
            return
        if binding.compatible:
            emit("C", binding.compatible, binding.on_bus or "",
                 (binding.description or binding.title or "").strip()[:250])
            count += 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--zephyr", type=Path, required=True)
    parser.add_argument("--application", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    args = parser.parse_args()
    print("ZEW-DTASSIST-1")
    try:
        index(args.zephyr, args.application, args.build)
    except (OSError, ValueError, ImportError, Exception) as error:
        # Fail closed. Avoid exposing untrusted file text in protocol output.
        emit("S", "unavailable", "Selected build could not be interpreted by edtlib: " +
             type(error).__name__)


if __name__ == "__main__":
    main()
