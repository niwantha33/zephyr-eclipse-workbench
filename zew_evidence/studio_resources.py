# SPDX-License-Identifier: Apache-2.0
"""CS-07: conservative resource-reference evidence from selected upstream edtlib.

A repeated GPIO line is a potential shared-resource issue, not proof of an
illegal electrical assignment. Vendor-specific pinctrl encodings remain opaque.
"""
from __future__ import annotations

import importlib
from pathlib import Path
import sys
from tempfile import TemporaryDirectory

from .collector import InputError, _digest, _images, _regular
from .dt_binding_preview import (_build_checkout, _checked_file, _paths,
                                 _selected_edtlib, MAX_DTS_BYTES)

MAX_NODES = 20000
MAX_REFERENCES = 50000


def _enabled(node):
    seen = set()
    while node is not None:
        if id(node) in seen:
            raise InputError("Cycle in decoded Devicetree parent links")
        seen.add(id(node))
        if node.status != "okay":
            return False
        node = node.parent
    return True


def analyze_edt(edt):
    """Consume edtlib's typed objects, never decode raw vendor pinmux cells."""
    owners, pinctrl = {}, []
    unknown = []
    references = 0
    if len(edt.nodes) > MAX_NODES:
        raise InputError("Decoded Devicetree exceeds node limit")
    for node in sorted(edt.nodes, key=lambda item: item.path):
        if not _enabled(node):
            continue
        for name, prop in sorted(node.props.items()):
            if prop.type != "phandle-array" or not (name == "gpios" or name.endswith("-gpios")):
                continue
            for index, spec in enumerate(prop.val or []):
                references += 1
                if references > MAX_REFERENCES:
                    raise InputError("Decoded Devicetree exceeds reference limit")
                # edtlib obtains named cells from the actual controller binding.
                if spec is None or "pin" not in spec.data or not isinstance(spec.data["pin"], int):
                    unknown.append({"node": node.path, "property": name,
                                    "index": index, "reason": "binding-has-no-integer-pin-cell"})
                    continue
                if not _enabled(spec.controller):
                    unknown.append({"node": node.path, "property": name,
                                    "index": index, "reason": "gpio-controller-disabled"})
                    continue
                key = (spec.controller.path, spec.data["pin"])
                owners.setdefault(key, []).append({"node": node.path,
                    "property": name, "index": index})
        for state in node.pinctrls:
            pinctrl.append({"node": node.path, "state": state.name,
                            "configuration_nodes": sorted(item.path for item in state.conf_nodes),
                            "pad_ownership": "unknown-vendor-encoding-not-decoded"})
    shared = []
    for (controller, pin), users in sorted(owners.items()):
        if len({u["node"] for u in users}) > 1:
            shared.append({"rule_id": "CS07-SHARED-GPIO-REFERENCE", "severity": "warning",
                           "confidence": "possible", "controller": controller, "pin": pin,
                           "owners": users, "interpretation": "Same decoded GPIO line is referenced by multiple enabled nodes; sharing may be intentional",
                           "next_action": "Review consumer bindings, direction and board wiring before changing either owner"})
    return {"gpio_lines": [{"controller": controller, "pin": pin, "owners": users}
                           for (controller, pin), users in sorted(owners.items())],
            "findings": shared, "pinctrl_states": pinctrl,
            "unknown_references": unknown,
            "pad_ownership": "unknown-vendor-package-metadata-required"}


def inspect(build: Path, zephyr: Path, images=None, bindings=None):
    root, zephyr = Path(build).absolute(), Path(zephyr).absolute()
    for location in (root, zephyr):
        if not location.is_dir() or any(p.is_symlink() for p in (location, *location.parents)):
            raise InputError("Build and checkout must be existing non-symlink directories")
    search = _selected_edtlib(zephyr)
    binding_roots = _paths(zephyr, bindings or [])
    output = []
    for image, directory in _images(root, images or []):
        row = {"image": image, "state": "unknown", "confidence": "unknown"}
        artifact = _regular(directory, "zephyr/zephyr.dts")
        if artifact is None:
            row["reason"] = "missing-generated-dts"
            output.append(row)
            continue
        snapshot, checksum = _checked_file(artifact, MAX_DTS_BYTES)
        row["provenance"] = {"artifact": image + "/zephyr/zephyr.dts", "sha256": checksum,
                             "method": "selected-checkout edtlib typed phandle cells",
                             "freshness": "unverified-build-snapshot"}
        if not _build_checkout(directory, zephyr):
            row["reason"] = "selected-checkout-does-not-match-build"
            output.append(row)
            continue
        existing = {n: m for n, m in sys.modules.items() if n == "devicetree" or n.startswith("devicetree.")}
        for name in existing:
            sys.modules.pop(name, None)
        old_path = sys.path[:]
        sys.path.insert(0, str(search))
        try:
            edtlib = importlib.import_module("devicetree.edtlib")
            with TemporaryDirectory(prefix="zew-resources-") as temp:
                source = Path(temp) / "generated.dts"
                source.write_bytes(snapshot)
                row.update(analyze_edt(edtlib.EDT(str(source), binding_roots)))
            row.update(state="resource-references-observed", confidence="derived")
        except InputError:
            raise
        except Exception:
            row.update(reason="edtlib-unavailable-or-rejected", state="unknown", confidence="unknown")
        finally:
            sys.path[:] = old_path
            for name in list(sys.modules):
                if name == "devicetree" or name.startswith("devicetree."):
                    sys.modules.pop(name, None)
            sys.modules.update(existing)
        if _digest(artifact)[0] != checksum:
            row = {"image": image, "state": "unknown", "confidence": "unknown",
                   "reason": "generated-dts-changed-during-inspection"}
        output.append(row)
    return {"schema": "zew.studio-resources/1", "images": output,
            "status": "observed" if output and all(r["state"] == "resource-references-observed" for r in output) else "partial-or-unavailable",
            "limitations": ["GPIO references do not prove exclusive ownership or electrical conflict.",
                            "Package pad availability and vendor pinmux encodings are unknown.",
                            "Last generated configuration is not proof of runtime ownership."]}
