# SPDX-License-Identifier: Apache-2.0
"""Read-only selected-peripheral evidence, not a pin/driver readiness inference.

Every observation is scoped to an explicitly declared build image and the
selected Zephyr checkout. The real edtlib performs DT binding interpretation;
Zephyr's generated .config supplies independent Kconfig observations. A
CONFIG_ symbol supplied by the caller is NEVER inferred to control this node.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import re

from .collector import (InputError, SCHEMA_VERSION, MAX_CONFIG_BYTES, PRIVATE,
                        _images, _kconfig, _regular)
from .dt_binding_preview import _checked_file, inspect as inspect_dt

_SYMBOL = re.compile(r"CONFIG_[A-Z0-9_]{1,160}")
_CONFIDENCE = "confirmed"
_UNKNOWN = "unknown"


def inspect(build: Path, zephyr: Path, node: str, prop: str | None = None,
            symbol: str | None = None, images: list[str] | None = None,
            bindings: list[Path] | None = None) -> dict:
    """Deterministic build-snapshot evidence, without values/paths/causal guesses."""
    if symbol is not None and (not _SYMBOL.fullmatch(symbol) or PRIVATE.search(symbol)):
        raise InputError("--symbol must be a non-sensitive CONFIG_ symbol")
    # Reuse upstream EDT interpretation and selected-checkout/hash validation.
    dt = inspect_dt(build, zephyr, node, prop, images=images, bindings=bindings)
    directories = dict(_images(Path(build).absolute(), images or []))
    rows = []
    for item in dt["images"]:
        name = item["image"]
        directory = directories[name]
        configured = _regular(directory, "zephyr/.config")
        if configured is None:
            config_sha = None
            effective = "no-generated-config" if symbol is not None else "not-requested"
        else:
            snapshot, config_sha = _checked_file(configured, MAX_CONFIG_BYTES)
            observed = _kconfig(snapshot)
            effective = ("not-requested" if symbol is None else
                         "enabled" if observed.get(symbol) == "y" else
                         "disabled" if observed.get(symbol) == "n" else
                         "module" if observed.get(symbol) == "m" else
                         "not-observed-or-redacted")
        built = item.get("built_node", {})
        path_status = built.get("state", "unknown")
        binding = built.get("binding", "unknown")
        device_status = built.get("status", "unknown")
        property_status = built.get("property", "unknown")
        sha = item.get("generated_dts_sha256")
        checks = [
            {"id": "configured-dts", "state": _CONFIDENCE
             if item["state"] in ("built-tree-evaluated", "built-tree-incomplete")
             else _UNKNOWN,
             "observation": item["state"],
             "evidence": {"artifact": "zephyr/zephyr.dts", "sha256": sha}},
            {"id": "selected-node", "state": _CONFIDENCE if path_status in
             ("node-found", "node-not-present") and sha else _UNKNOWN,
             "observation": path_status},
            {"id": "matched-binding", "state": _CONFIDENCE
             if path_status == "node-found" and binding == "matched" else _UNKNOWN,
             "observation": binding},
            {"id": "configured-node-status", "state": _CONFIDENCE
             if path_status == "node-found" and device_status in
             ("okay", "disabled", "reserved", "fail", "fail-sss") else _UNKNOWN,
             "observation": device_status},
            {"id": "selected-binding-property", "state": _CONFIDENCE
             if prop is not None and property_status == "interpreted-by-binding"
             else _UNKNOWN,
             "observation": property_status},
            {"id": "selected-kconfig-symbol", "state": _CONFIDENCE
             if symbol is not None and effective in ("enabled", "disabled", "module")
             and item["state"] in ("built-tree-evaluated", "built-tree-incomplete")
             else _UNKNOWN,
             "observation": effective,
             "evidence": {"artifact": "zephyr/.config", "sha256": config_sha}},
        ]
        # Fixed, auditable follow-up steps; not diagnoses of wiring or causality.
        actions = []
        if item["state"] not in ("built-tree-evaluated", "built-tree-incomplete"):
            actions.append("verify-exact-zephyr-checkout-and-generated-dts")
        elif path_status != "node-found":
            actions.append("locate-node-in-generated-devicetree")
        else:
            if binding != "matched":
                actions.append("supply-actual-module-binding-roots")
            if device_status == "disabled":
                actions.append("review-source-overlay-and-reconfigure")
            if prop is not None and property_status != "interpreted-by-binding":
                actions.append("check-selected-property-against-upstream-binding")
        if symbol is not None and effective not in ("enabled", "disabled", "module"):
            actions.append("inspect-generated-kconfig-with-kconfiglib")
        if configured is None:
            actions.append("generate-selected-build-config")
        # No implication that symbol enables this node, no inferred pin.
        state = ("selected-observations-present"
                 if path_status == "node-found" and binding == "matched"
                 and device_status != "unknown"
                 and (prop is None or property_status == "interpreted-by-binding")
                 and (symbol is None or effective in ("enabled", "disabled", "module"))
                 and item["state"] in ("built-tree-evaluated", "built-tree-incomplete")
                 else "incomplete-or-unavailable")
        rows.append({
            "image": name, "state": state,
            "generated_dts_sha256": sha,
            "generated_config_sha256": config_sha,
            "node": {"state": path_status, "binding": binding,
                     "configured_status": device_status,
                     "property": property_status,
                     "property_type": built.get("property_type", "unknown"),
                     "property_deprecated": built.get("deprecated")},
            "kconfig": {"requested": symbol is not None, "effective": effective,
                        "causal_link_to_node": "unverified"},
            "checks": checks, "next_actions": sorted(set(actions)),
        })
    result = {
        "schema_version": SCHEMA_VERSION,
        "status": ("selected-observations-present"
                   if rows and all(row["state"] == "selected-observations-present" for row in rows)
                   else "incomplete-or-unavailable"),
        "node_path_sha256": dt["node_path_sha256"],
        "property_requested": prop is not None,
        "symbol_sha256": hashlib.sha256(symbol.encode("utf-8")).hexdigest() if symbol else None,
        "images": rows,
        "limitations": [
            "Selected CONFIG_ observations are independent; there is no inferred link to this node.",
            "A matched binding or status okay does not prove a driver is compiled, a pin is free, or hardware works.",
            "Node status is the selected generated tree's status; parent/bus runtime readiness is not established.",
            "Properties, bindings and module roots are interpreted only by the selected Zephyr edtlib.",
            "No package pad, pinmux electrical capability, physical wiring or flashed target was inspected.",
            "Generated outputs may be stale relative to unobserved source inputs; reconfigure before trusting changes.",
            "All reads are offline and read-only; no user values, paths or source contents are exported.",
        ],
    }
    return result
