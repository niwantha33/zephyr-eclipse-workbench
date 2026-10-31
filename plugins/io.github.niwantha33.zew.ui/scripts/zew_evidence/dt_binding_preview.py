# SPDX-License-Identifier: Apache-2.0
"""Read-only, binding-aware preview of a limited Devicetree overlay.

Uses the explicitly selected Zephyr checkout's edtlib to inspect a generated
build snapshot. Never unpickle edt.pickle, run a compiler, or write to project.
This is NOT proof an overlay is included by CMake or hardware is available.
"""
from __future__ import annotations

import hashlib
import importlib
import os
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory

from .collector import InputError, SCHEMA_VERSION, _digest, _images, _regular

MAX_DTS_BYTES = 4 * 1024 * 1024
MAX_OVERLAY_BYTES = 64 * 1024
MAX_CACHE_BYTES = 8 * 1024 * 1024
_NODE_PATH = re.compile(r"/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
_PROPERTY = re.compile(r"[a-zA-Z][a-zA-Z0-9,._+?-]{0,99}")
_CACHE_BASE = re.compile(r"^ZEPHYR_BASE:[A-Z_]+=(.*)$")


def _checked_file(path: Path, limit: int) -> tuple[bytes, str]:
    path = Path(path).absolute()
    if any(item.is_symlink() for item in (path, *path.parents)):
        raise InputError("Symlinked input paths are not allowed")
    if not path.is_file():
        raise InputError("Selected input is not an existing regular file")
    before = path.stat()
    if before.st_size > limit:
        raise InputError("Input exceeds the allowed size")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise InputError("Input changed during reading")
    if len(data) > limit:
        raise InputError("Input exceeds the allowed size")
    if b"\x00" in data:
        raise InputError("NUL byte in input")
    try:
        data.decode("utf-8", errors="strict")
    except UnicodeError as exc:
        raise InputError("Input must be UTF-8") from exc
    return data, hashlib.sha256(data).hexdigest()


def _build_checkout(build: Path, zephyr: Path) -> bool:
    """Require exact configured ZEPHYR_BASE, not an arbitrary external checkout."""
    cache = _regular(build, "CMakeCache.txt")
    if cache is None:
        return False
    data, _ = _checked_file(cache, MAX_CACHE_BYTES)
    bases = [_line.group(1) for raw in data.decode("utf-8").splitlines()
             if (_line := _CACHE_BASE.fullmatch(raw)) is not None]
    if len(bases) != 1:
        return False
    try:
        return Path(bases[0]).resolve(strict=True) == zephyr.resolve(strict=True)
    except (OSError, ValueError):
        return False


def _paths(zephyr: Path, extras: list[Path]) -> list[str]:
    standard = zephyr / "dts" / "bindings"
    roots = [standard, *map(Path, extras)]
    if len(roots) > 10:
        raise InputError("At most ten binding roots are allowed")
    resolved = []
    for root in roots:
        root = root.absolute()
        if any(item.is_symlink() for item in (root, *root.parents)) or not root.is_dir():
            raise InputError("Binding root must be an existing non-symlink directory")
        canonical = str(root.resolve(strict=True))
        if canonical not in resolved:
            resolved.append(canonical)
    return resolved


def _selected_edtlib(zephyr: Path):
    """Import only from selected checkout, restoring module state after inspection."""
    search_path = zephyr / "scripts" / "dts" / "python-devicetree" / "src"
    if not (search_path / "devicetree" / "edtlib.py").is_file():
        raise InputError("Selected Zephyr checkout has no supported edtlib")
    return search_path


def _inspect_node(edt, node_path: str, property_name: str | None) -> dict:
    try:
        node = edt.get_node(node_path)
    except Exception:
        return {"state": "node-not-present", "binding": "unknown",
                "property": "unknown"}
    bound = node.binding_path is not None
    result = {"state": "node-found", "binding": "matched" if bound else "unmatched",
              "status": node.status if node.status in
              ("okay", "disabled", "reserved", "fail", "fail-sss") else "other"}
    if property_name is not None:
        prop = node.props.get(property_name)
        if not bound:
            result["property"] = "unknown-without-binding"
        elif prop is None:
            result["property"] = "not-interpreted-by-binding"
        else:
            result["property"] = "interpreted-by-binding"
            result["property_type"] = prop.type if prop.type in (
                "boolean", "int", "array", "uint8-array", "string",
                "string-array", "phandle", "phandles", "phandle-array", "path"
            ) else "other"
            result["deprecated"] = bool(prop.spec.deprecated)
    else:
        result["property"] = "not-requested"
    return result


def inspect(build: Path, zephyr: Path, node: str, prop: str | None = None,
            overlay: Path | None = None, images: list[str] | None = None,
            bindings: list[Path] | None = None) -> dict:
    """Return bounded JSON without exporting DTS contents, values or host paths."""
    if not _NODE_PATH.fullmatch(node) or len(node) > 240:
        raise InputError("--node must be an absolute, bounded DTS node path")
    if prop is not None and not _PROPERTY.fullmatch(prop):
        raise InputError("--property has invalid characters")
    root = Path(build).absolute()
    if not root.is_dir() or any(path.is_symlink() for path in (root, *root.parents)):
        raise InputError("--build must be a non-symlink directory")
    zephyr = Path(zephyr).absolute()
    if not zephyr.is_dir() or any(path.is_symlink() for path in (zephyr, *zephyr.parents)):
        raise InputError("--zephyr must be an existing non-symlink checkout")
    search = _selected_edtlib(zephyr)
    binding_roots = _paths(zephyr, bindings or [])
    overlay_data = b""
    overlay_hash = None
    if overlay is not None:
        overlay_data, overlay_hash = _checked_file(Path(overlay), MAX_OVERLAY_BYTES)
        if not overlay_data.strip():
            raise InputError("Empty overlay has no effect to validate")
        # C preprocessor macros and includes require the genuine Zephyr build
        # pipeline; a standalone EDT preview must not guess their meaning.
        if re.search(rb"(?m)^\s*#\s*(?:include|define|if|ifdef|ifndef|elif|endif)\b",
                     overlay_data) or b"/include/" in overlay_data or b"/incbin/" in overlay_data:
            raise InputError("Overlay needs preprocessing; use the actual Zephyr build")
    image_results = []
    image_dirs = _images(root, images or [])
    for image, directory in image_dirs:
        artifact = _regular(directory, "zephyr/zephyr.dts")
        if artifact is None:
            image_results.append({"image": image, "state": "missing-generated-dts"})
            continue
        snapshot, sha = _checked_file(artifact, MAX_DTS_BYTES)
        if not _build_checkout(directory, zephyr):
            image_results.append({"image": image, "state": "build-checkout-unverified",
                                  "generated_dts_sha256": sha})
            continue
        # Loading edt.pickle here would execute arbitrary pickle payloads:
        # always parse bounded plain DTS with upstream edtlib instead.
        existing_modules = {name: module for name, module in sys.modules.items()
                            if name == "devicetree" or name.startswith("devicetree.")}
        for name in existing_modules:
            sys.modules.pop(name, None)
        sys.path.insert(0, str(search))
        try:
            edtlib = importlib.import_module("devicetree.edtlib")
            with TemporaryDirectory(prefix="zew-dt-preview-") as temp:
                dts = Path(temp) / "generated.dts"
                dts.write_bytes(snapshot)
                base_edt = edtlib.EDT(str(dts), binding_roots)
                base_info = _inspect_node(base_edt, node, prop)
                row = {"image": image, "generated_dts_sha256": sha,
                       "state": "built-tree-evaluated", "built_node": base_info}
                if overlay is not None:
                    # The experiment is in a new temp directory, never in the
                    # selected build or user's application.
                    proposal = Path(temp) / "preview.dts"
                    proposal.write_bytes(snapshot + b"\n" + overlay_data + b"\n")
                    try:
                        proposed_edt = edtlib.EDT(str(proposal), binding_roots)
                        proposed = _inspect_node(proposed_edt, node, prop)
                        row["proposed_node"] = proposed
                        if (proposed["state"] == "node-found"
                                and proposed["binding"] == "matched"
                                and (prop is None or proposed["property"] == "interpreted-by-binding")):
                            row["state"] = "binding-validated-preview"
                        else:
                            row["state"] = "preview-incomplete"
                    except Exception:
                        # EDTError may contain user's source paths / literal
                        # property values. Never export its exception string.
                        row["state"] = "edtlib-rejected-preview"
                elif (base_info["state"] != "node-found"
                      or base_info["binding"] != "matched"
                      or (prop is not None and base_info["property"] != "interpreted-by-binding")):
                    row["state"] = "built-tree-incomplete"
                image_results.append(row)
        except Exception:
            image_results.append({"image": image, "state": "edtlib-unavailable-or-rejected",
                                  "generated_dts_sha256": sha})
        finally:
            sys.path.remove(str(search))
            for name in list(sys.modules):
                if name == "devicetree" or name.startswith("devicetree."):
                    sys.modules.pop(name, None)
            sys.modules.update(existing_modules)
        after, _ = _digest(artifact)
        if sha != after:
            image_results[-1] = {"image": image, "state": "generated-dts-changed"}
    accepted = bool(image_results) and all(r["state"] in
                ("binding-validated-preview", "built-tree-evaluated") for r in image_results)
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "interpreted-preview" if accepted else "partial-or-unavailable",
        "node_path_sha256": hashlib.sha256(node.encode("utf-8")).hexdigest(),
        "property_requested": prop is not None,
        "overlay": {"provided": overlay is not None, "sha256": overlay_hash},
        "binding_roots": len(binding_roots),
        "images": image_results,
        "limitations": [
            "Only the chosen generated DTS and explicit binding roots were evaluated.",
            "An overlay preview does not prove it is included by the configured CMake build.",
            "Preprocessor directives, overlay ordering and module binding completeness require a real Zephyr build.",
            "EDT syntax and binding interpretation are not electrical pin or runtime device validation.",
            "No firmware, generated source, build directory or physical target was modified.",
        ],
    }
