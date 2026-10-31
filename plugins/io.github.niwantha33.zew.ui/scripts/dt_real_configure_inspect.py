#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Installed SWT adapter for genuine, opt-in Zephyr configuration evidence.

Uses the selected managed Python/West and selected Zephyr's dtlib. Includes
no dependency on a Git checkout of Workbench or on zew_evidence being installed.
Never unpickles generated files or reports raw CMake logs/source contents.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory

_NODE = re.compile(r"/(?:[A-Za-z0-9,._+@-]+(?:/[A-Za-z0-9,._+@-]+)*)?")
_PROPERTY = re.compile(r"[A-Za-z][A-Za-z0-9,._+?-]{0,99}")
_BOARD = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.@/+~-]{0,159}")
_CACHE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*):[A-Z_]+=(.*)")
_SKIP_DIRS = {".zew-build", ".git", ".west", ".venv", "__pycache__",
              "node_modules", "build", ".cache"}
_SKIP_FILES = {".env", ".env.local", ".env.production"}
_SECRET_EXT = (".pem", ".key", ".p12", ".pfx", ".jks", ".pyc")
MAX_FILES = 3000
MAX_TOTAL = 80 * 1024 * 1024
MAX_LOG = 1024 * 1024
ZERO = "0" * 64


class Rejected(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


def canonical(path, file=False):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise Rejected("unsafe-input")
    if not (path.is_file() if file else path.is_dir()):
        raise Rejected("missing-input")
    return path.resolve(strict=True)


def bounded(path, limit):
    if not path.is_file() or path.stat().st_size > limit:
        raise Rejected("oversized-or-missing-input")
    before = path.stat()
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    after = path.stat()
    if len(data) > limit or (before.st_size, before.st_mtime_ns) != (
            after.st_size, after.st_mtime_ns):
        raise Rejected("input-changed")
    return data


def digest(path):
    return hashlib.sha256(bounded(path, 4 * 1024 * 1024)).hexdigest()


def cmake_cache(path):
    contents = bounded(path, 8 * 1024 * 1024).decode("utf-8", "replace")
    return {m.group(1): m.group(2) for line in contents.splitlines()
            if (m := _CACHE.fullmatch(line)) is not None}


def identity(cache, zephyr, board):
    try:
        checkout = Path(cache["ZEPHYR_BASE"]).resolve(strict=True)
    except (OSError, KeyError, ValueError):
        return False
    target = cache.get("CACHED_BOARD") or cache.get("BOARD_TARGET")
    if not target:
        selected = cache.get("BOARD", "")
        target = selected if selected == board else None
    return checkout == zephyr and target == board


def copy_application(app, target):
    files, total = 0, 0
    original = hashlib.sha256()
    for directory, children, names in os.walk(app, topdown=True, followlinks=False):
        current = Path(directory)
        children[:] = [child for child in sorted(children)
                       if child not in _SKIP_DIRS]
        for child in children:
            if (current / child).is_symlink():
                raise Rejected("unsafe-input")
        for name in sorted(names):
            path = current / name
            if path.is_symlink() or not path.is_file():
                raise Rejected("unsafe-input")
            if name in _SKIP_FILES or name.lower().endswith(_SECRET_EXT):
                continue
            files += 1
            total += path.stat().st_size
            if files > MAX_FILES or total > MAX_TOTAL:
                raise Rejected("snapshot-too-large")
            relative = path.relative_to(app)
            output = target / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            before = path.stat()
            with path.open("rb") as reader, output.open("xb") as writer:
                while block := reader.read(65536):
                    writer.write(block)
                    original.update(block)
            after = path.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise Rejected("input-changed")
            original.update(relative.as_posix().encode("utf-8"))
    if not (target / "CMakeLists.txt").is_file():
        raise Rejected("missing-application-cmake")
    return files, original.hexdigest()


def generated_node(zephyr, source, path, prop):
    # This fresh worker has no already-imported devicetree modules.
    libs = zephyr / "scripts/dts/python-devicetree/src"
    if not (libs / "devicetree/dtlib.py").is_file():
        return "unknown", "unknown"
    sys.path.insert(0, str(libs))
    try:
        from devicetree import dtlib
        try:
            node = dtlib.DT(str(source)).get_node(path)
        except Exception:
            return "not-observed", "not-observed" if prop else "not-requested"
        if prop is None:
            return "observed", "not-requested"
        return "observed", "observed" if prop in node.props else "not-observed"
    except Exception:
        return "unknown", "unknown"
    finally:
        sys.path.remove(str(libs))


def log_reason(path, overlay):
    data = bounded(path, MAX_LOG).decode("utf-8", "replace").lower()
    if "undefined node label" in data or "undefined node" in data:
        reason = "unknown-node-or-label"
    elif "required property" in data or "missing required" in data:
        reason = "missing-binding-property"
    elif "devicetree error" in data or "parse error" in data or "syntax error" in data:
        reason = "devicetree-parse-error"
    elif "kconfig" in data and ("error" in data or "aborting" in data):
        reason = "kconfig-error"
    elif "toolchain" in data or "not found" in data or "could not find" in data:
        reason = "tool-or-module-unavailable"
    else:
        reason = "cmake-configure-failed"
    hint = 0
    if overlay is not None:
        m = re.search(re.escape(overlay.name.lower())
                      + r":(\d{1,6})(?::\d{1,6})?(?!\d)", data)
        if m and 0 < int(m.group(1)) <= 100000:
            hint = int(m.group(1))
    return reason, hint


def assess(args):
    if not _BOARD.fullmatch(args.board) or ".." in args.board:
        raise Rejected("invalid-board")
    if not _NODE.fullmatch(args.node) or len(args.node) > 240:
        raise Rejected("invalid-node")
    if args.property and not _PROPERTY.fullmatch(args.property):
        raise Rejected("invalid-property")
    app, build = canonical(args.application), canonical(args.build)
    zephyr = canonical(args.zephyr)
    if not (zephyr / "CMakeLists.txt").is_file():
        raise Rejected("missing-zephyr-checkout")
    if not (app / "CMakeLists.txt").is_file() or app == build:
        raise Rejected("invalid-application-build")
    original = canonical(build / "zephyr/zephyr.dts", file=True)
    old_sha = digest(original)
    if not identity(cmake_cache(build / "CMakeCache.txt"), zephyr, args.board):
        raise Rejected("selected-build-identity-mismatch")
    overlay = None
    overlay_sha = ZERO
    if args.overlay is not None:
        overlay = canonical(args.overlay, file=True)
        if not overlay.is_relative_to(app):
            raise Rejected("overlay-outside-application")
        overlay_sha = hashlib.sha256(bounded(overlay, 65536)).hexdigest()
    if args.ninja:
        canonical(args.ninja, file=True)
    with TemporaryDirectory(prefix="zew-studio-real-dt-") as directory:
        scratch = Path(directory)
        source = scratch / "app"
        source.mkdir()
        count, _ = copy_application(app, source)
        newbuild = scratch / "build"
        cmd = [sys.executable, "-m", "west", "build", "--cmake-only",
               "--pristine=always", "-b", args.board, "-d", str(newbuild),
               str(source), "--",
               "-DPython3_EXECUTABLE:FILEPATH=" + sys.executable]
        if args.ninja:
            cmd.append("-DCMAKE_MAKE_PROGRAM:FILEPATH=" + str(args.ninja))
        candidate = None
        if overlay is not None:
            candidate = source / overlay.relative_to(app)
            cmd.append("-DEXTRA_DTC_OVERLAY_FILE:STRING=" + candidate.as_posix())
        log = scratch / "west-configure.log"
        try:
            env = os.environ.copy()
            env["ZEPHYR_BASE"] = str(zephyr)
            with log.open("wb") as output:
                result = subprocess.run(cmd, cwd=zephyr.parent, env=env,
                                        stdin=subprocess.DEVNULL,
                                        stdout=output, stderr=subprocess.STDOUT,
                                        timeout=300, check=False)
        except (OSError, subprocess.TimeoutExpired):
            return ("unavailable", "configure-timeout-or-launch-failed", old_sha,
                    ZERO, "unknown", "unknown", "additional" if overlay else "normal",
                    overlay_sha, count, 0, "unknown")
        generated = newbuild / "zephyr/zephyr.dts"
        if result.returncode != 0 or not generated.is_file():
            reason, hint = log_reason(log, overlay)
            return ("failed", reason, old_sha, ZERO, "unknown", "unknown",
                    "additional" if overlay else "normal", overlay_sha, count, hint, "heuristic")
        try:
            scratch_cache = cmake_cache(newbuild / "CMakeCache.txt")
            configured = identity(scratch_cache, zephyr, args.board)
            if candidate is not None:
                configured = (configured and candidate.as_posix() in
                              scratch_cache.get("EXTRA_DTC_OVERLAY_FILE", "").replace("\\", "/"))
            new_sha = digest(generated)
        except (OSError, Rejected):
            configured, new_sha = False, ZERO
        if not configured:
            return ("unavailable", "scratch-identity-unverified", old_sha, new_sha,
                    "unknown", "unknown", "additional" if overlay else "normal",
                    overlay_sha, count, 0, "observed")
        observed, property_state = generated_node(zephyr, generated, args.node, args.property)
        status = ("configured" if observed == "observed"
                  and property_state in ("observed", "not-requested") else "incomplete")
        reason = ("real-cmake-configured" if status == "configured"
                  else "node-or-property-unverified")
    if digest(original) != old_sha or (overlay is not None and
                                      hashlib.sha256(bounded(overlay, 65536)).hexdigest()
                                      != overlay_sha):
        return ("unavailable", "input-changed", old_sha, ZERO,
                "unknown", "unknown", "additional" if overlay else "normal",
                overlay_sha, 0, 0, "observed")
    return (status, reason, old_sha, new_sha, observed, property_state,
            "additional" if overlay else "normal", overlay_sha, count, 0, "observed")


def wire(fields):
    print("ZEW-REAL-DT-1")
    print("\t".join(base64.b64encode(str(field).encode("ascii")).decode("ascii")
                    for field in fields))


def main():
    parser = argparse.ArgumentParser()
    for name in ("build", "application", "zephyr", "board", "node"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--property")
    parser.add_argument("--overlay")
    parser.add_argument("--ninja")
    args = parser.parse_args()
    try:
        result = assess(args)
    except (OSError, Rejected, UnicodeError, ValueError):
        result = ("unavailable", "invalid-or-missing-input", ZERO, ZERO, "unknown",
                  "unknown", "additional" if args.overlay else "normal", ZERO, 0, 0, "observed")
    wire(result)
    return 0 if result[0] == "configured" else 1


if __name__ == "__main__":
    raise SystemExit(main())
