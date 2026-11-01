# SPDX-License-Identifier: Apache-2.0
"""Conservative, read-only overlay guidance using the selected Zephyr edtlib.

A proposed override is not a firmware build, proof of board pin availability,
or a binding-validated result. No file is written, executed or automatically
activated. Only a user-supplied existing property with an upstream binding
type/enum, or status, can be proposed. All other edits remain manual.
"""
from __future__ import annotations

import difflib
import hashlib
import importlib
import json
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory

from .collector import InputError, SCHEMA_VERSION, _digest, _images, _regular
from .dt_binding_preview import (_NODE_PATH, _PROPERTY, _build_checkout,
                                 _checked_file, _paths, _selected_edtlib,
                                 MAX_DTS_BYTES, MAX_OVERLAY_BYTES)

_SAFE_INT = re.compile(r"(?:0|[1-9][0-9]{0,9}|0[xX][0-9a-fA-F]{1,8})")
_SAFE_ENUM = re.compile(r"[A-Za-z0-9_.+ /-]{1,60}")
_FORBIDDEN = re.compile(
    r"(?i)(?:password|token|secret|auth|private|credential|ssid|psk|"
    r"(?:^|[,_-])(?:pin|key|cert)(?:[,_-]|$))")
_TOPOLOGY = frozenset({
    "reg", "ranges", "interrupts", "interrupt-parent", "interrupts-extended",
    "compatible", "gpio-controller", "interrupt-controller", "pinctrl-names",
    "phandle", "linux,phandle", "io-channels", "clocks", "resets",
    "dmas", "power-domains", "memory-region", "gpios", "cs-gpios",
    "scl-gpios", "sda-gpios", "pinctrl-0", "pinctrl-1",
})
_KIND = frozenset(("gpio", "adc", "spi", "i2c"))
_MAX_SHOW_DIFF = 100 * 1024


class _ManualReview(Exception):
    """Expected incomplete binding evidence; do not claim a source draft."""


def _selected_image(build: Path, images: list[str]) -> tuple[str, Path]:
    chosen = _images(Path(build).absolute(), images)
    if len(chosen) != 1:
        raise InputError("Guided changes require exactly one explicit Zephyr image")
    return chosen[0]


def _overlay(app: Path | None, overlay: Path | None) -> tuple[bytes, str | None, str]:
    if overlay is None:
        return b"", None, "no-overlay-selected"
    if app is None:
        raise InputError("--overlay requires --application")
    app = Path(app).absolute()
    if any(item.is_symlink() for item in (app, *app.parents)) or not app.is_dir():
        raise InputError("Selected application must be a regular directory")
    source = Path(overlay).absolute()
    if any(item.is_symlink() for item in (source, *source.parents)):
        raise InputError("Symlinked overlay paths are not supported")
    if source.suffix != ".overlay" or not source.is_relative_to(app):
        raise InputError("Select a .overlay file inside the exact application")
    if not source.exists():
        if not source.parent.is_dir():
            raise InputError("New overlay parent directory does not exist")
        return b"", None, "new-file-proposal"
    original, digest = _checked_file(source, MAX_OVERLAY_BYTES)
    return original, digest, "existing-file-proposal"


def _request(status: str | None, integer: str | None,
             enum: str | None) -> tuple[str, str, str]:
    selected = [x is not None for x in (status, integer, enum)]
    if sum(selected) != 1:
        raise InputError("Choose exactly one --status, --set-int, or --set-enum")
    if status is not None:
        if status not in ("okay", "disabled"):
            raise InputError("Status override must be exactly okay or disabled")
        return "status", "status", json.dumps(status, ensure_ascii=True)
    request = integer if integer is not None else enum
    if "=" not in request:
        raise InputError("Use PROPERTY=VALUE for guided property requests")
    name, raw = request.split("=", 1)
    if (not _PROPERTY.fullmatch(name) or name in _TOPOLOGY
            or name.startswith("#") or name.startswith("pinctrl-")
            or name.endswith("-gpios") or name.endswith("-supply")
            or _FORBIDDEN.search(name)):
        raise InputError("Structural, pinmux, reference or sensitive properties need manual review")
    if integer is not None:
        if not _SAFE_INT.fullmatch(raw):
            raise InputError("Integer must be a bounded positive decimal or 32-bit hexadecimal number")
        number = int(raw, 0)
        if number > 0xFFFFFFFF:
            raise InputError("Integer exceeds the bounded 32-bit preview range")
        return "int", name, f"<{number}>"
    if not _SAFE_ENUM.fullmatch(raw) or _FORBIDDEN.search(raw):
        raise InputError("Enum proposal must be a bounded non-sensitive token")
    return "enum", name, json.dumps(raw, ensure_ascii=True)


def _interfaces(node) -> list[str]:
    """Only positive structural hints from upstream EDT; never a pin claim."""
    result = []
    properties = node.props
    if "gpio-controller" in properties:
        result.append("gpio-controller-property")
    if "#io-channel-cells" in properties:
        result.append("adc-io-channel-cells-property")
    buses = getattr(node, "buses", [])
    on_buses = getattr(node, "on_buses", [])
    for bus in ("spi", "i2c"):
        if bus in buses:
            result.append(bus + "-controller-bus")
        if bus in on_buses:
            result.append(bus + "-child-bus")
    return sorted(set(result))


def guide(build: Path, zephyr: Path, node_path: str,
          status: str | None = None, integer: str | None = None,
          enum: str | None = None, app: Path | None = None,
          overlay: Path | None = None, kind: str | None = None,
          images: list[str] | None = None, bindings: list[Path] | None = None,
          show_diff: bool = False) -> dict:
    if not _NODE_PATH.fullmatch(node_path) or len(node_path) > 240:
        raise InputError("Select an exact generated Devicetree path")
    if kind is not None and kind not in _KIND:
        raise InputError("Only gpio, adc, spi and i2c guided review are supported")
    request_kind, property_name, value = _request(status, integer, enum)
    image, directory = _selected_image(build, images or [])
    zephyr = Path(zephyr).absolute()
    search = _selected_edtlib(zephyr)
    roots = _paths(zephyr, bindings or [])
    original_overlay, overlay_sha, overlay_mode = _overlay(app, overlay)
    artifact = _regular(directory, "zephyr/zephyr.dts")
    if artifact is None:
        raise InputError("Selected image has no generated Devicetree")
    original_dts, dts_sha = _checked_file(artifact, MAX_DTS_BYTES)
    if not _build_checkout(directory, zephyr):
        raise InputError("Exact selected Zephyr checkout cannot be verified")
    result = {"schema_version": SCHEMA_VERSION, "image": image,
              "status": "manual-review-required", "request_type": request_kind,
              "requested_peripheral_kind": kind,
              "node_sha256": hashlib.sha256(node_path.encode("utf-8")).hexdigest(),
              "property_sha256": hashlib.sha256(property_name.encode("utf-8")).hexdigest(),
              "generated_dts_sha256": dts_sha,
              "overlay": {"mode": overlay_mode, "original_sha256": overlay_sha},
              "source": {"tool": "selected-zephyr-edtlib", "confidence": "unknown",
                         "binding": "unmatched", "node": "unknown",
                         "generated_status": "unknown", "property_type": "unknown",
                         "selected_property_deprecated": None,
                         "interface_hints": []},
              "proposal": {"available": False, "source_addition_sha256": None,
                           "post_change_overlay_sha256": None,
                           "visible_diff": None},
              "limitations": [
                  "Preview only; no application overlay, generated build or target is modified.",
                  "Only an existing binding-interpreted property or an explicit node status may be proposed.",
                  "Selected edtlib and binding directories must match the exact configured build.",
                  "The peripheral kind is a user request, not proof of the device class or driver.",
                  "No pad/free-pin, pinmux, clock, bus electrical, physical wiring or driver inclusion is inferred.",
                  "Proposed source is not accepted by CMake/edtlib until validated in a separate real configure.",
                  "Do not copy private overlay source into reports without explicitly requesting --show-diff.",
              ]}
    previous = {key: module for key, module in sys.modules.items()
                if key == "devicetree" or key.startswith("devicetree.")}
    for key in previous:
        sys.modules.pop(key, None)
    sys.path.insert(0, str(search))
    try:
        edtlib = importlib.import_module("devicetree.edtlib")
        with TemporaryDirectory(prefix="zew-guide-dts-") as tmp:
            generated = Path(tmp) / "selected.dts"
            generated.write_bytes(original_dts)
            edt = edtlib.EDT(str(generated), roots)
            try:
                node = edt.get_node(node_path)
            except Exception:
                result["source"]["node"] = "not-in-generated-dts"
                raise _ManualReview()
            src = result["source"]
            src["node"] = "present-in-generated-dts"
            src["generated_status"] = node.status if node.status in (
                "okay", "disabled", "reserved", "fail", "fail-sss") else "unknown"
            src["binding"] = "matched" if node.binding_path else "unmatched"
            src["interface_hints"] = _interfaces(node)
            if not node.binding_path:
                raise _ManualReview()
            spec = None
            if request_kind == "status":
                # status is a Zephyr standard property, not a device-driver readiness claim.
                src["property_type"] = "status-string"
            else:
                property = node.props.get(property_name)
                if property is None or getattr(property, "spec", None) is None:
                    raise _ManualReview()
                spec = property.spec
                src["property_type"] = spec.type if spec.type in (
                    "int", "string", "boolean", "array", "string-array", "phandle",
                    "phandle-array", "uint8-array") else "other"
                src["selected_property_deprecated"] = bool(spec.deprecated)
                if src["selected_property_deprecated"]:
                    raise _ManualReview()
                if request_kind == "int":
                    if spec.type != "int":
                        raise _ManualReview()
                    number = int(value[1:-1])
                    if spec.enum is not None and number not in spec.enum:
                        raise _ManualReview()
                    if spec.const is not None and number != spec.const:
                        raise _ManualReview()
                else:
                    if spec.type != "string" or not spec.enum:
                        raise _ManualReview()
                    proposed = json.loads(value)
                    if proposed not in spec.enum or spec.const is not None and proposed != spec.const:
                        raise _ManualReview()
            src["confidence"] = "confirmed"
            block = ("/* Review only — this does not validate pin/driver hardware. */\n"
                     + "&{" + node_path + "} {\n    " + property_name + " = " + value
                     + ";\n};\n")
            new = (original_overlay + (b"\n" if original_overlay and not
                   original_overlay.endswith(b"\n") else b"") +
                   block.encode("utf-8"))
            if len(new) > MAX_OVERLAY_BYTES:
                raise InputError("Proposed overlay exceeds 64 KiB")
            preview = result["proposal"]
            preview.update({"available": True,
                            "source_addition_sha256": hashlib.sha256(block.encode("utf-8")).hexdigest(),
                            "post_change_overlay_sha256": hashlib.sha256(new).hexdigest()})
            result["status"] = "draft-requires-real-configuration"
            if show_diff:
                # User explicitly opts into seeing source context, including
                # potentially private lines from the pre-existing overlay.
                old_text = original_overlay.decode("utf-8", errors="strict").splitlines(keepends=True)
                new_text = new.decode("utf-8", errors="strict").splitlines(keepends=True)
                diff = "".join(difflib.unified_diff(old_text, new_text,
                              fromfile="selected-overlay-before", tofile="selected-overlay-proposal",
                              lineterm="\n"))
                if len(diff.encode("utf-8")) > _MAX_SHOW_DIFF:
                    raise InputError("Generated review diff exceeds 100 KiB")
                preview["visible_diff"] = diff
    except _ManualReview:
        pass
    except InputError:
        raise
    except Exception:
        # Exceptions from vendor bindings may contain source paths and private
        # literal property values; do not include them in the report.
        result["status"] = "manual-review-required"
        result["source"]["confidence"] = "unknown"
    finally:
        sys.path.remove(str(search))
        for key in list(sys.modules):
            if key == "devicetree" or key.startswith("devicetree."):
                sys.modules.pop(key, None)
        sys.modules.update(previous)
    if _digest(artifact)[0] != dts_sha:
        raise InputError("Selected generated Devicetree changed during planning")
    if overlay is not None and overlay_sha is not None:
        if _checked_file(Path(overlay), MAX_OVERLAY_BYTES)[1] != overlay_sha:
            raise InputError("Selected overlay changed during planning")
    return result
