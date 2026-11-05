# SPDX-License-Identifier: Apache-2.0
"""Read-only, offline inventory of upstream Zephyr west SPDX build evidence.

No SBOM generation, vulnerability assessment, JSON-LD context fetching or
assumption that an archived SPDX file matches the current firmware is made.
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION, _images, _regular, MAX_CONFIG_BYTES

MAX_SPDX_BYTES = 16 * 1024 * 1024
DOCUMENTS = ("app", "zephyr", "build", "modules-deps")
VERSIONS = ("3.0", "3.0.1", "3.1")
RELATIONS = ("hasInput", "hasOutput", "usesTool")
CONTEXT = re.compile(r"^https://spdx\.org/rdf/3\.(?:0\.1|1)(?:/spdx-context\.jsonld)?$")
METADATA = "CONFIG_BUILD_OUTPUT_META"


def _read(path: Path, limit: int) -> tuple[bytes, dict]:
    before = path.stat()
    with path.open("rb") as reader:
        raw = reader.read(limit + 1)
    after = path.stat()
    if len(raw) > limit:
        raise InputError("SPDX/configuration artifact exceeds bounded evidence limit")
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (
            after.st_size, after.st_mtime_ns, after.st_ino):
        raise InputError("SPDX/configuration artifact changed during collection")
    return raw, {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}


def _pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise InputError("Duplicate JSON key in SPDX document")
        result[key] = value
    return result


def _load_jsonld(raw: bytes) -> list[dict]:
    try:
        doc = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=_pairs)
    except (UnicodeError, ValueError, RecursionError) as error:
        raise InputError("Invalid or excessively deep SPDX JSON-LD") from error
    if not isinstance(doc, dict) or not isinstance(doc.get("@graph"), list):
        raise InputError("SPDX JSON-LD must contain an @graph array")
    # Only inspect a locally packaged SPDX context identifier. Never retrieve
    # remote JSON-LD contexts or execute transformations on untrusted input.
    context = doc.get("@context")
    if not isinstance(context, str) or not CONTEXT.fullmatch(context):
        raise InputError("SPDX JSON-LD context not recognised as SPDX 3.0/3.1")
    elements = doc["@graph"]
    if len(elements) > 50000 or any(not isinstance(item, dict) for item in elements):
        raise InputError("SPDX JSON-LD has excessive or invalid graph elements")
    return elements


def _name(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    return value.rsplit("/", 1)[-1] if value.startswith("https://spdx.org/rdf/") else value


def _profile(elements: list[dict]) -> dict:
    # Counts are intentionally observational; an external validator is needed
    # to determine formal SPDX 3.0 Build profile conformance.
    builds = set()
    relationships = dict.fromkeys(RELATIONS, 0)
    for item in elements:
        if _name(item.get("type")) in ("build_Build", "Build"):
            ident = item.get("spdxId") or item.get("@id")
            if isinstance(ident, str) and len(ident) <= 4096:
                builds.add(ident)
    for item in elements:
        if _name(item.get("type")) not in ("LifecycleScopedRelationship", "Relationship"):
            continue
        relation = _name(item.get("relationshipType"))
        if relation not in relationships:
            continue
        subject = item.get("from")
        if not isinstance(subject, str) or subject not in builds:
            continue
        if _name(item.get("type")) == "LifecycleScopedRelationship":
            scope = _name(item.get("scope"))
            if scope != "build":
                continue
        destinations = item.get("to")
        if not isinstance(destinations, list) or not destinations or not all(
                isinstance(x, str) for x in destinations):
            continue
        relationships[relation] += 1
    return {"build_elements": len(builds), "observed_build_relationships": relationships,
            "profile_evidence": "relationships-observed" if builds and any(relationships.values())
            else "build-element-only" if builds else "not-observed"}


def _config_flag(directory: Path) -> dict:
    path = _regular(directory, "zephyr/.config")
    if path is None:
        return {"value": "unknown", "reason": "generated .config missing", "provenance": None}
    raw, info = _read(path, MAX_CONFIG_BYTES)
    # Same captured bytes yield both evidence and observation, never expose
    # other names/values from a private Kconfig file.
    flag = "n"
    try:
        for line in raw.decode("utf-8", "strict").splitlines():
            if line == METADATA + "=y":
                flag = "y"
            elif line == "# " + METADATA + " is not set" or line == METADATA + "=n":
                flag = "n"
    except UnicodeError as error:
        raise InputError("Generated .config is not UTF-8") from error
    return {"value": flag, "reason": "generated Kconfig assignment" if flag == "y"
            else "not enabled in generated Kconfig", "provenance":
            {"artifact": "zephyr/.config", **info}}


def inspect_build(build: Path, images: list[str] | None = None) -> dict:
    root = Path(build)
    if root.is_symlink() or not root.is_dir():
        raise InputError("--build must be an existing, non-symlink directory")
    root = root.resolve(strict=True)
    reports = []
    for image, directory in _images(root, images or []):
        cfg = _config_flag(directory)
        docs = []
        for name in DOCUMENTS:
            ver = None
            artifact = None
            data = None
            for ext, family in ((".jsonld", "spdx3"), (".spdx", "spdx2")):
                candidate = _regular(directory, f"spdx/{name}{ext}")
                if candidate is not None:
                    ver, artifact = family, candidate
                    break
            if artifact is None:
                docs.append({"document": name, "state": "missing", "format": "unknown",
                             "provenance": None})
                continue
            raw, identity = _read(artifact, MAX_SPDX_BYTES)
            profile = None
            if ver == "spdx3":
                profile = _profile(_load_jsonld(raw))
            docs.append({"document": name, "state": "observed", "format": ver,
                         "provenance": {"artifact": f"{image}/spdx/{artifact.name}",
                                        **identity},
                         "build_profile": profile})
        build_doc = next(d for d in docs if d["document"] == "build")
        profile = build_doc.get("build_profile")
        if build_doc["state"] == "missing":
            state = "missing-build-sbom"
        elif build_doc["format"] == "spdx2":
            state = "spdx2-only"
        elif profile["profile_evidence"] == "not-observed":
            state = "spdx3-without-observed-build-profile"
        else:
            state = "spdx3-build-evidence-observed"
        reports.append({"image": image, "status": state, "metadata_config": cfg,
                        "documents": docs, "next_action": (
                            "Enable CONFIG_BUILD_OUTPUT_META=y, rebuild, then run "
                            "'west spdx -d BUILD_DIR --spdx-version 3.0' explicitly."
                            if cfg["value"] != "y" else
                            "Run 'west spdx -d BUILD_DIR --spdx-version 3.0' "
                            "on the exact image build directory if documents are missing; "
                            "validate with upstream SPDX tooling.")})
    return {
        "schema_version": SCHEMA_VERSION, "status": "upstream-spdx-observation",
        "images": reports,
        "limitations": [
            "Only SPDX 2 tag-value presence or SPDX 3 JSON-LD Build Profile shapes are observed.",
            "This is not an SPDX conformance validator; SPDX 2 contents are not interpreted.",
            "A document can be stale or forged; its provenance does not bind it to the current ELF.",
            "Relationship counts do not establish that sources were compiled or in the flashed image.",
            "No CPE/PURL, CVE applicability, VEX, licence compliance or legal conclusions are produced.",
            "No subprocess, west, internet request, or target operation was performed.",
        ],
    }
