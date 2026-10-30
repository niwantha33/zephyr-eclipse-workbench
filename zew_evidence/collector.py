# SPDX-License-Identifier: Apache-2.0
"""Read-only, deterministic collection of *observed* generated Zephyr artifacts.

This is the Phase 0 schema/CLI feasibility implementation. It does NOT evaluate
Kconfig, parse devicetree semantics, infer inclusion, or authenticate firmware.
"""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

SCHEMA_VERSION = "0.1.0"
MAX_ARTIFACT_BYTES = 256 * 1024 * 1024
MAX_CONFIG_BYTES = 8 * 1024 * 1024
FILES = (
    ("kconfig", "zephyr/.config"),
    ("devicetree", "zephyr/zephyr.dts"),
    ("elf", "zephyr/zephyr.elf"),
    ("map", "zephyr/zephyr.map"),
    ("compiler_database", "compile_commands.json"),
    ("cmake_cache", "CMakeCache.txt"),
    ("runners", "zephyr/runners.yaml"),
    ("build_info", "build_info.yml"),
    ("workbench_receipt", ".zew-built-target"),
    ("spdx_3_build", "spdx/build.jsonld"),
)
SET = re.compile(r"^(CONFIG_[A-Z0-9_]+)=(.*)$")
UNSET = re.compile(r"^# (CONFIG_[A-Z0-9_]+) is not set$")
SAFE_VALUE = re.compile(r"(?:[ymn]|-?[0-9]{1,16}|0[xX][0-9a-fA-F]{1,16})")
TARGET = re.compile(r"[A-Za-z0-9_.@/+-]{1,160}")
PRIVATE = re.compile(r"(?i)(?:PASSWORD|TOKEN|SECRET|AUTH|PRIVATE|KEY|PIN|SSID|CERT|CREDENTIAL|PSK)")
ARCHES = ("ARM", "ARM64", "RISCV", "XTENSA", "X86", "ARC")


class InputError(ValueError):
    pass


def _digest(path: Path) -> tuple[str, int]:
    total = 0
    sha = hashlib.sha256()
    with path.open("rb") as handle:
        while data := handle.read(64 * 1024):
            total += len(data)
            if total > MAX_ARTIFACT_BYTES:
                raise InputError(f"Artifact exceeds {MAX_ARTIFACT_BYTES} byte limit")
            sha.update(data)
    return sha.hexdigest(), total


def _regular(root: Path, name: str) -> Path | None:
    path = root
    for part in Path(name).parts:
        if part in ("", ".", "..") or Path(part).is_absolute():
            raise InputError("Invalid artifact path")
        path = path / part
        if path.is_symlink():
            raise InputError(f"Symlinked artifact path rejected: {name}")
    if not path.exists():
        return None
    if not path.is_file():
        raise InputError(f"Artifact is not a regular file: {name}")
    return path


def _kconfig(path: Path) -> dict[str, str]:
    if path.stat().st_size > MAX_CONFIG_BYTES:
        raise InputError("Generated .config is larger than the 8 MiB parsing limit")
    values: dict[str, str] = {}
    try:
        text = path.read_text(encoding="utf-8", errors="strict")
    except UnicodeError as error:
        raise InputError("Generated .config is not UTF-8") from error
    for line in text.splitlines():
        enabled, disabled = SET.fullmatch(line), UNSET.fullmatch(line)
        if enabled:
            # The generated value is an observation, NOT a dependency explanation.
            name, raw = enabled.groups()
            if PRIVATE.search(name) or not SAFE_VALUE.fullmatch(raw):
                if name in ("CONFIG_BOARD_TARGET", "CONFIG_SOC", "CONFIG_BOARD", "CONFIG_KERNEL_BIN_NAME"):
                    plain = raw.removeprefix('"').removesuffix('"')
                    # Reject paths and unquoted strings; names and values are untrusted.
                    public = (raw.startswith('"') and raw.endswith('"')
                              and bool(TARGET.fullmatch(plain))
                              and not plain.startswith(('/', '.', '~'))
                              and '..' not in plain.split('/')
                              and not PRIVATE.search(plain))
                    values[name] = plain if public else "<redacted>"
                else:
                    values[name] = "<redacted>"
            else:
                values[name] = raw
        elif disabled:
            values[disabled.group(1)] = "n"
    return dict(sorted(values.items()))


def _images(root: Path, images: list[str]) -> list[tuple[str, Path]]:
    if not images:
        return [("default", root)]
    resolved = []
    seen = set()
    for value in images:
        if "=" not in value:
            raise InputError("Use --image NAME=relative/build/path")
        name, relative = value.split("=", 1)
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,60}", name) or name in seen:
            raise InputError("Duplicate or invalid image name")
        if not relative or Path(relative).is_absolute() or ".." in Path(relative).parts:
            raise InputError("Image build location must be relative and inside --build")
        location = root
        for part in Path(relative).parts:
            location = location / part
            if location.is_symlink():
                raise InputError("Symlinked image path rejected")
        if not location.is_dir():
            raise InputError(f"Image directory not found: {name}")
        seen.add(name)
        resolved.append((name, location))
    return sorted(resolved)


def collect(build: Path, images: list[str] | None = None) -> dict:
    root = Path(build)
    if not root.is_dir() or root.is_symlink():
        raise InputError("--build must be an existing, non-symlink directory")
    root = root.resolve(strict=True)
    nodes: list[dict] = [{"id": "project:root", "type": "project", "confidence": "unknown",
                          "data": {"identity": "unspecified"}}]
    edges: list[dict] = []
    missing: list[dict] = []
    for name, directory in _images(root, images or []):
        image_id = f"image:{name}"
        nodes.append({"id": image_id, "type": "image", "confidence": "derived",
                      "data": {"name": name, "artifact_root": directory.relative_to(root).as_posix() or "."}})
        edges.append({"from": "project:root", "to": image_id, "type": "contains"})
        observed = {}
        for kind, relative in FILES:
            path = _regular(directory, relative)
            if path is None:
                if kind in ("kconfig", "devicetree"):
                    missing.append({"image": name, "artifact": relative, "state": "missing"})
                continue
            checksum, size = _digest(path)
            artifact_id = f"artifact:{name}:{kind}"
            logical_path = f"{name}/{relative}"
            provenance = {
                "artifact": logical_path, "sha256": checksum,
                "producing_tool": "unknown", "tool_version": "unknown",
                "extraction": "read-only SHA-256 file observation",
            }
            nodes.append({"id": artifact_id, "type": "artifact", "confidence": "confirmed",
                          "data": {"kind": kind, "bytes": size}, "provenance": provenance})
            edges.append({"from": image_id, "to": artifact_id, "type": "contains"})
            if kind == "kconfig":
                observed = _kconfig(path)
                for symbol, value in observed.items():
                    symbol_id = f"kconfig:{name}:{symbol}"
                    nodes.append({"id": symbol_id, "type": "kconfig_observation",
                                  "confidence": "confirmed",
                                  "data": {"name": symbol, "observed_value": value,
                                           "dependency_reason": "unknown"},
                                  "provenance": provenance})
                    edges.append({"from": artifact_id, "to": symbol_id, "type": "observed-in"})
        # A receipt is specific to this Workbench and not present in standard west builds.
        receipt = _regular(directory, ".zew-built-target")
        if receipt is not None and receipt.stat().st_size <= 256:
            raw = receipt.read_text(encoding="utf-8", errors="replace").strip()
            board = observed.get("CONFIG_BOARD_TARGET")
            if board and board != "<redacted>" and raw != board:
                missing.append({"image": name, "artifact": ".zew-built-target",
                                "state": "conflicting-board-identity",
                                "details": "Workbench receipt differs from generated CONFIG_BOARD_TARGET"})
    nodes.sort(key=lambda item: item["id"])
    edges.sort(key=lambda item: (item["from"], item["type"], item["to"]))
    missing.sort(key=lambda item: (item["image"], item["artifact"]))
    return {"schema_version": SCHEMA_VERSION, "status": "observations-only",
            "nodes": nodes, "edges": edges, "missing_evidence": missing,
            "limitations": [
                "Generated Kconfig values are observed, not dependency or inclusion explanations.",
                "Devicetree content is hashed, not interpreted; use Zephyr edtlib for semantics.",
                "ELF hashes identify bytes, not the firmware actually flashed on a target.",
                "External image locations require explicit --image declarations.",
                "No host paths, timestamps or unreviewed Kconfig strings are exported.",
            ]}


def diagnose(graph: dict) -> dict:
    findings = []
    for entry in graph["missing_evidence"]:
        conflict = entry["state"] == "conflicting-board-identity"
        findings.append({
            "id": f"ZEW-{'TARGET-CONFLICT' if conflict else 'MISSING-ARTIFACT'}:{entry['image']}:{entry['artifact']}",
            "confidence": "confirmed", "severity": "error" if conflict else "warning",
            "condition": entry["state"], "image": entry["image"],
            "evidence": entry["artifact"],
            "next_action": "Rebuild the selected exact board; verify its build receipt before flashing."
                if conflict else "Configure/build this image and inspect the generated output.",
        })
    return {"schema_version": SCHEMA_VERSION, "status": "partial",
            "findings": findings, "unknown": [
                "Device binding correctness", "Kconfig dependency reasons",
                "Build freshness versus all source inputs", "Physical target identity",
            ]}


def why(graph: dict, symbol: str) -> dict:
    if not re.fullmatch(r"CONFIG_[A-Z0-9_]+", symbol):
        raise InputError("Expected exact symbol such as CONFIG_GPIO")
    observations = [{"image": x["id"].split(":")[1],
                     "observed_value": x["data"]["observed_value"], "provenance": x["provenance"],
                     "confidence": x["confidence"]}
                    for x in graph["nodes"] if x["type"] == "kconfig_observation"
                    and x["data"]["name"] == symbol]
    return {"schema_version": SCHEMA_VERSION, "symbol": symbol, "observations": observations,
            "why_enabled": "unknown", "missing_evidence": [
                "kconfiglib dependency and symbol-origin evaluation for the selected Zephyr checkout",
                "source configuration fragment provenance",
            ]}
