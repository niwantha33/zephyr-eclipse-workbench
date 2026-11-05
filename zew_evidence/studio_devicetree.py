# SPDX-License-Identifier: Apache-2.0
"""Selected-build DT origin evidence from upstream dtlib and CPP line markers.

No source regex guesses property scope/precedence. The selected checkout's
DT parser resolves overrides, references and deletions in zephyr.dts.pre; a
property origin is accepted only when its interpreted bytes match zephyr.dts.
"""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import importlib
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory

from .collector import InputError, _images, _regular
from .dt_binding_preview import (_build_checkout, _checked_file, MAX_DTS_BYTES,
                                 _NODE_PATH, _PROPERTY)

MAX_SOURCES = 4096
_LINE = re.compile(r'^\s*#(?:line\s+|\s+)([0-9]+)\s+"([^"\r\n]+)"(?:\s+[0-9 ]+)?\s*$')


@contextmanager
def _library(zephyr: Path):
    search = zephyr / "scripts/dts/python-devicetree/src"
    if not (search / "devicetree/dtlib.py").is_file():
        raise InputError("Selected checkout has no upstream dtlib")
    saved = {key: value for key, value in sys.modules.items()
             if key == "devicetree" or key.startswith("devicetree.")}
    previous = sys.path[:]
    try:
        for key in saved:
            del sys.modules[key]
        sys.path.insert(0, str(search))
        yield importlib.import_module("devicetree.dtlib")
    finally:
        for key in list(sys.modules):
            if key == "devicetree" or key.startswith("devicetree."):
                del sys.modules[key]
        sys.modules.update(saved)
        sys.path[:] = previous


def _safe_roots(roots: list[Path]) -> list[Path]:
    if not roots or len(roots) > 16:
        raise InputError("Supply one to sixteen explicit source roots")
    result = []
    for raw in roots:
        root = Path(raw).absolute()
        if any(p.is_symlink() for p in (root, *root.parents)) or not root.is_dir():
            raise InputError("Source root must be an existing non-symlink directory")
        result.append(root.resolve(strict=True))
    return result


def _source(filename: str, build: Path, roots: list[Path]) -> dict:
    # Relative CPP paths are relative to the build's working directory. Never
    # search roots by basename or treat a guessed path as an origin.
    path = Path(filename)
    path = path if path.is_absolute() else build / path
    path = path.absolute()
    if any(part.is_symlink() for part in (path, *path.parents)):
        return {"state": "unsafe-source", "confidence": "unknown"}
    path = path.resolve(strict=False)
    for index, root in enumerate(roots):
        if path.is_relative_to(root):
            if not path.is_file():
                return {"state": "source-missing", "confidence": "unknown"}
            if path.suffix.lower() not in (".dts", ".dtsi", ".overlay", ".h"):
                return {"state": "unsupported-source-kind", "confidence": "unknown"}
            _, digest = _checked_file(path, MAX_DTS_BYTES)
            return {"state": "source-observed", "confidence": "derived",
                    "root_index": index, "path": path.relative_to(root).as_posix(),
                    "sha256": digest, "kind": "overlay" if path.suffix == ".overlay" else "source",
                    "freshness": "current-bytes-only; source-at-configure hash unavailable"}
    return {"state": "source-outside-selected-roots", "confidence": "unknown"}


def inspect(build: Path, zephyr: Path, node: str, prop: str,
            source_roots: list[Path], images: list[str] | None = None,
            include_values: bool = False) -> dict:
    if not _NODE_PATH.fullmatch(node) or not _PROPERTY.fullmatch(prop):
        raise InputError("Select an exact generated node path and property")
    roots = _safe_roots(source_roots)
    zephyr = Path(zephyr).absolute()
    if any(p.is_symlink() for p in (zephyr, *zephyr.parents)):
        raise InputError("Symlinked checkout rejected")
    rows = []
    for image, directory in _images(Path(build).absolute(), images or []):
        row = {"image": image, "state": "unavailable", "confidence": "unknown",
               "origin": None, "final_property": None, "active_sources": [],
               "provenance": {}, "reason": "selected-checkout-unverified"}
        rows.append(row)
        if not _build_checkout(directory, zephyr):
            continue
        generated = _regular(directory, "zephyr/zephyr.dts")
        pre = _regular(directory, "zephyr/zephyr.dts.pre")
        if generated is None or pre is None:
            row["reason"] = "generated-dts-and-preprocessed-input-required"
            continue
        generated_bytes, generated_sha = _checked_file(generated, MAX_DTS_BYTES)
        pre_bytes, pre_sha = _checked_file(pre, MAX_DTS_BYTES)
        row["provenance"] = {
            "generated": {"artifact": "zephyr/zephyr.dts", "sha256": generated_sha},
            "preprocessed": {"artifact": "zephyr/zephyr.dts.pre", "sha256": pre_sha},
            "method": "selected-checkout upstream dtlib; CPP source line markers",
        }
        # Fully preprocessed inputs need no file IO during parsing. Prevent
        # dtlib opening arbitrary include/incbin paths from imported artifacts.
        if any(token in generated_bytes or token in pre_bytes
               for token in (b"/include/", b"/incbin/")):
            row["reason"] = "external-parser-reads-not-permitted"
            continue
        markers = sorted({match.group(2) for line in pre_bytes.decode("utf-8").splitlines()
                          if (match := _LINE.fullmatch(line))})
        if len(markers) > MAX_SOURCES:
            row["reason"] = "source-marker-limit-exceeded"
            continue
        row["active_sources"] = [_source(name, directory, roots) for name in markers
                                  if name.endswith((".dts", ".dtsi", ".overlay"))]
        with _library(zephyr) as library, TemporaryDirectory(prefix="zew-dt-origin-") as temp:
            final_path, pre_path = Path(temp) / "final.dts", Path(temp) / "pre.dts"
            final_path.write_bytes(generated_bytes)
            pre_path.write_bytes(pre_bytes)
            try:
                final_tree = library.DT(str(final_path))
                pre_tree = library.DT(str(pre_path))
            except Exception:
                row["reason"] = "upstream-dtlib-rejected-snapshot"
                continue
            try:
                final_property = final_tree.get_node(node).props.get(prop)
                pre_property = pre_tree.get_node(node).props.get(prop)
            except Exception:
                row["reason"] = "selected-node-not-present"
                continue
            if final_property is None:
                row.update(state="property-not-present", confidence="confirmed",
                           reason="no-final-property-origin-claimed")
                continue
            value = bytes(final_property.value)
            row["final_property"] = {
                "state": "observed", "type": final_property.type.name,
                "value_sha256": hashlib.sha256(value).hexdigest(), "bytes": len(value)}
            if include_values:
                row["final_property"]["value_hex"] = value.hex()
            if pre_property is None or bytes(pre_property.value) != value:
                row["reason"] = "preprocessed-and-final-property-differ"
                continue
            filename, line = getattr(pre_property, "filename", None), getattr(pre_property, "lineno", None)
            if filename not in markers or not isinstance(line, int) or line < 1:
                row["reason"] = "upstream-source-location-unavailable"
                continue
            origin = _source(filename, directory, roots)
            origin["line"] = line
            row["origin"] = origin
            if origin["state"] == "source-observed":
                row.update(state="property-origin-observed", confidence="derived",
                           reason="upstream-final-assignment-location-and-equal-property-bytes")
            else:
                row["reason"] = "source-origin-not-verifiable"
        if (_checked_file(generated, MAX_DTS_BYTES)[1] != generated_sha
                or _checked_file(pre, MAX_DTS_BYTES)[1] != pre_sha):
            raise InputError("Devicetree artifacts changed during inspection")
    return {"schema": "zew.studio-devicetree/1", "node": node, "property": prop,
            "status": "observed" if rows and all(r["state"] == "property-origin-observed" for r in rows)
                       else "partial-or-unavailable", "images": rows,
            "limitations": ["CPP source participation does not prove any property's origin by itself.",
                            "Origins are upstream parser locations, not an assignment history.",
                            "Matching final and preprocessed bytes do not prove source freshness or a successful build.",
                            "Source hashes identify current files; configure-time source hashes require a separate receipt.",
                            "No driver, pin availability, runtime or hardware state is inferred."]}
