# SPDX-License-Identifier: Apache-2.0
"""Opt-in, bounded Zephyr CMake Devicetree verification in an isolated scratch build.

This invokes the real selected Zephyr west/CMake configuration. Unlike edtlib
preview it allows preprocessor directives and module bindings. It never flashes,
never runs Ninja to compile firmware, and never overwrites the selected build.
The application is copied first. Custom CMake code is executable; opt in only.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from .collector import InputError, SCHEMA_VERSION, _digest

MAX_INPUT_BYTES = 80 * 1024 * 1024
MAX_FILES = 3000
MAX_CONFIG_BYTES = 8 * 1024 * 1024
MAX_LOG_BYTES = 1024 * 1024
_BOARD = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.@/+~-]{0,159}$")
_CACHE = re.compile(r"^([A-Za-z_][A-Za-z_0-9]*):[A-Z_]+=(.*)$")
_SKIP_DIRS = {".git", ".west", ".zew-build", ".venv", "__pycache__",
              "node_modules", ".cache", "build"}
_SKIP_FILES = {".env", ".env.local", ".env.production"}
_SENSITIVE_SUFFIXES = (".pem", ".key", ".p12", ".pfx", ".jks", ".pyc")


def _canonical(path: Path, kind: str) -> Path:
    path = Path(path).absolute()
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise InputError("Symlinked " + kind + " paths are not supported")
    if kind == "file" and not path.is_file():
        raise InputError("Input file not found")
    if kind == "directory" and not path.is_dir():
        raise InputError("Input directory not found")
    return path.resolve(strict=True)


def _cache(path: Path) -> dict[str, str]:
    source = _canonical(path, "file")
    if source.stat().st_size > MAX_CONFIG_BYTES:
        raise InputError("CMake cache exceeds 8 MiB")
    result = {}
    for line in source.read_text(encoding="utf-8", errors="replace").splitlines():
        match = _CACHE.fullmatch(line)
        if match:
            result[match.group(1)] = match.group(2)
    return result


def _snapshot(application: Path, destination: Path, exclude_build: Path) -> tuple[int, str]:
    """Copy bounded application inputs; refuse source symlinks or large content."""
    total = 0
    files = 0
    digest = hashlib.sha256()
    for current, dirs, names in os.walk(application, topdown=True, followlinks=False):
        relative = Path(current).relative_to(application)
        kept = []
        for name in sorted(dirs):
            source = Path(current) / name
            if source.is_symlink():
                raise InputError("Symlink inside application source")
            if name in _SKIP_DIRS or source == exclude_build or source.is_relative_to(exclude_build):
                continue
            kept.append(name)
        dirs[:] = kept
        for name in sorted(names):
            source = Path(current) / name
            if source.is_symlink():
                raise InputError("Symlink inside application source")
            if (name in _SKIP_FILES or name.lower().endswith(_SENSITIVE_SUFFIXES)
                    or source == exclude_build or source.is_relative_to(exclude_build)):
                continue
            if not source.is_file():
                raise InputError("Non-regular application source")
            files += 1
            total += source.stat().st_size
            if files > MAX_FILES or total > MAX_INPUT_BYTES:
                raise InputError("Application source exceeds bounded scratch-copy limit")
            destination_file = destination / relative / name
            destination_file.parent.mkdir(parents=True, exist_ok=True)
            before = source.stat()
            with source.open("rb") as reader, destination_file.open("xb") as writer:
                while block := reader.read(65536):
                    writer.write(block)
                    digest.update(block)
            after = source.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise InputError("Application source changed during snapshot")
            digest.update((relative / name).as_posix().encode("utf-8"))
    if files == 0 or not (destination / "CMakeLists.txt").is_file():
        raise InputError("An application CMakeLists.txt is required for a real Zephyr configuration")
    return files, digest.hexdigest()


def _reason(log: Path, overlay: bool) -> dict:
    """Only fixed reason codes and bounded source-line numbers leave the scratch."""
    size = log.stat().st_size if log.is_file() else 0
    with log.open("rb") as stream:
        if size > MAX_LOG_BYTES:
            stream.seek(-MAX_LOG_BYTES, os.SEEK_END)
        data = stream.read(MAX_LOG_BYTES).decode("utf-8", errors="replace").lower()
    if "undefined node label" in data or "undefined node" in data:
        kind = "undefined-devicetree-node-or-label"
    elif "required property" in data or "missing required" in data:
        kind = "required-binding-property"
    elif "devicetree error" in data or "parse error" in data or "syntax error" in data:
        kind = "devicetree-syntax-or-preprocessing"
    elif "kconfig" in data and ("error" in data or "aborting" in data):
        kind = "kconfig-configuration"
    elif "toolchain" in data or "not found" in data or "could not find" in data:
        kind = "missing-tool-or-module"
    else:
        kind = "cmake-configure-failed"
    # Do not leak user source text, host paths, secrets or free-form CMake errors.
    return {"code": kind, "diagnostic_classification": "heuristic",
            "log_truncated": size > MAX_LOG_BYTES, "source_scope":
            "candidate-or-source" if overlay else "source-or-toolchain"}


def validate(build: Path, application: Path, zephyr: Path, python: Path, board: str,
             overlay: Path | None = None, ninja: Path | None = None,
             timeout: int = 300, scratch_parent: Path | None = None) -> dict:
    if not _BOARD.fullmatch(board) or ".." in board:
        raise InputError("Invalid selected board target")
    if not 30 <= timeout <= 900:
        raise InputError("Timeout must be between 30 and 900 seconds")
    build = _canonical(build, "directory")
    application = _canonical(application, "directory")
    zephyr = _canonical(zephyr, "directory")
    python = _canonical(python, "file")
    ninja = _canonical(ninja, "file") if ninja is not None else None
    if scratch_parent is not None:
        scratch_parent = _canonical(scratch_parent, "directory")
        if scratch_parent == application or scratch_parent.is_relative_to(application):
            raise InputError("Scratch parent must be outside the application")
        if scratch_parent == build or scratch_parent.is_relative_to(build):
            raise InputError("Scratch parent must be outside the original build")
    if not (zephyr / "CMakeLists.txt").is_file():
        raise InputError("Selected Zephyr checkout has no CMakeLists.txt")
    if build == application or not (application / "CMakeLists.txt").is_file():
        raise InputError("Original application and build must be separate")
    source_cache = _cache(build / "CMakeCache.txt")
    if not source_cache.get("ZEPHYR_BASE"):
        raise InputError("Existing selected build has no ZEPHYR_BASE")
    try:
        if Path(source_cache["ZEPHYR_BASE"]).resolve(strict=True) != zephyr:
            raise InputError("Build checkout does not match --zephyr")
    except (OSError, ValueError) as exc:
        raise InputError("Build checkout cannot be verified") from exc
    target = source_cache.get("BOARD_TARGET")
    selected = source_cache.get("BOARD")
    if target and target != board:
        raise InputError("Selected board differs from existing build target")
    if not target and selected and selected not in (board, board.split("/")[0]):
        raise InputError("Selected board differs from existing build board")
    if not target and not selected:
        raise InputError("Existing build has no verifiable BOARD/BOARD_TARGET")
    original = build / "zephyr" / "zephyr.dts"
    if not original.is_file():
        raise InputError("Configure the original selected build before validation")
    original = _canonical(original, "file")
    original_sha, _ = _digest(original)
    candidate = None
    candidate_sha = None
    candidate_rel = None
    if overlay is not None:
        candidate = _canonical(overlay, "file")
        if not candidate.is_relative_to(application):
            raise InputError("Candidate overlay must be inside the selected application")
        if candidate.stat().st_size > 64 * 1024:
            raise InputError("Candidate overlay exceeds 64 KiB")
        candidate_rel = candidate.relative_to(application)
        candidate_sha, _ = _digest(candidate)
    excluded = build
    # Only the environment explicitly supplied by caller is inherited; never
    # install providers, fetch modules, rewrite west config or probe hardware.
    with tempfile.TemporaryDirectory(prefix="zew-real-dt-", dir=scratch_parent) as scratch_name:
        scratch = Path(scratch_name)
        source = scratch / "application"
        source.mkdir()
        file_count, source_sha = _snapshot(application, source, excluded)
        isolated_build = scratch / "build"
        command = [str(python), "-m", "west", "build", "--cmake-only",
                   "--pristine=always", "-b", board, "-d", str(isolated_build),
                   str(source), "--"]
        command.extend(["-DPython3_EXECUTABLE:FILEPATH=" + str(python)])
        if ninja is not None:
            command.append("-DCMAKE_MAKE_PROGRAM:FILEPATH=" + str(ninja))
        if candidate_rel is not None:
            # An ADDITIONAL overlay with highest priority, not a replacement
            # for existing automatically discovered board/app overlays.
            command.append("-DEXTRA_DTC_OVERLAY_FILE:STRING="
                           + (source / candidate_rel).as_posix())
        log = scratch / "configure.log"
        try:
            env = os.environ.copy()
            env["ZEPHYR_BASE"] = str(zephyr)
            with log.open("wb") as output:
                process = subprocess.run(command, cwd=zephyr.parent, env=env,
                                         stdin=subprocess.DEVNULL, stdout=output,
                                         stderr=subprocess.STDOUT, timeout=timeout,
                                         check=False)
            exit_code = process.returncode
        except subprocess.TimeoutExpired:
            exit_code = None
        except OSError:
            exit_code = None
        outcome = "configure-failed"
        explanation = _reason(log, candidate is not None)
        scratch_dts = isolated_build / "zephyr" / "zephyr.dts"
        output_sha = None
        if exit_code == 0 and scratch_dts.is_file():
            output_sha, _ = _digest(scratch_dts)
            actual = _cache(isolated_build / "CMakeCache.txt")
            try:
                same_zephyr = Path(actual.get("ZEPHYR_BASE", "")).resolve(strict=True) == zephyr
            except (OSError, ValueError):
                same_zephyr = False
            same_board = (actual.get("BOARD_TARGET", board) == board
                          and actual.get("BOARD", board.split("/")[0])
                          in (board, board.split("/")[0]))
            overlay_in_cache = (candidate_rel is None or
                                (source / candidate_rel).as_posix()
                                in actual.get("EXTRA_DTC_OVERLAY_FILE", "").replace("\\", "/"))
            if same_zephyr and same_board and overlay_in_cache:
                outcome = "configured-in-scratch"
                explanation = {"code": "real-zephyr-cmake-accepted",
                               "diagnostic_classification": "observed"}
            else:
                outcome = "identity-unverified"
                explanation = {"code": "scratch-build-identity-or-overlay-unverified",
                               "diagnostic_classification": "observed"}
        if exit_code is None:
            outcome = "unavailable"
            explanation = {"code": "configure-timeout-or-launch-failed",
                           "diagnostic_classification": "observed"}
    if _digest(original)[0] != original_sha or (candidate is not None and
                                                _digest(candidate)[0] != candidate_sha):
        outcome = "input-changed"
        explanation = {"code": "source-or-build-changed-during-check",
                       "diagnostic_classification": "observed"}
    return {
        "schema_version": SCHEMA_VERSION,
        "status": outcome,
        "evidence_level": "configured" if outcome == "configured-in-scratch" else "unknown",
        "selected_build_dts_sha256": original_sha,
        "scratch_generated_dts_sha256": output_sha,
        "scratch_dts_bytes_identical": output_sha == original_sha if output_sha else None,
        "application_snapshot_sha256": source_sha,
        "application_snapshot_files": file_count,
        "overlay": {"additional": candidate is not None, "sha256": candidate_sha,
                    "mode": "EXTRA_DTC_OVERLAY_FILE (highest priority)" if candidate else "normal discovery"},
        "diagnostic": explanation,
        "limitations": [
            "Configuration ran Zephyr's west/CMake in disposable scratch, not the selected build.",
            "Application inputs were copied; absolute paths and custom CMake hooks can depend on the original environment.",
            "A successful CMake configure does not establish firmware compilation, flash, runtime, or electrical pin safety.",
            "Adding EXTRA_DTC_OVERLAY_FILE does not prove this overlay was already included by the original build.",
            "Kconfig, Devicetree and module resolution depend on the caller's pinned workspace/toolchain environment.",
            "Custom CMake code executes during configuration; only use trusted application and Zephyr workspaces.",
            "Only bounded, sanitized diagnostic classes are exported; no raw stdout, paths or private source.",
        ],
    }
