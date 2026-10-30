# SPDX-License-Identifier: Apache-2.0
"""Offline evidence bundle: sanitised graph only, tamper-evident, never extract ZIP.

This is a Phase-0 feasibility package, not proof of provenance or authenticity.
Hash checks detect corruption, not a malicious producer who can rewrite both.
"""
from __future__ import annotations
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path
from .collector import InputError, SCHEMA_VERSION, SAFE_VALUE, TARGET, PRIVATE, FILES, MAX_ARTIFACT_BYTES, LIMITATIONS

FORMAT = "zew-evidence-0.1"
MAX_GRAPH_BYTES = 12 * 1024 * 1024
MAX_MANIFEST_BYTES = 2048
ALLOWED = {"manifest.json", "graph.json"}
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def _json(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
            + "\n").encode("utf-8")


def preview(graph: dict) -> dict:
    payload = _json(graph)
    if len(payload) > MAX_GRAPH_BYTES:
        raise InputError("Sanitised evidence graph exceeds 12 MiB bundle limit")
    return {
        "format": FORMAT, "schema_version": SCHEMA_VERSION,
        "files": [{"name": "graph.json", "bytes": len(payload),
                   "sha256": hashlib.sha256(payload).hexdigest()}],
        "privacy": "sanitised graph only; no ELF, source, raw DTS, logs or arbitrary configuration strings",
        "warning": "Inspection verifies integrity but not the authority of the original producer",
        "write_requires": "explicit --write, an existing output directory, and an absent target",
    }


def _zip_item(name: str, payload: bytes):
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | 0o600) << 16
    return info, payload


def write(graph: dict, output: Path) -> dict:
    plan = preview(graph)
    destination = Path(output)
    if destination.suffix.lower() != ".zip":
        raise InputError("Output must use the .zip extension")
    if destination.is_symlink() or destination.exists():
        raise InputError("Refusing to overwrite or follow existing output")
    parent = destination.parent
    if parent.is_symlink() or not parent.is_dir():
        raise InputError("Output parent must be an existing real directory")
    graph_bytes = _json(graph)
    manifest = _json({
        "format": FORMAT, "schema_version": SCHEMA_VERSION,
        "entries": {"graph.json": {
            "sha256": plan["files"][0]["sha256"],
            "bytes": len(graph_bytes),
        }},
        "attestation": "none",
    })
    if len(manifest) > MAX_MANIFEST_BYTES:
        raise InputError("Manifest exceeds size limit")
    tmp_name = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".zew-evidence-", suffix=".zip",
                                         dir=parent, delete=False) as tmp:
            tmp_name = Path(tmp.name)
        with zipfile.ZipFile(tmp_name, "w", allowZip64=False) as zf:
            for name, contents in (("manifest.json", manifest), ("graph.json", graph_bytes)):
                item, data = _zip_item(name, contents)
                zf.writestr(item, data)
        # Exclusive create prevents replacing existing user data. Copy only a
        # fully validated temporary ZIP; on error remove the incomplete output.
        created = False
        try:
            # Exclusive + restrictive permissions: an evidence ZIP must not be
            # world-readable merely because the user's umask is permissive.
            # O_NOFOLLOW also blocks a last-moment destination symlink swap
            # where the platform provides it.
            flags = (os.O_WRONLY | os.O_CREAT | os.O_EXCL
                     | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
            fd = os.open(destination, flags, 0o600)
            created = True
            with os.fdopen(fd, "wb") as target:
                with tmp_name.open("rb") as original:
                    shutil.copyfileobj(original, target, length=64 * 1024)
                target.flush()
                os.fsync(target.fileno())
        except BaseException:
            if created:
                destination.unlink(missing_ok=True)
            raise
    finally:
        if tmp_name is not None:
            tmp_name.unlink(missing_ok=True)
    return {"status": "written", "output": destination.name, **plan}


def _validate_graph(graph: object) -> None:
    """Validate the exact observational contract, including cross-node provenance.

    A matching archive digest does not authenticate arbitrary producer metadata.
    Fail closed instead of returning unvalidated extra strings or path fields.
    """
    if not isinstance(graph, dict) or graph.get("schema_version") != SCHEMA_VERSION:
        raise InputError("Unsupported evidence graph version")
    if (set(graph) != {"schema_version", "status", "nodes", "edges", "missing_evidence", "limitations"}
            or graph.get("status") != "observations-only"):
        raise InputError("Bundle is not an observational evidence graph")
    nodes, edges = graph.get("nodes"), graph.get("edges")
    missing, limitations = graph.get("missing_evidence"), graph.get("limitations")
    if (not isinstance(nodes, list) or not isinstance(edges, list)
            or not isinstance(missing, list) or not isinstance(limitations, list)
            or len(nodes) > 100000 or len(edges) > 200000 or len(missing) > 10000
            or len(limitations) > 100):
        raise InputError("Malformed or excessively large evidence graph")
    def safe_text(value, limit=512):
        return (isinstance(value, str) and len(value) <= limit
                and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value))
    def relative(value):
        return (safe_text(value) and bool(value) and not value.startswith(("/", "~"))
                and "\\" not in value and ":" not in value
                and all(part not in ("", "..") for part in value.split("/"))
                and (value == "." or "." not in value.split("/")))
    by_id, images, artifact_paths = {}, {}, {}
    for item in nodes:
        if not isinstance(item, dict):
            raise InputError("Invalid graph node")
        ident, kind, data = item.get("id"), item.get("type"), item.get("data")
        observed = kind in ("artifact", "kconfig_observation")
        if (set(item) != ({"id", "type", "confidence", "data", "provenance"} if observed
                         else {"id", "type", "confidence", "data"})
                or not isinstance(ident, str) or len(ident) > 256
                or not re.fullmatch(r"[A-Za-z0-9_:@./+-]+", ident) or ident in by_id
                or kind not in ("project", "image", "artifact", "kconfig_observation")
                or not isinstance(data, dict)):
            raise InputError("Invalid or duplicate graph node")
        by_id[ident] = item
        if kind == "project":
            if ident != "project:root" or data != {"identity": "unspecified"} or item["confidence"] != "unknown":
                raise InputError("Invalid project metadata")
            continue
        if kind == "image":
            name = data.get("name")
            if (set(data) != {"name", "artifact_root"} or not isinstance(name, str)
                    or not re.fullmatch(r"[A-Za-z0-9_.-]{1,60}", name) or name in (".", "..")
                    or ident != "image:" + name or not relative(data.get("artifact_root"))
                    or item["confidence"] != "derived"):
                raise InputError("Invalid image metadata")
            images[name] = item
            continue
        evidence = item.get("provenance")
        if (item["confidence"] != "confirmed" or not isinstance(evidence, dict)
                or set(evidence) != {"artifact", "sha256", "producing_tool", "tool_version", "extraction"}
                or not relative(evidence.get("artifact"))
                or not isinstance(evidence.get("sha256"), str)
                or not re.fullmatch(r"[a-f0-9]{64}", evidence["sha256"])
                or evidence.get("producing_tool") != "unknown" or evidence.get("tool_version") != "unknown"
                or evidence.get("extraction") != "read-only SHA-256 file observation"):
            raise InputError("Graph provenance is invalid for this schema version")
        if kind == "kconfig_observation":
            name, value = data.get("name"), data.get("observed_value")
            if (set(data) != {"name", "observed_value", "dependency_reason"}
                    or data.get("dependency_reason") != "unknown"
                    or not isinstance(name, str) or not re.fullmatch(r"CONFIG_[A-Z0-9_]+", name)):
                raise InputError("Invalid Kconfig evidence symbol")
            public_id = name in ("CONFIG_BOARD_TARGET", "CONFIG_SOC", "CONFIG_BOARD", "CONFIG_KERNEL_BIN_NAME")
            valid_value = (
                isinstance(value, str)
                and (value == "<redacted>" or bool(SAFE_VALUE.fullmatch(value))
                     or (public_id and bool(TARGET.fullmatch(value))
                         and not value.startswith(("/", ".", "~"))
                         and ".." not in value.split("/") and not PRIVATE.search(value)))
                and (not PRIVATE.search(name) or public_id or value in ("<redacted>", "n"))
            )
            if not valid_value:
                raise InputError("Unredacted or malformed Kconfig value in imported graph")
        elif (set(data) != {"kind", "bytes"} or not isinstance(data.get("kind"), str) or data.get("kind") not in dict(FILES)
                or type(data.get("bytes")) is not int or not 0 <= data["bytes"] <= MAX_ARTIFACT_BYTES):
            raise InputError("Invalid artifact size/type metadata")
        else:
            if evidence["artifact"] in artifact_paths:
                raise InputError("Duplicate artifact identity")
            artifact_paths[evidence["artifact"]] = item
    if "project:root" not in by_id or not images:
        raise InputError("Missing project or image identity")
    expected_edges = set()
    for name in images:
        expected_edges.add(("project:root", "contains", "image:" + name))
    for item in nodes:
        kind = item["type"]
        if kind == "artifact":
            try:
                prefix, image, artifact_kind = item["id"].split(":")
                expected_path = image + "/" + dict(FILES)[artifact_kind]
            except (ValueError, KeyError) as exc:
                raise InputError("Invalid artifact identity") from exc
            if (prefix != "artifact" or image not in images or artifact_kind != item["data"]["kind"]
                    or expected_path != item["provenance"]["artifact"]):
                raise InputError("Artifact identity/provenance mismatch")
            expected_edges.add(("image:" + image, "contains", item["id"]))
        elif kind == "kconfig_observation":
            try:
                prefix, image, symbol = item["id"].split(":")
            except ValueError as exc:
                raise InputError("Invalid Kconfig identity") from exc
            source = by_id.get("artifact:" + image + ":kconfig")
            if (prefix != "kconfig" or symbol != item["data"]["name"] or source is None
                    or source["provenance"] != item["provenance"]):
                raise InputError("Kconfig provenance does not match its artifact")
            expected_edges.add((source["id"], "observed-in", item["id"]))
    actual_edges = set()
    for edge in edges:
        if (not isinstance(edge, dict) or set(edge) != {"from", "to", "type"}
                or not all(isinstance(edge.get(key), str) for key in ("from", "to", "type"))):
            raise InputError("Malformed evidence relationship")
        identity = (edge["from"], edge["type"], edge["to"])
        if identity in actual_edges:
            raise InputError("Duplicate evidence relationship")
        actual_edges.add(identity)
    if actual_edges != expected_edges:
        raise InputError("Malformed or missing evidence relationship")
    for row in missing:
        if (not isinstance(row, dict) or not isinstance(row.get("image"), str) or row.get("image") not in images
                or row.get("artifact") not in ("zephyr/.config", "zephyr/zephyr.dts", ".zew-built-target")
                or row.get("state") not in ("missing", "conflicting-board-identity")
                or set(row) - {"image", "artifact", "state", "details"}
                or ("details" in row and row["details"] != "Workbench receipt differs from generated CONFIG_BOARD_TARGET")):
            raise InputError("Malformed missing-evidence entry")
    if limitations != list(LIMITATIONS):
        raise InputError("Malformed graph limitation text")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InputError("Duplicate JSON object key")
        result[key] = value
    return result


def inspect(bundle: Path, include_graph: bool = False) -> dict:
    source = Path(bundle)
    if source.is_symlink() or not source.is_file():
        raise InputError("Input bundle must be a regular non-symlink file")
    if source.stat().st_size > MAX_GRAPH_BYTES + MAX_MANIFEST_BYTES + 1024 * 1024:
        raise InputError("Bundle ZIP exceeds size limit")
    try:
        with zipfile.ZipFile(source, "r") as zf:
            infos = zf.infolist()
            names = [item.filename for item in infos]
            if len(names) != 2 or set(names) != ALLOWED or len(names) != len(set(names)):
                raise InputError("Bundle must contain only manifest.json and graph.json, once each")
            for info in infos:
                mode = info.external_attr >> 16
                if (info.flag_bits & 0x1 or info.compress_type != zipfile.ZIP_STORED
                        or info.is_dir() or info.file_size > (MAX_GRAPH_BYTES if info.filename == "graph.json" else MAX_MANIFEST_BYTES)
                        or info.compress_size != info.file_size
                        or stat.S_IFMT(mode) not in (0, stat.S_IFREG)):
                    raise InputError("Unsupported or unsafe archive member")
            raw_manifest = zf.read("manifest.json")
            raw_graph = zf.read("graph.json")
    except (zipfile.BadZipFile, EOFError, RuntimeError, OSError) as exc:
        raise InputError("Invalid or corrupted evidence ZIP") from exc
    try:
        meta = json.loads(raw_manifest, object_pairs_hook=_unique_object)
        graph = json.loads(raw_graph, object_pairs_hook=_unique_object)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise InputError("Bundle JSON is invalid") from exc
    if (not isinstance(meta, dict) or meta.get("format") != FORMAT
            or meta.get("schema_version") != SCHEMA_VERSION
            or meta.get("attestation") != "none"
            or not isinstance(meta.get("entries"), dict)
            or set(meta["entries"]) != {"graph.json"}):
        raise InputError("Unsupported or malformed bundle manifest")
    record = meta["entries"]["graph.json"]
    if (not isinstance(record, dict)
            or not isinstance(record.get("sha256"), str)
            or type(record.get("bytes")) is not int
            or record["sha256"] != hashlib.sha256(raw_graph).hexdigest()
            or record["bytes"] != len(raw_graph)):
        raise InputError("Bundle SHA-256 or size mismatch")
    _validate_graph(graph)
    result = {
        "status": "verified-integrity-only", "format": FORMAT,
        "schema_version": SCHEMA_VERSION, "graph_sha256": record["sha256"],
        "nodes": len(graph["nodes"]), "edges": len(graph["edges"]),
        "attestation": "none",
        "limitations": ["SHA-256 verifies received ZIP content against its manifest, not producer authenticity",
                        "This archive does not establish flashed, debugged or analysed hardware state",
                        "No archive members were extracted or executed"],
    }
    if include_graph:
        # Never return unvalidated raw ZIP bytes: this is the parsed, integrity-
        # checked, sanitised snapshot. It remains untrusted producer-supplied data.
        result["graph"] = graph
    return result
