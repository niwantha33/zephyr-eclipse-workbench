#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Native Eclipse's read-only EDT probe for the *selected* generated build.

Prints a bounded base64 protocol, never user-provided DTS, YAML, host paths,
property values or arbitrary exception messages. Edtlib is imported from the
selected Zephyr checkout. This script never unpickles edt.pickle.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory

NODE_PATH = re.compile(r"/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
PROPERTY = re.compile(r"[A-Za-z][A-Za-z0-9,._+?-]{0,99}")
CACHE = re.compile(r"^ZEPHYR_BASE:[A-Z_]+=(.*)$")
ALLOWED_TYPES = {"boolean", "int", "array", "uint8-array", "string",
                 "string-array", "phandle", "phandles", "phandle-array", "path"}
MAX_DTS = 4 * 1024 * 1024
MAX_OVERLAY = 64 * 1024
MAX_CACHE = 8 * 1024 * 1024


def read_local(path, limit):
    path = Path(path).absolute()
    if any(part.is_symlink() for part in (path, *path.parents)) or not path.is_file():
        raise ValueError("invalid-local-file")
    before = path.stat()
    if before.st_size > limit:
        raise ValueError("input-too-large")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError("input-changed")
    if len(data) > limit or b"\x00" in data:
        raise ValueError("invalid-input")
    data.decode("utf-8", errors="strict")
    return data


def snapshot_checkout(cache, zephyr):
    data = read_local(cache, MAX_CACHE).decode("utf-8")
    bases = [match.group(1) for raw in data.splitlines()
             if (match := CACHE.fullmatch(raw)) is not None]
    return len(bases) == 1 and Path(bases[0]).resolve(strict=True) == zephyr.resolve(strict=True)


def describe(edt, node_path, prop):
    try:
        node = edt.get_node(node_path)
    except Exception:
        return ("missing", "unknown", "unknown", "unknown", "unknown")
    bound = node.binding_path is not None
    binding = "matched" if bound else "unmatched"
    status = node.status if node.status in ("okay", "disabled", "reserved", "fail", "fail-sss") else "other"
    if prop is None:
        return ("present", binding, "not-requested", "none", status)
    if not bound:
        return ("present", "unmatched", "unknown", "none", status)
    value = node.props.get(prop)
    if value is None:
        return ("present", "matched", "not-interpreted", "none", status)
    kind = value.type if value.type in ALLOWED_TYPES else "other"
    return ("present", "matched", "interpreted", kind, status)


def evaluate(build, zephyr, application, node, prop=None, overlay=None):
    if not NODE_PATH.fullmatch(node) or len(node) > 240:
        raise ValueError("invalid-node")
    if prop is not None and not PROPERTY.fullmatch(prop):
        raise ValueError("invalid-property")
    build, zephyr, application = map(lambda value: Path(value).absolute(),
                                      (build, zephyr, application))
    if any(path.is_symlink() for path in (build, *build.parents,
                                         zephyr, *zephyr.parents)) or not build.is_dir() or not zephyr.is_dir():
        raise ValueError("invalid-build-or-checkout")
    source_path = zephyr / "scripts/dts/python-devicetree/src"
    if not (source_path / "devicetree/edtlib.py").is_file():
        return ("unavailable", "0" * 64, "unknown", "unknown",
                "unknown", "none", "unknown", "none", "selected-edtlib-missing")
    dts = build / "zephyr/zephyr.dts"
    cache = build / "CMakeCache.txt"
    if not dts.is_file() or not cache.is_file():
        return ("unavailable", "0" * 64, "unknown", "unknown",
                "unknown", "none", "unknown", "none", "build-artifacts-missing")
    source = read_local(dts, MAX_DTS)
    digest = hashlib.sha256(source).hexdigest()
    if not snapshot_checkout(cache, zephyr):
        return ("unavailable", digest, "unknown", "unknown", "unknown",
                "none", "unknown", "none", "checkout-mismatch")
    bindings = zephyr / "dts/bindings"
    if not bindings.is_dir():
        return ("unavailable", digest, "unknown", "unknown", "unknown",
                "none", "unknown", "none", "binding-root-missing")
    extra = application / "dts/bindings"
    roots = [str(bindings.resolve())]
    if extra.is_dir() and not extra.is_symlink() and extra.resolve() != bindings.resolve():
        roots.append(str(extra.resolve()))
    overlay_bytes = None
    if overlay is not None:
        path = Path(overlay).absolute()
        if not path.is_relative_to(application) or any(p.is_symlink() for p in (path, *path.parents)):
            raise ValueError("unsafe-overlay-path")
        overlay_bytes = read_local(path, MAX_OVERLAY)
        if not overlay_bytes.strip():
            raise ValueError("empty-overlay")
        if re.search(rb"(?m)^\s*#\s*(?:include|define|if|ifdef|ifndef|elif|endif)\b",
                     overlay_bytes) or b"/include/" in overlay_bytes or b"/incbin/" in overlay_bytes:
            return ("unavailable", digest, "unknown", "unknown", "unknown",
                    "none", "unknown", "unavailable", "preprocessing-required")
    sys.path.insert(0, str(source_path))
    try:
        from devicetree import edtlib
        with TemporaryDirectory(prefix="zew-studio-edt-") as temporary:
            plain = Path(temporary) / "built.dts"
            plain.write_bytes(source)
            built = edtlib.EDT(str(plain), roots)
            node_info = describe(built, node, prop)
            if overlay_bytes is not None:
                changed = Path(temporary) / "candidate.dts"
                changed.write_bytes(source + b"\n" + overlay_bytes + b"\n")
                try:
                    preview = edtlib.EDT(str(changed), roots)
                    node_info = describe(preview, node, prop)
                except Exception:
                    return ("rejected", digest, "unknown", "unknown",
                            "unknown", "none", "unknown", "rejected", "edtlib-rejected-overlay")
            node_state, binding, property_state, kind, status = node_info
            complete = node_state == "present" and binding == "matched" and (
                prop is None or property_state == "interpreted")
            state = "interpreted" if complete else "incomplete"
            return (state, digest, node_state, binding, property_state,
                    kind, status, "previewed" if overlay_bytes is not None else "none",
                    "matching-binding" if complete else "node-or-binding-incomplete")
    except Exception:
        return ("unavailable", digest, "unknown", "unknown", "unknown",
                "none", "unknown", "unavailable", "edtlib-unavailable")
    finally:
        if str(source_path) in sys.path:
            sys.path.remove(str(source_path))


def wire(record):
    print("ZEW-DTBIND-1")
    print("\t".join(base64.b64encode(value.encode("utf-8")).decode("ascii")
                    for value in record))


def main():
    parser = argparse.ArgumentParser()
    for flag in ("build", "zephyr", "application", "node"):
        parser.add_argument("--" + flag, required=True)
    parser.add_argument("--property")
    parser.add_argument("--overlay")
    args = parser.parse_args()
    try:
        result = evaluate(args.build, args.zephyr, args.application,
                          args.node, args.property, args.overlay)
    except (OSError, ValueError, UnicodeError):
        result = ("unavailable", "0" * 64, "unknown", "unknown",
                  "unknown", "none", "unknown", "unavailable", "invalid-input")
    wire(result)
    return 0 if result[0] == "interpreted" else 1


if __name__ == "__main__":
    sys.exit(main())
