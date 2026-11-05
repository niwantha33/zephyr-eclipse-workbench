# SPDX-License-Identifier: Apache-2.0
"""Keep driver evidence levels separate; consume existing exact-source adapters.

The caller chooses a source and optionally a CONFIG symbol. They are independent
observations: no compatible-to-driver or Kconfig-to-CMake causal mapping is guessed.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import re

from .collector import InputError, collect, _digest, _images, _regular, _kconfig, MAX_CONFIG_BYTES
from .compilation import inspect as inspect_compilation, _source
from .linker import inspect as inspect_linker
from .dt_binding_preview import _checked_file
from .peripheral_evidence import _bool_observation

_SYMBOL = re.compile(r"CONFIG_[A-Z0-9_]{1,160}")


def inspect(build: Path, source: str, source_root: Path, symbol: str | None = None,
            images: list[str] | None = None) -> dict:
    if symbol is not None and not _SYMBOL.fullmatch(symbol):
        raise InputError("Select an exact CONFIG_ symbol")
    selected_source = _source(source_root, source)
    source_digest, source_bytes = _digest(selected_source)
    root = Path(build).absolute()
    graph = collect(root, images or [])
    compiled = inspect_compilation(root, source, source_root, images, graph)
    linked = inspect_linker(root, source, source_root, images, graph)
    compile_rows = {item["image"]: item for item in compiled["images"]}
    link_rows = {item["image"]: item for item in linked["images"]}
    graph_config = {node["id"]: node["provenance"]["sha256"] for node in graph["nodes"]
                    if node["type"] == "artifact" and node["data"]["kind"] == "kconfig"}
    rows = []
    for image, directory in _images(root, images or []):
        command, link = compile_rows[image], link_rows[image]
        config = {"state": "not-requested", "confidence": "unknown", "sha256": None}
        if symbol is not None:
            path = _regular(directory, "zephyr/.config")
            if path is None:
                config["state"] = "generated-config-missing"
            else:
                snapshot, digest = _checked_file(path, MAX_CONFIG_BYTES)
                if graph_config.get(f"artifact:{image}:kconfig") != digest:
                    raise InputError("Generated config changed during driver inspection")
                observed = _bool_observation(snapshot, symbol, _kconfig(snapshot))
                config = {"state": observed, "confidence": "confirmed" if observed in
                          ("enabled", "disabled", "module") else "unknown", "sha256": digest}
        obj_digest = link.get("observed_object_sha256")
        contribution = link["status"] == "map-section-contribution-observed"
        load_only = link["status"] == "map-load-observed"
        rows.append({
            "image": image,
            "candidate": {"state": "explicitly-selected-source", "confidence": "confirmed",
                          "source_sha256": source_digest, "source_bytes": source_bytes,
                          "driver_identity": "caller-declared; not inferred from Devicetree"},
            "configured": config,
            "compile_command": {"state": command["status"], "confidence": command["confidence"],
                                "matching_commands": command["matching_commands"],
                                "provenance": command["provenance"]},
            "compiled": {"state": "object-bytes-observed" if obj_digest else "unknown",
                         "confidence": "confirmed" if obj_digest else "unknown",
                         "object_sha256": obj_digest,
                         "compiler_execution": "unknown"},
            "linked": {"state": "map-assigned-section-observed" if contribution else
                       "map-load-only" if load_only else "unknown",
                       "confidence": "derived" if contribution else "unknown",
                       "assigned_section_bytes": (link.get("map_observation") or {}).get("assigned_section_bytes", 0),
                       "final_elf_binding": "unknown", "provenance": link["provenance"]},
            "initialized": {"state": "unknown", "confidence": "unknown",
                            "required_evidence": "matching-image runtime device initialization result"},
            "causality": {"kconfig_to_source": "unverified", "dts_to_driver": "unverified"},
            "missing_evidence": link["missing_evidence"],
        })
    if _digest(selected_source)[0] != source_digest:
        raise InputError("Selected source changed during driver inspection")
    return {"schema": "zew.studio-driver/1", "status": "separate-evidence-levels",
            "selection_sha256": hashlib.sha256(source.encode("utf-8")).hexdigest(),
            "images": rows,
            "limitations": ["Caller-selected driver source and CONFIG symbol have no inferred causal relationship.",
                            "A compile database records commands, not successful compiler execution.",
                            "Object bytes are not proof that the selected source produced those bytes.",
                            "Map contributions are upstream text observations, not authenticated final ELF binding.",
                            "Missing evidence never proves driver exclusion or initialization failure.",
                            "No initialized, flashed or physical device state is inferred from build artifacts."]}
