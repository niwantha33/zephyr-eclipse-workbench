# SPDX-License-Identifier: Apache-2.0
"""Exact, evidence-linked SPDX 3 Build-input explanations without inclusion claims.

Consuming Zephyr west SPDX output is intentionally distinct from the
Kconfiglib-backed CONFIG_* reasoner, which has not yet been implemented.
"""
from __future__ import annotations

import re
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION, _images, _regular, collect
from .spdx import DOCUMENTS, MAX_SPDX_BYTES, _read, _load_jsonld, _name

MAX_REFERENCES = 100000
MAX_ELEMENTS = 50000
KINDS = {"source": "software_File", "module": "software_Package"}


def _query(kind: str, value: str) -> str:
    if kind not in KINDS:
        raise InputError("Expected source or module")
    if (not isinstance(value, str) or not value or len(value) > 512
            or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value)
            or value.startswith(("/", "~", "."))
            or "\\" in value or ":" in value or "//" in value
            or any(p in (".", "..") for p in value.split("/"))):
        raise InputError("Expected a safe, exact SPDX element name relative to the source root")
    return value


def _id(item: dict) -> str | None:
    value = item.get("spdxId") or item.get("@id")
    return value if isinstance(value, str) and 0 < len(value) <= 4096 else None


def _index(directory: Path, image: str, kind: str, name: str,
           snapshot_sha: str) -> dict:
    identity = KINDS[kind]
    documents: list[tuple[str, list[dict], dict]] = []
    for doc in DOCUMENTS:
        path = _regular(directory, f"spdx/{doc}.jsonld")
        if path is None:
            continue
        raw, provenance = _read(path, MAX_SPDX_BYTES)
        if doc == "build" and provenance["sha256"] != snapshot_sha:
            raise InputError("SPDX build document differs from the Evidence Graph snapshot")
        elements = _load_jsonld(raw)
        documents.append((doc, elements, {"artifact": f"{image}/spdx/{doc}.jsonld",
                                           **provenance}))
    main = next(((elements, proof) for doc, elements, proof in documents
                 if doc == "build"), None)
    if main is None:
        return {"image": image, "status": "missing-spdx3-build-evidence", "confidence": "unknown",
                "matching_elements": 0, "builds_with_direct_input": 0,
                "build_relationships": [], "missing_evidence": ["spdx/build.jsonld"]}
    build_elements, build_proof = main
    builds = set()
    for item in build_elements:
        if _name(item.get("type")) == "build_Build":
            ident = _id(item)
            if ident:
                builds.add(ident)
    if not builds:
        return {"image": image, "status": "no-build-profile", "confidence": "unknown",
                "matching_elements": 0, "builds_with_direct_input": 0,
                "build_relationships": [], "missing_evidence": ["SPDX 3 Build element"]}

    names: dict[str, tuple[str, str]] = {}
    files: dict[str, dict] = {}
    matches = set()
    total = 0
    for doc, elements, provenance in documents:
        total += len(elements)
        if total > MAX_ELEMENTS * len(DOCUMENTS):
            raise InputError("Too many SPDX elements across documents")
        for item in elements:
            if _name(item.get("type")) not in ("software_File", "software_Package"):
                continue
            ident = _id(item)
            label = item.get("name")
            if not ident or not isinstance(label, str):
                continue
            previous = names.get(ident)
            pair = (_name(item["type"]), label)
            if previous is not None and previous != pair:
                raise InputError("Conflicting SPDX identity appears in multiple documents")
            names[ident] = pair
            if pair == (identity, name):
                matches.add(ident)
                files[ident] = provenance

    # Duplicate names may denote different upstream components or files.
    # Never arbitrarily pick one and present that edge as proven identity.
    if len(matches) > 1:
        return {"image": image, "status": "ambiguous-spdx-element", "confidence": "unknown",
                "matching_elements": len(matches), "builds_with_direct_input": 0,
                "build_relationships": [],
                "missing_evidence": ["Unique upstream SPDX element identity for this exact name"]}

    declared = next(iter(matches), None)
    inputs, outputs, unresolved = {}, {}, 0
    for item in build_elements:
        if _name(item.get("type")) != "LifecycleScopedRelationship":
            continue
        if _name(item.get("scope")) != "build":
            continue
        source = item.get("from")
        typ = _name(item.get("relationshipType"))
        dest = item.get("to")
        if (not isinstance(source, str) or source not in builds
                or typ not in ("hasInput", "hasOutput", "usesTool")
                or not isinstance(dest, list) or not dest
                or len(dest) > MAX_REFERENCES
                or any(not isinstance(x, str) or len(x) > 4096 for x in dest)):
            continue
        if typ == "hasInput":
            inputs.setdefault(source, set()).update(dest)
            unresolved += sum(x not in names for x in dest)
        elif typ == "hasOutput":
            outputs.setdefault(source, set()).update(dest)
    direct = sorted(b for b, targets in inputs.items() if declared and declared in targets)
    witnesses = [{"relationship": "hasInput", "confidence": "confirmed",
                  "output_relationship_targets": len(outputs.get(build_id, ())),
                  "output_causality": "unknown",
                  "build_document": build_proof, "element_document": files[declared]}
                 for build_id in direct]
    status = ("observed-direct-build-input" if witnesses else
              "no-direct-build-input-observed" if matches else
              "name-not-found-in-spdx-documents")
    return {"image": image, "status": status,
            "confidence": "confirmed" if witnesses else "unknown",
            "matching_elements": len(matches), "builds_with_direct_input": len(direct),
            "build_relationships": witnesses,
            "unresolved_build_input_references": unresolved,
            "missing_evidence": [] if witnesses else [
                "A direct Build-scope hasInput relationship for the exact SPDX element name"]}


def explain(build: Path, kind: str, name: str, images: list[str] | None = None) -> dict:
    requested = _query(kind, name)
    graph = collect(build, images or [])
    proofs = {node["id"]: node["provenance"]["sha256"] for node in graph["nodes"]
              if node["type"] == "artifact" and node["data"]["kind"] == "spdx_3_build"}
    root = Path(build).resolve(strict=True)
    reports = []
    for image, directory in _images(root, images or []):
        artifact_id = f"artifact:{image}:spdx_3_build"
        digest = proofs.get(artifact_id, "")
        report = _index(directory, image, kind, requested, digest)
        report["evidence_graph_anchor"] = (
            {"node_id": artifact_id, "sha256": digest, "confidence": "confirmed"}
            if digest else None)
        reports.append(report)
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "upstream-spdx-input-explanation",
        "query": {"kind": kind, "selection": "exact element name", "name": "<not-exported>"},
        "images": reports,
        "limitations": [
            "Observed hasInput describes an upstream SPDX Build input, not proof of compilation into the final ELF.",
            "The relationship may describe a source or a package (module root); it does not imply every file in that module compiled.",
            "SPDX references may span files; matching uses exact names, never guesswork or filename suffixes.",
            "Even a matching SPDX hash does not prove source/configuration freshness or that the firmware was flashed.",
            "Absent or ambiguous evidence means unknown, never a negative inclusion or vulnerability assertion.",
            "Source names and module identifiers are not exported; JSON-LD is never fetched or executed.",
            "Kconfig dependency causality still requires Zephyr kconfiglib, not these SPDX relationships.",
        ],
    }
