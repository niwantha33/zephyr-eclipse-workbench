#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only HWMv2 board/SoC metadata from the *selected* Zephyr checkout.

Uses its list_boards/list_hardware resolver, never a local reimplementation of
Zephyr's HWMv2 rules. No generated-DTS, build, Kconfig or hardware claims.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import io
import os
from pathlib import Path
import sys

from yaml_observe_file import SourceRejected, read_selected
from zew_evidence.yaml_observation import observe_yaml

MAX_ROWS = 256
MAX_FIELD_CHARS = 256
MAX_API_BYTES = 128 * 1024
EMPTY_DIGEST = "0" * 64


class HwmError(Exception):
    """A stable reason code, never untrusted YAML or an absolute path."""


def field(value):
    return str(value if value is not None else "")[:MAX_FIELD_CHARS].replace("\r", " ").replace("\n", " ")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checked_api(path):
    if any(item.is_symlink() for item in (path, *path.parents)) or not path.is_file():
        raise HwmError("selected-hwm-api-unavailable")
    with path.open("rb") as handle:
        before = os.fstat(handle.fileno())
        if before.st_size > MAX_API_BYTES:
            raise HwmError("selected-hwm-api-too-large")
        source = handle.read(MAX_API_BYTES + 1)
        after = os.fstat(handle.fileno())
    if len(source) > MAX_API_BYTES or (before.st_size, before.st_mtime_ns) != (
            after.st_size, after.st_mtime_ns):
        raise HwmError("selected-hwm-api-changed")
    return source


def source_type(relative):
    parts = relative.replace("\\", "/").split("/")
    if len(parts) >= 4 and parts[0] == "boards" and parts[-1] == "board.yml":
        return "board"
    if len(parts) >= 4 and parts[0] == "soc" and parts[-1] == "soc.yml":
        return "soc"
    return "none"


def paths_match(folders, directory):
    for item in folders or []:
        try:
            if Path(item).absolute().resolve(strict=True) == directory.resolve(strict=True):
                return True
        except (OSError, ValueError):
            continue
    return False


def inspect(root_text, relative, source_root, zephyr_text, application_text):
    root, zephyr, application = (Path(x).absolute() for x in
                                (root_text, zephyr_text, application_text))
    if source_root not in ("zephyr", "application"):
        raise HwmError("invalid-source-root")
    expected = zephyr if source_root == "zephyr" else application
    if root.resolve(strict=True) != expected.resolve(strict=True):
        raise HwmError("selected-root-mismatch")
    original = read_selected(str(root), relative, source_root)
    source_sha = sha(original)
    kind = source_type(relative)
    if kind == "none":
        return ("not-hwmv2", "not-canonical-hwmv2-metadata", source_sha, "none",
                EMPTY_DIGEST, [])
    syntax = observe_yaml(original, path=relative, source_root=source_root)
    if syntax["status"] != "parsed":
        raise HwmError("unsafe-or-invalid-yaml-source")
    api_files = (zephyr / "scripts/list_boards.py", zephyr / "scripts/list_hardware.py")
    api_contents = [checked_api(f) for f in api_files]
    api_sha = sha(b"".join(api_contents))
    selected_file = root.joinpath(*relative.replace("\\", "/").split("/"))
    selected_dir = selected_file.parent.resolve(strict=True)
    import importlib
    # Suppress upstream errors that may echo user paths or literal YAML values.
    # The Python interpreter is an isolated, bounded subprocess from Java.
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        sys.path.insert(0, str(zephyr / "scripts"))
        try:
            list_hardware = importlib.import_module("list_hardware")
            list_boards = importlib.import_module("list_boards")
            args = argparse.Namespace(soc_roots=[zephyr, application],
                                      board_roots=[zephyr, application],
                                      board_dir=[], board=None)
            systems = list_hardware.find_v2_systems(
                argparse.Namespace(soc_roots=args.soc_roots))
            rows = []
            socs_by_name = {s.name: s for s in systems.get_socs()}

            def add(code, *values):
                if len(rows) >= MAX_ROWS:
                    raise HwmError("too-many-hwmv2-rows")
                rows.append((code, *(field(v) for v in values)))

            if kind == "board":
                # Work through Zephyr's effective board catalogue: this
                # includes board extensions and board/SoC qualifiers.
                boards = list_boards.find_v2_boards(args)
                selected = [b for b in boards.values()
                            if b.hwm == "v2"
                            and paths_match(b.directories, selected_dir)]
                if not selected:
                    raise HwmError("selected-board-not-resolved")
                if len(selected) > 32:
                    raise HwmError("too-many-selected-boards")
                for board in sorted(selected, key=lambda b: b.name):
                    add("B", board.name, board.full_name, board.vendor,
                        board.revision_format, board.revision_default,
                        "yes" if board.revision_exact else "no")
                    for revision in board.revisions:
                        add("R", board.name, revision.name)
                    for qualifier in list_boards.board_v2_qualifiers(board):
                        add("Q", board.name, qualifier)
                    for soc in board.socs:
                        full = socs_by_name.get(soc.name)
                        if full is None:
                            raise HwmError("board-soc-not-resolved")
                        add("S", soc.name, full.family, full.series,
                            ",".join(full.cpuclusters))
            else:
                selected = [soc for soc in systems.get_socs()
                            if paths_match(soc.folder, selected_dir)]
                if not selected:
                    # HWMv2 soc extensions may not yield an own SoC; do
                    # not mistake a standalone fragment for a complete SoC.
                    raise HwmError("selected-soc-not-resolved")
                for soc in sorted(selected, key=lambda s: s.name):
                    add("S", soc.name, soc.family, soc.series,
                        ",".join(soc.cpuclusters))
        finally:
            sys.path.pop(0)

    if source_sha != sha(read_selected(str(root), relative, source_root)):
        raise HwmError("source-changed-during-resolution")
    if api_sha != sha(b"".join(checked_api(f) for f in api_files)):
        raise HwmError("hwm-resolver-changed")
    return ("resolved", "selected-upstream-hwmv2", source_sha, kind, api_sha, rows)


def encoded(value):
    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")


def output(result):
    state, reason, source_sha, kind, api_sha, rows = result
    print("ZEW-HWMV2-1")
    print("H\t" + "\t".join(encoded(x) for x in
                        (state, reason, source_sha, kind, api_sha)))
    for row in rows:
        print(row[0] + "\t" + "\t".join(encoded(x) for x in row[1:]))


def main(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False)
    for flag in ("root", "relative", "source-root", "zephyr", "application"):
        parser.add_argument("--" + flag, required=True)
    args = parser.parse_args(argv)
    try:
        result = inspect(args.root, args.relative, args.source_root,
                         args.zephyr, args.application)
    except (SourceRejected, HwmError) as problem:
        result = ("unavailable", str(problem), EMPTY_DIGEST, "none",
                  EMPTY_DIGEST, [])
    except (Exception, SystemExit, RecursionError):
        # Never echo upstream errors; they may contain paths and YAML values.
        result = ("unavailable", "selected-hwmv2-api-unavailable-or-rejected",
                  EMPTY_DIGEST, "none", EMPTY_DIGEST, [])
    output(result)


if __name__ == "__main__":
    main()
