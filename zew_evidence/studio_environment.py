# SPDX-License-Identifier: Apache-2.0
"""CS-12: read-only preflight from actual configured CMake identities.

No tool command read from a build directory is executed. Existence is distinct
from compatibility; versions absent from metadata remain unknown.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import re
import sys

from .collector import InputError, _images, _read_observed, _regular

MAX_CACHE_BYTES = 2 * 1024 * 1024
_ENTRY = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):[A-Z_]+=(.*)$")
_TOOLS = (("cmake", ("CMAKE_COMMAND",)),
          ("ninja", ("CMAKE_MAKE_PROGRAM",)),
          ("c_compiler", ("CMAKE_C_COMPILER",)),
          ("python", ("PYTHON_EXECUTABLE", "Python3_EXECUTABLE", "Python_EXECUTABLE")))
_VERSION = re.compile(r"[0-9]{1,5}(?:\.[0-9]{1,5}){0,3}(?:[-+][A-Za-z0-9_.-]{1,64})?")


def _cache(snapshot):
    try:
        text = snapshot.decode("utf-8", errors="strict")
    except UnicodeError as error:
        raise InputError("CMake cache is not UTF-8") from error
    values = {}
    for line in text.splitlines():
        if line.startswith(("#", "//")) or not line:
            continue
        match = _ENTRY.fullmatch(line)
        if match:
            name, value = match.groups()
            if name in values:
                raise InputError("Duplicate CMake cache identity")
            if "\x00" in value:
                raise InputError("NUL in CMake cache value")
            values[name] = value
    return values


def _tool(values, keys, name):
    declarations = [values[key] for key in keys if values.get(key)]
    if not declarations:
        return {"state": "unknown", "reason": "not-recorded-in-cache", "version": "unknown"}
    if len(set(declarations)) != 1:
        return {"state": "unknown", "reason": "ambiguous-configured-executables", "version": "unknown"}
    value = declarations[0]
    if len(value) > 4096 or "\x00" in value or ";" in value:
        return {"state": "unknown", "reason": "invalid-configured-executable", "version": "unknown"}
    location = Path(value)
    if not location.is_absolute():
        # Original CMake working directory/PATH may differ from this inspector.
        # Neither current PATH lookup nor absence here proves tool availability.
        return {"state": "unknown", "reason": "configured-relative-path-requires-original-environment",
                "version": "unknown", "path_sha256": hashlib.sha256(value.encode("utf-8")).hexdigest()}
    try:
        exists = location is not None and location.is_file()
    except (OSError, ValueError):
        exists = False
    versions = [values[key + "_VERSION"] for key in keys if values.get(key + "_VERSION")]
    version = versions[0] if versions and len(set(versions)) == 1 and _VERSION.fullmatch(versions[0]) else "unknown"
    if name == "cmake":
        parts = [values.get("CMAKE_CACHE_" + component + "_VERSION", "")
                 for component in ("MAJOR", "MINOR", "PATCH")]
        if all(re.fullmatch(r"[0-9]{1,5}", part) for part in parts):
            version = ".".join(parts)
    return {"state": "available" if exists else "missing",
            "reason": "configured-path-exists" if exists else "configured-executable-unavailable",
            "version": version, "version_scope": "recorded-cache-metadata-not-current-executable",
            "path_sha256": hashlib.sha256(value.encode("utf-8")).hexdigest()}


def inspect(build: Path, images=None, expected_board=None, expected_zephyr=None, expected_versions=None):
    root = Path(build).absolute()
    if not root.is_dir() or any(p.is_symlink() for p in (root, *root.parents)):
        raise InputError("Build must be an existing non-symlink directory")
    if expected_board is not None and (not isinstance(expected_board, str) or not expected_board or len(expected_board) > 160):
        raise InputError("Expected board must be a bounded nonempty target name")
    expected_versions = expected_versions or {}
    if (not isinstance(expected_versions, dict)
            or any(key not in dict(_TOOLS) or not isinstance(value, str)
                   or not _VERSION.fullmatch(value) for key, value in expected_versions.items())):
        raise InputError("Expected versions require known tool names and exact bounded version strings")
    output = []
    for image, directory in _images(root, images or []):
        path = _regular(directory, "CMakeCache.txt")
        row = {"image": image, "state": "unknown", "findings": []}
        if path is None:
            row.update(reason="missing-cmake-cache")
            row["findings"].append({"rule_id": "CS12-NO-CONFIGURE", "severity": "info",
                "confidence": "confirmed", "observation": "Selected image has no CMakeCache.txt",
                "next_action": "Configure this exact image and board using the existing managed build action"})
            output.append(row)
            continue
        checksum, _, snapshot = _read_observed(path, MAX_CACHE_BYTES, capture=True)
        values = _cache(snapshot)
        row["provenance"] = {"artifact": image + "/CMakeCache.txt", "sha256": checksum,
                             "method": "read-only typed CMake cache identities"}
        row["tools"] = {name: _tool(values, keys, name) for name, keys in _TOOLS}
        for name, tool in row["tools"].items():
            if tool["state"] == "missing":
                row["findings"].append({"rule_id": "CS12-MISSING-" + name.upper(),
                    "severity": "error", "confidence": "confirmed",
                    "observation": "Recorded " + name + " executable is unavailable on this host",
                    "next_action": "Select the matching installed setup or restore the recorded tool; then reconfigure"})
        for name, expected in sorted(expected_versions.items()):
            tool = row["tools"][name]
            known = tool["version"]
            tool["expected_version"] = expected
            tool["version_match"] = "unknown" if known == "unknown" else "matching" if known == expected else "mismatch"
            if tool["version_match"] == "mismatch":
                row["findings"].append({"rule_id": "CS12-RECORDED-VERSION-MISMATCH-" + name.upper(),
                    "severity": "error", "confidence": "confirmed",
                    "observation": "Recorded " + name + " version differs from explicitly required version",
                    "next_action": "Use the matching tool environment and independently reconfigure; installed executable version remains unverified"})
        configured_board = values.get("BOARD")
        row["board"] = "unknown" if not configured_board else "recorded"
        if expected_board is not None:
            row["board"] = "unknown" if not configured_board else "matching" if configured_board == expected_board else "mismatch"
            if row["board"] == "mismatch":
                row["findings"].append({"rule_id": "CS12-BOARD-MISMATCH", "severity": "error",
                    "confidence": "confirmed", "observation": "CMake BOARD differs from the explicitly selected target",
                    "next_action": "Select the correct build directory or deliberately configure the selected target in an independent directory"})
        recorded = values.get("ZEPHYR_BASE")
        row["checkout"] = "unknown"
        if recorded:
            row["checkout"] = "recorded"
            if expected_zephyr is not None:
                try:
                    matches = Path(recorded).resolve(strict=True) == Path(expected_zephyr).resolve(strict=True)
                    row["checkout"] = "matching" if matches else "mismatch"
                except (OSError, ValueError):
                    row["checkout"] = "unavailable"
                if row["checkout"] == "mismatch":
                    row["findings"].append({"rule_id": "CS12-CHECKOUT-MISMATCH", "severity": "error",
                        "confidence": "confirmed", "observation": "Configured Zephyr checkout differs from the selected checkout",
                        "next_action": "Use the matching checkout/build pair; do not reuse generated files across versions"})
                elif row["checkout"] == "unavailable":
                    row["findings"].append({"rule_id": "CS12-CHECKOUT-UNAVAILABLE", "severity": "warning",
                        "confidence": "unknown", "observation": "Configured or selected checkout cannot be resolved",
                        "next_action": "Restore the original checkout or explicitly reconfigure with an installed setup"})
        row["generator"] = "ninja" if values.get("CMAKE_GENERATOR") == "Ninja" else "other-or-unknown"
        row["compatibility"] = "unknown-no-tool-execution-or-sdk-qualification"
        row["state"] = "blockers-observed" if any(f["severity"] == "error" for f in row["findings"]) else "metadata-inspected"
        output.append(row)
    return {"schema": "zew.studio-environment/1", "status": "inspected",
            "inspector_python": ".".join(map(str, sys.version_info[:3])), "images": output,
            "limitations": ["Executable existence does not prove version or SDK compatibility.",
                            "No cached command is executed; missing version metadata stays unknown.",
                            "Preflight does not install tools, modify sources or qualify firmware."]}
