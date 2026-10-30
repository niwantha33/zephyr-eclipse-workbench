# SPDX-License-Identifier: Apache-2.0
"""Offline evidence bundle: sanitised graph only, tamper-evident, never extract ZIP.

This is a Phase-0 feasibility package, not proof of provenance or authenticity.
Hash checks detect corruption, not a malicious producer who can rewrite both.
"""
from __future__ import annotations
import hashlib
import json
import os
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path
from .collector import InputError, SCHEMA_VERSION

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
            # FileExistsError must never delete a file another process just
            # created between our preliminary checks and exclusive open.
            with destination.open("xb") as target:
                created = True
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


def inspect(bundle: Path) -> dict:
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
                        or info.compress_size != info.file_size or (mode and not stat.S_ISREG(mode))):
                    raise InputError("Unsupported or unsafe archive member")
            raw_manifest = zf.read("manifest.json")
            raw_graph = zf.read("graph.json")
    except (zipfile.BadZipFile, EOFError, RuntimeError, OSError) as exc:
        raise InputError("Invalid or corrupted evidence ZIP") from exc
    try:
        meta = json.loads(raw_manifest)
        graph = json.loads(raw_graph)
    except (ValueError, UnicodeError) as exc:
        raise InputError("Bundle JSON is invalid") from exc
    if (not isinstance(meta, dict) or meta.get("format") != FORMAT
            or meta.get("schema_version") != SCHEMA_VERSION
            or meta.get("attestation") != "none"
            or set(meta.get("entries", {})) != {"graph.json"}):
        raise InputError("Unsupported or malformed bundle manifest")
    record = meta["entries"]["graph.json"]
    if (record.get("sha256") != hashlib.sha256(raw_graph).hexdigest()
            or record.get("bytes") != len(raw_graph)):
        raise InputError("Bundle SHA-256 or size mismatch")
    if (not isinstance(graph, dict) or graph.get("schema_version") != SCHEMA_VERSION
            or graph.get("status") != "observations-only"
            or not isinstance(graph.get("nodes"), list)
            or not isinstance(graph.get("edges"), list)):
        raise InputError("Not a supported Zephyr evidence graph")
    return {
        "status": "verified-integrity-only", "format": FORMAT,
        "schema_version": SCHEMA_VERSION, "graph_sha256": record["sha256"],
        "nodes": len(graph["nodes"]), "edges": len(graph["edges"]),
        "attestation": "none",
        "limitations": ["SHA-256 verifies received ZIP content against its manifest, not producer authenticity",
                        "This archive does not establish flashed, debugged or analysed hardware state",
                        "No archive members were extracted or executed"],
    }
