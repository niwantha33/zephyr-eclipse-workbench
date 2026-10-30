# SPDX-License-Identifier: Apache-2.0
"""Read-only source -> explicit CMake output -> GNU-ld map observations.

A map is generated text. Its entries do NOT authenticate a final ELF, prove
that all compiler commands ran or establish what was flashed on hardware.
"""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION, _digest, _images, _regular, collect
from .compilation import _candidate, _snapshot, _source

MAX_MAP_BYTES = 64 * 1024 * 1024
SECTION = re.compile(r"^\s*(\.[^\s]+)\s+0x[0-9a-fA-F]+\s+0x([0-9a-fA-F]+)\s+(.+?)\s*$")


def _output(row: dict, directory: Path) -> Path | None:
    value = row.get("output")
    if value is None:
        return None
    if not isinstance(value, str) or not value or len(value) > 4096 or "\x00" in value:
        raise InputError("Invalid explicit compiler database output")
    try:
        base = Path(row["directory"])
        path = Path(value)
        if not base.is_absolute():
            return None
        unresolved = path if path.is_absolute() else base / path
        # Do not silently classify an aliased output as a regular build object.
        # Even when it resolves outside the build, it is not safe to assert a
        # source-to-object identity through a symlink.
        if unresolved.is_symlink():
            raise InputError("Symlinked compiler output rejected")
        resolved = unresolved.resolve(strict=False)
        if not resolved.is_relative_to(directory):
            return None
        if resolved.suffix not in (".o", ".obj"):
            return None
        return resolved
    except (OSError, ValueError):
        return None


def _map_snapshot(path: Path, expected: str) -> str:
    before = path.stat()
    if before.st_size > MAX_MAP_BYTES:
        raise InputError("Linker map exceeds 64 MiB parsing limit")
    with path.open("rb") as reader:
        raw = reader.read(MAX_MAP_BYTES + 1)
    after = path.stat()
    if len(raw) > MAX_MAP_BYTES:
        raise InputError("Linker map exceeds 64 MiB parsing limit")
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (
            after.st_size, after.st_mtime_ns, after.st_ino):
        raise InputError("Linker map changed during evidence collection")
    if hashlib.sha256(raw).hexdigest() != expected:
        raise InputError("Linker map differs from Evidence Graph snapshot")
    try:
        return raw.decode("utf-8", "strict")
    except UnicodeError as exc:
        raise InputError("Linker map is not UTF-8; format is unsupported") from exc


def _map_target(token: str, directory: Path) -> Path | None:
    # Do not infer an archive member from a coincidentally matching basename:
    # 'library.a(member.o)' cannot identify a standalone compiled output.
    if "(" in token or ")" in token or not token.endswith((".o", ".obj")):
        return None
    try:
        name = Path(token)
        return (name if name.is_absolute() else directory / name).resolve(strict=False)
    except (OSError, ValueError):
        return None


def _map_observations(map_text: str, directory: Path, obj: Path) -> dict:
    if "Linker script and memory map" not in map_text:
        return {"format": "unsupported-map", "load_entries": 0,
                "assigned_section_entries": 0, "assigned_section_bytes": 0}
    in_layout = False
    loads = entries = count = 0
    for line in map_text.splitlines():
        stripped = line.strip()
        if stripped == "Linker script and memory map":
            in_layout = True
            continue
        if not in_layout:
            continue  # Discarded input sections are not assigned to the layout.
        if stripped.startswith("LOAD "):
            if _map_target(stripped[5:].strip(), directory) == obj:
                loads += 1
            continue
        if len(line) > 8192:
            continue
        m = SECTION.fullmatch(line)
        if m is None:
            continue
        candidate = _map_target(m.group(3), directory)
        if candidate != obj:
            continue
        amount = int(m.group(2), 16)
        # A zero-size input section does not prove any allocated bytes.
        if amount:
            entries += 1
            count += amount
    return {"format": "gnu-ld-text-recognised", "load_entries": loads,
            "assigned_section_entries": entries, "assigned_section_bytes": count}


def inspect(build: Path, source: str, source_root: Path,
            images: list[str] | None = None, graph: dict | None = None) -> dict:
    requested = _source(source_root, source)
    root = Path(build)
    if root.is_symlink() or not root.is_dir():
        raise InputError("--build must be an existing non-symlink directory")
    root = root.resolve(strict=True)
    graph = graph if graph is not None else collect(root, images or [])
    fingerprints = {node["id"]: node["provenance"]["sha256"] for node in graph["nodes"]
                    if node["type"] == "artifact"
                    and node["data"]["kind"] in ("compiler_database", "map")}
    reports = []
    for image, sub in _images(root, images or []):
        directory = sub.resolve(strict=True)
        database_id = f"artifact:{image}:compiler_database"
        map_id = f"artifact:{image}:map"
        database = _regular(directory, "compile_commands.json")
        link_map = _regular(directory, "zephyr/zephyr.map")
        record = {
            "image": image, "status": "unknown",
            "matching_compile_commands": 0, "distinct_declared_outputs": 0,
            "observed_object_sha256": None, "map_observation": None,
            "provenance": {
                "compiler_database": None, "linker_map": None,
            },
            "missing_evidence": [],
        }
        if database is None:
            if database_id in fingerprints:
                raise InputError("Compiler database disappeared after graph snapshot")
            record["missing_evidence"].append("compile_commands.json")
            reports.append(record)
            continue
        rows, database_hash = _snapshot(database)
        if database_hash != fingerprints.get(database_id):
            raise InputError("Compiler database differs from Evidence Graph snapshot")
        record["provenance"]["compiler_database"] = {
            "node_id": database_id, "sha256": database_hash}
        objects = set()
        for row in rows:
            candidate = _candidate(row)
            if candidate == requested:
                record["matching_compile_commands"] += 1
                output = _output(row, directory)
                if output is not None:
                    objects.add(output)
        record["distinct_declared_outputs"] = len(objects)
        if len(objects) != 1:
            record["status"] = ("ambiguous-object-outputs" if len(objects) > 1
                                else "missing-unambiguous-object-output")
            record["missing_evidence"].append("Exactly one explicit CMake compiler output under this image")
            reports.append(record)
            continue
        obj = next(iter(objects))
        rel = obj.relative_to(directory).as_posix()
        obj_path = _regular(directory, rel)
        if obj_path is None:
            record["status"] = "declared-object-not-found"
            record["missing_evidence"].append("Existing compiler output object")
            reports.append(record)
            continue
        obj_hash, obj_bytes = _digest(obj_path)
        record["observed_object_sha256"] = obj_hash
        record["observed_object_bytes"] = obj_bytes
        if link_map is None:
            if map_id in fingerprints:
                raise InputError("Linker map disappeared after graph snapshot")
            record["status"] = "object-observed-map-missing"
            record["missing_evidence"].append("zephyr/zephyr.map")
            reports.append(record)
            continue
        map_hash = fingerprints.get(map_id)
        if map_hash is None:
            raise InputError("Linker map missing from Evidence Graph")
        record["provenance"]["linker_map"] = {"node_id": map_id, "sha256": map_hash}
        observation = _map_observations(_map_snapshot(link_map, map_hash), directory, obj)
        record["map_observation"] = observation
        record["status"] = ("map-section-contribution-observed"
                            if observation["assigned_section_entries"] else
                            "map-load-observed" if observation["load_entries"] else
                            "unknown-map-contribution")
        if observation["format"] == "unsupported-map":
            record["status"] = "unsupported-map-format"
        reports.append(record)
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "linker-map-object-observations",
        "query": {"selection": "exact local source; explicit CMake output", "name": "<not-exported>"},
        "images": reports,
        "limitations": [
            "An observed object hash proves local bytes only, not that a compiler just produced them.",
            "GNU-ld map assigned sections are textual linker observations, not independent ELF verification.",
            "LOAD alone is not evidence of an allocated section or final linked contribution.",
            "Archive members, discarded-only sections and unsupported linker maps remain unknown.",
            "Missing or ambiguous CMake outputs never trigger basename guesses.",
            "Linker map and database hashes do not establish build freshness, target identity, or flashing.",
            "No command arguments, source names, paths or map text are exported.",
        ],
    }
