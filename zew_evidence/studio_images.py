# SPDX-License-Identifier: Apache-2.0
"""CS-11: bounded upstream sysbuild domains.yaml discovery.

Image identity comes from generated sysbuild metadata. Never scan arbitrary
folders or combine different images' Kconfig symbol spaces.
"""
from __future__ import annotations

from pathlib import Path
import re

from .collector import InputError, _read_observed, _regular, collect

MAX_DOMAINS_BYTES = 1024 * 1024
MAX_IMAGES = 128
_NAME = re.compile(r"[A-Za-z0-9_.-]{1,60}")


def _load(snapshot):
    try:
        import yaml
        from yaml.tokens import AliasToken, AnchorToken
    except ImportError as error:
        raise InputError("PyYAML is required to read upstream sysbuild metadata") from error
    try:
        text = snapshot.decode("utf-8", errors="strict")
        if any(isinstance(token, (AliasToken, AnchorToken)) for token in yaml.scan(text)):
            raise InputError("Aliases and anchors are not accepted in sysbuild metadata")

        class UniqueLoader(yaml.SafeLoader):
            pass

        def mapping(loader, entry):
            pairs = loader.construct_pairs(entry)
            result = {}
            for key, value in pairs:
                if not isinstance(key, str) or key in result:
                    raise InputError("Duplicate or non-string sysbuild metadata key")
                result[key] = value
            return result

        UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
        return yaml.load(text, Loader=UniqueLoader)
    except InputError:
        raise
    except (UnicodeError, yaml.YAMLError, RecursionError, ValueError, TypeError) as error:
        raise InputError("Invalid upstream sysbuild metadata") from error


def _directory(root, raw):
    if not isinstance(raw, str) or not raw or len(raw) > 4096 or "\x00" in raw:
        raise InputError("Invalid sysbuild image location")
    raw_path = Path(raw)
    # Both relative generated paths and exact absolute upstream paths are valid.
    # Absolute paths outside the selected root require explicit user selection.
    if ".." in raw_path.parts:
        raise InputError("Parent traversal in sysbuild image location")
    location = raw_path if raw_path.is_absolute() else root / raw_path
    if any(p.is_symlink() for p in (location, *location.parents)):
        raise InputError("Symlinked sysbuild image directory")
    try:
        relative = location.resolve(strict=True).relative_to(root)
    except (OSError, ValueError) as error:
        raise InputError("Sysbuild image must exist within the selected build root") from error
    if not location.is_dir():
        raise InputError("Sysbuild image is not a directory")
    return relative.as_posix() or "."


def inspect(build: Path):
    root = Path(build).absolute()
    if not root.is_dir() or any(p.is_symlink() for p in (root, *root.parents)):
        raise InputError("Build root must be an existing non-symlink directory")
    root = root.resolve(strict=True)
    metadata = _regular(root, "domains.yaml")
    if metadata is None:
        return {"schema": "zew.studio-images/1", "status": "metadata-unavailable",
                "reason": "No generated domains.yaml; image topology is not guessed",
                "images": [], "default_image": None}
    checksum, _, snapshot = _read_observed(metadata, MAX_DOMAINS_BYTES, capture=True)
    data = _load(snapshot)
    if not isinstance(data, dict) or not isinstance(data.get("domains"), list):
        raise InputError("sysbuild domains.yaml requires a domains list")
    if not data["domains"] or len(data["domains"]) > MAX_IMAGES:
        raise InputError("sysbuild image count outside allowed bounds")
    names, locations, rows = set(), set(), []
    for domain in data["domains"]:
        if not isinstance(domain, dict) or not isinstance(domain.get("name"), str):
            raise InputError("Invalid sysbuild domain")
        name = domain["name"]
        if not _NAME.fullmatch(name) or name in (".", "..") or name in names:
            raise InputError("Duplicate or invalid sysbuild domain name")
        relative = _directory(root, domain.get("build_dir"))
        if relative in locations:
            raise InputError("Multiple image names point to the same build directory")
        names.add(name)
        locations.add(relative)
        directory = root / relative
        observed = {kind: _regular(directory, path) is not None for kind, path in
                    (("kconfig", "zephyr/.config"), ("devicetree", "zephyr/zephyr.dts"),
                     ("elf", "zephyr/zephyr.elf"))}
        rows.append({"image": name, "relative_build": relative, "observed_artifacts": observed,
                     "state": "generated-artifacts-present" if all(observed.values()) else "partial-or-unconfigured"})
    default = data.get("default")
    if not isinstance(default, str) or default not in names:
        raise InputError("Default sysbuild image is absent or ambiguous")
    if "build_dir" in data and _directory(root, data["build_dir"]) != ".":
        raise InputError("Sysbuild metadata names a different root build directory")
    order = data.get("flash_order", [])
    if not isinstance(order, list) or any(not isinstance(n, str) or n not in names for n in order) or len(set(order)) != len(order):
        raise InputError("Invalid sysbuild flash order")
    return {"schema": "zew.studio-images/1", "status": "discovered",
            "default_image": default, "images": sorted(rows, key=lambda r: r["image"]),
            "flash_order": order,
            "provenance": {"artifact": "domains.yaml", "sha256": checksum,
                           "method": "upstream sysbuild generated domains metadata"},
            "limitations": ["Artifact existence does not prove successful compilation or flashing.",
                            "Image configuration spaces remain independent."]}


def declarations(report):
    if report.get("status") != "discovered":
        raise InputError("No unambiguous generated image topology")
    return [row["image"] + "=" + row["relative_build"] for row in report["images"]]


def collect_images(build: Path):
    """Discover actual image roots, then reuse the existing per-image collector."""
    report = inspect(build)
    if report["status"] != "discovered":
        return {"topology": report, "graph": None}
    graph = collect(build, declarations(report))
    return {"topology": report, "graph": graph}
