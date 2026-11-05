#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Stage the headless Studio dependency closure verbatim for Eclipse/p2.

Use --check in CI. This reads Python syntax only; it never imports project code.
"""
import argparse
import ast
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "zew_evidence"
TARGET = ROOT / "plugins/io.github.niwantha33.zew.ui/scripts/zew_evidence"


def dependencies():
    found, pending = set(), ["studio_cli", "__init__"]
    while pending:
        name = pending.pop()
        if name in found:
            continue
        path = SOURCE / (name + ".py")
        if not path.is_file():
            raise ValueError("Missing headless dependency: " + name)
        found.add(name)
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for entry in ast.walk(tree):
            if isinstance(entry, ast.ImportFrom) and entry.level == 1:
                names = [entry.module.split(".")[0]] if entry.module else [item.name for item in entry.names]
                pending.extend(names)
    return sorted(found)


def sync(check=False):
    names = dependencies()
    for name in names:
        source, target = SOURCE / (name + ".py"), TARGET / (name + ".py")
        if check:
            if not target.is_file() or source.read_bytes() != target.read_bytes():
                raise ValueError("Installed Studio evaluator differs from headless source: " + name)
        else:
            TARGET.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    return names


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    print("Verified " + str(len(sync(parser.parse_args().check))) + " exact Studio resource modules")
