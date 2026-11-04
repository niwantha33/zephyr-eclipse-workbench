#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Hash relative file names and content for a verified p2 site across CI jobs."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


def digest_site(root: Path) -> str:
    base = Path(root)
    if base.is_symlink() or not base.is_dir():
        raise ValueError("Site must be an existing, non-symlink directory")
    entries = sorted(base.rglob("*"))
    files = [p for p in entries if p.is_file()]
    if not 3 <= len(files) <= 100:
        raise ValueError("Unexpected p2 site file count")
    hash_value = hashlib.sha256()
    for item in entries:
        if item.is_symlink() or not (item.is_file() or item.is_dir()):
            raise ValueError("Unsafe p2 site member")
    for item in files:
        relative = item.relative_to(base).as_posix()
        name = relative.encode("utf-8")
        if len(name) > 2048:
            raise ValueError("Invalid p2 site entry path")
        hash_value.update(len(name).to_bytes(4, "big"))
        hash_value.update(name)
        part = hashlib.sha256()
        with item.open("rb") as file:
            for chunk in iter(lambda: file.read(128 * 1024), b""):
                part.update(chunk)
        hash_value.update(part.digest())
    return hash_value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path)
    args = parser.parse_args()
    try:
        print(digest_site(args.site))
    except (ValueError, OSError) as error:
        parser.exit(1, f"p2 site integrity failed: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
