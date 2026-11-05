# SPDX-License-Identifier: Apache-2.0
"""CS-13: image-scoped differences, never a diagnosis of regression causality."""
from __future__ import annotations

import hashlib
import importlib
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory

from .collector import InputError, _images, _regular, _read_observed, collect
from .compare import compare
from .dt_binding_preview import _build_checkout, _selected_edtlib

MAX_METADATA = 256 * 1024
MAX_PROPERTIES = 50000
PUBLIC_VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.+~-]{0,99}")


def _versions(directory: Path) -> dict:
    """Read known scalars through YAML's upstream safe loader, not a YAML imitation."""
    path = _regular(directory, "build_info.yml")
    if path is None:
        return {"state": "unknown", "reason": "missing-build-info", "values": {}}
    sha, _, data = _read_observed(path, MAX_METADATA, capture=True)
    result = {"state": "unknown", "values": {}, "provenance": {
        "artifact": "build_info.yml", "sha256": sha,
        "extraction": "YAML safe loader; known version scalars only"}}
    try:
        import yaml
        # Aliases can build recursive/exponential structures even with safe_load.
        if any(isinstance(event, yaml.events.AliasEvent) for event in yaml.parse(data)):
            raise ValueError("Aliases unsupported")
        class UniqueLoader(yaml.SafeLoader):
            pass
        def mapping(loader, node):
            pairs = loader.construct_pairs(node)
            keys = [key for key, _ in pairs]
            if any(not isinstance(key, str) for key in keys) or len(set(keys)) != len(keys):
                raise ValueError("Duplicate/non-string metadata key")
            return dict(pairs)
        UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
        metadata = yaml.load(data, Loader=UniqueLoader)
        if not isinstance(metadata, dict):
            raise ValueError("Expected mapping")
        for label, section, key in (("zephyr", "zephyr", "version"),
                                    ("toolchain", "toolchain", "version"),
                                    ("cmake", "cmake", "version")):
            group = metadata.get(section)
            raw = group.get(key) if isinstance(group, dict) else None
            if isinstance(raw, str) and PUBLIC_VERSION.fullmatch(raw):
                result["values"][label] = raw
        result["state"] = "observed" if result["values"] else "unknown"
        result["reason"] = "known-version-scalars" if result["values"] else "no-supported-version-scalars"
    except (ImportError, ValueError, RecursionError, UnicodeError):
        result["reason"] = "metadata-unavailable-or-invalid"
    except Exception:
        # Upstream YAML errors may embed private source literals.
        result["reason"] = "metadata-unavailable-or-invalid"
    return result


def _devicetree(directory: Path, checkout: Path | None) -> dict:
    if checkout is None:
        return {"state": "unknown", "reason": "explicit-selected-checkout-required"}
    artifact = _regular(directory, "zephyr/zephyr.dts")
    if artifact is None:
        return {"state": "unknown", "reason": "missing-generated-dts"}
    sha, _, snapshot = _read_observed(artifact, 4 * 1024 * 1024, capture=True)
    if not _build_checkout(directory, checkout):
        return {"state": "unknown", "reason": "build-checkout-unverified"}
    if b"/include/" in snapshot or b"/incbin/" in snapshot:
        # Generated DTS is self-contained; never read arbitrary external files.
        return {"state": "unknown", "reason": "non-self-contained-generated-dts"}
    try:
        search = str(_selected_edtlib(checkout))
    except InputError:
        return {"state": "unknown", "reason": "selected-upstream-dtlib-unavailable"}
    modules = {key: value for key, value in sys.modules.items()
               if key == "devicetree" or key.startswith("devicetree.")}
    old_path = sys.path[:]
    for key in modules:
        sys.modules.pop(key)
    try:
        sys.path.insert(0, search)
        dtlib = importlib.import_module("devicetree.dtlib")
        properties = {}
        with TemporaryDirectory(prefix="zew-studio-compare-") as temp:
            path = Path(temp) / "snapshot.dts"
            path.write_bytes(snapshot)
            tree = dtlib.DT(str(path))
            for node in tree.node_iter():
                for name, prop in sorted(node.props.items()):
                    if len(properties) >= MAX_PROPERTIES:
                        return {"state": "unknown", "reason": "property-limit-exceeded"}
                    # Raw DT property bytes are the upstream parser's resolved
                    # semantic values. Keep private strings out of the report.
                    key = node.path + ":" + name
                    properties[key] = {"type": prop.type.name,
                                       "value_sha256": hashlib.sha256(prop.value).hexdigest()}
        after, _, _ = _read_observed(artifact, 4 * 1024 * 1024)
        if after != sha:
            return {"state": "unknown", "reason": "generated-dts-changed"}
        return {"state": "observed", "properties": properties,
                "provenance": {"artifact": "zephyr/zephyr.dts", "sha256": sha,
                               "extraction": "selected-upstream-dtlib resolved property bytes"}}
    except Exception:
        return {"state": "unknown", "reason": "upstream-dtlib-rejected-or-unavailable"}
    finally:
        sys.path[:] = old_path
        for key in list(sys.modules):
            if key == "devicetree" or key.startswith("devicetree."):
                sys.modules.pop(key)
        sys.modules.update(modules)


def inspect(left: Path, right: Path, left_images: list[str] | None = None,
            right_images: list[str] | None = None, left_zephyr: Path | None = None,
            right_zephyr: Path | None = None) -> dict:
    """Compare explicit image identities. Optional checkouts enable DT semantics.

    No build, application or target is modified. Version metadata describes its
    producer's record, not a verified current host installation.
    """
    before, after = collect(left, left_images), collect(right, right_images)
    report = compare(before, after)
    report["schema"] = "zew.studio-comparison/1"
    directories = [dict(_images(Path(root).resolve(strict=True), images or []))
                   for root, images in ((left, left_images), (right, right_images))]
    image_reports = []
    for name in sorted(directories[0].keys() | directories[1].keys()):
        row = {"image": name, "state": "comparable"}
        if name not in directories[0] or name not in directories[1]:
            row["state"] = "added" if name not in directories[0] else "removed"
            row["semantic_differences"] = []
            image_reports.append(row)
            continue
        a, b = directories[0][name], directories[1][name]
        row["versions"] = {"left": _versions(a), "right": _versions(b)}
        row["identity"] = {}
        for side, graph in (("left", before), ("right", after)):
            row["identity"][side] = {n["data"]["name"]: n["data"]["observed_value"]
                for n in graph["nodes"] if n["type"] == "kconfig_observation"
                and n["id"].startswith("kconfig:" + name + ":")
                and n["data"]["name"] in ("CONFIG_BOARD_TARGET", "CONFIG_SOC", "CONFIG_BOARD")}
        dt_a, dt_b = _devicetree(a, left_zephyr), _devicetree(b, right_zephyr)
        row["devicetree"] = {"left": {k: v for k, v in dt_a.items() if k != "properties"},
                             "right": {k: v for k, v in dt_b.items() if k != "properties"}}
        changes = []
        if dt_a["state"] == dt_b["state"] == "observed":
            p, q = dt_a["properties"], dt_b["properties"]
            for key in sorted(p.keys() | q.keys()):
                if p.get(key) != q.get(key):
                    changes.append({"property": key, "left": p.get(key), "right": q.get(key),
                                    "confidence": "derived", "cause": "unknown"})
        else:
            row["state"] = "partial"
        row["semantic_differences"] = changes
        image_reports.append(row)
    report["images"] = image_reports
    report["causality"] = "unknown: differences are observations, not regression causes"
    report["limitations"].extend([
        "Devicetree semantic comparison requires each side's explicitly selected checkout.",
        "Property-byte hashes preserve private values; bindings and electrical meanings are not compared.",
        "No runtime regression, initialized driver, flashed device or complete source freshness is established.",
    ])
    return report
