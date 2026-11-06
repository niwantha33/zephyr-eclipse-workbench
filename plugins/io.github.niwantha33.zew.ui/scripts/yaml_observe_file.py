#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read exactly one selected YAML source for Step 6's native Eclipse view.

No network, no shell, no file writes. Both root and relative filename are
explicit inputs from the selected, managed workbench project context.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
import sys

from zew_evidence.yaml_classification import classify_yaml_path
from zew_evidence.yaml_observation import MAX_SOURCE_BYTES, observe_yaml


class SourceRejected(Exception):
    """Expected source validation failure; never contains the source text."""


def read_selected(root_text: str, relative: str, source_root: str) -> bytes:
    classification = classify_yaml_path(relative, source_root=source_root)
    if classification.kind in ("unknown", "unsupported"):
        raise SourceRejected("unsupported-or-unsafe-yaml-path")
    parts = relative.replace("\\", "/").split("/")
    try:
        root = Path(root_text).absolute()
        if not root.is_dir() or root.is_symlink():
            raise SourceRejected("unavailable-source-root")
        root = root.resolve(strict=True)
        selected = root
        for component in parts:
            selected = selected / component
            if selected.is_symlink():
                raise SourceRejected("symlinked-source-not-allowed")
        if not selected.is_file() or not selected.resolve(strict=True).is_relative_to(root):
            raise SourceRejected("source-not-within-root")
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(selected, flags)
        with os.fdopen(fd, "rb") as source:
            before = os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_SOURCE_BYTES:
                raise SourceRejected("oversized-or-nonregular-yaml-source")
            snapshot = source.read(MAX_SOURCE_BYTES + 1)
            after = os.fstat(source.fileno())
        if len(snapshot) > MAX_SOURCE_BYTES:
            raise SourceRejected("oversized-yaml-source")
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise SourceRejected("source-changed-during-reading")
        if (not selected.is_file() or selected.is_symlink()
                or not selected.resolve(strict=True).is_relative_to(root)):
            raise SourceRejected("source-changed-during-reading")
        return snapshot
    except SourceRejected:
        raise
    except (OSError, ValueError, RuntimeError) as error:
        raise SourceRejected("source-unavailable-or-unsafe") from error


def main(argv=None) -> int:
    args = argparse.ArgumentParser(description="Read-only bounded Zephyr YAML observation")
    args.add_argument("--root", required=True)
    args.add_argument("--relative", required=True)
    args.add_argument("--source-root", required=True,
                      choices=("zephyr", "module", "application"))
    request = args.parse_args(argv)
    try:
        snapshot = read_selected(request.root, request.relative, request.source_root)
        report = observe_yaml(snapshot, path=request.relative, source_root=request.source_root)
        print(json.dumps(report, ensure_ascii=True, separators=(",", ":")))
        return 0
    except SourceRejected as error:
        # Stable machine reason only. Never print input YAML or an absolute path.
        print(error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
