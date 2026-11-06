#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Step 7: inspect one selected DTS binding using that checkout's edtlib.Binding.

No build/flash, schema imitation, source writes, or guessed binding origins.
Reject ambiguous includes and provide bounded, sanitized, versioned evidence.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib
import os
from pathlib import Path
import stat
import sys

from yaml_observe_file import read_selected, SourceRejected
from zew_evidence.yaml_observation import observe_yaml

MAX_BINDINGS = 12000
MAX_INCLUDES = 64
MAX_PROPERTY_SPECS = 256
MAX_METADATA = 512
MAX_FILE_BYTES = 256 * 1024


class Rejected(Exception):
    pass


def _limited(value, limit=MAX_METADATA):
    return str(value or "")[:limit]


def _regular_bytes(path, limit=MAX_FILE_BYTES):
    if path.is_symlink() or not path.is_file():
        raise Rejected("unsafe-include-path")
    with path.open("rb") as handle:
        before = os.fstat(handle.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise Rejected("include-too-large")
        value = handle.read(limit + 1)
        after = os.fstat(handle.fileno())
    if len(value) > limit or (before.st_dev, before.st_ino, before.st_size,
                            before.st_mtime_ns) != (after.st_dev, after.st_ino,
                                                    after.st_size, after.st_mtime_ns):
        raise Rejected("include-unstable-or-large")
    return value


def _bindings(root):
    directory = root / "dts" / "bindings"
    if not directory.is_dir() or directory.is_symlink():
        return []
    items = []
    scanned = 0
    for current, directories, names in os.walk(directory, followlinks=False):
        directories[:] = sorted(n for n in directories if
                                not (Path(current) / n).is_symlink())
        for name in sorted(names):
            scanned += 1
            if scanned > MAX_BINDINGS:
                raise Rejected("binding-catalog-too-large")
            if name.endswith((".yaml", ".yml")):
                path = Path(current) / name
                if path.is_symlink() or not path.is_file():
                    continue
                items.append(path)
    return items


def _include_names(value):
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        names = []
        for item in value:
            names.extend(_include_names(item))
        return names
    if isinstance(value, dict) and isinstance(value.get("name"), str):
        return [value["name"]]
    raise Rejected("unsupported-include-description")


def _load_safe_yaml(raw, relative, kind):
    import yaml
    observation = observe_yaml(raw, path=relative, source_root=kind)
    if observation["status"] != "parsed":
        raise Rejected("binding-syntax-or-safety-rejected")
    parsed = yaml.safe_load(raw)
    if not isinstance(parsed, dict):
        raise Rejected("binding-root-not-a-mapping")
    return parsed


def _names_in(mapping):
    names = []
    if isinstance(mapping, dict):
        for key, value in mapping.items():
            if key == "include":
                names.extend(_include_names(value))
            elif key == "child-binding" and isinstance(value, dict):
                names.extend(_names_in(value))
    return names


def inspect(root, relative, kind, zephyr, application):
    if kind not in ("application", "zephyr"):
        raise Rejected("invalid-source-kind")
    root = Path(root).absolute()
    zephyr = Path(zephyr).absolute()
    application = Path(application).absolute()
    expected = zephyr if kind == "zephyr" else application
    if root.resolve(strict=True) != expected.resolve(strict=True):
        raise Rejected("source-root-mismatch")
    source = read_selected(str(root), relative, kind)
    source_hash = hashlib.sha256(source).hexdigest()
    if len(relative) > 4096 or not relative.replace("\\", "/").startswith("dts/bindings/"):
        return ("not-binding", "not-a-binding-path", source_hash,
                "0" * 64, "", "", "", 0, [])
    selected = root / Path(relative.replace("\\", "/"))
    if selected.is_symlink() or not selected.is_file():
        raise Rejected("selected-binding-unavailable")
    edt_path = zephyr / "scripts/dts/python-devicetree/src/devicetree/edtlib.py"
    if not edt_path.is_file() or edt_path.is_symlink():
        return ("unavailable", "selected-edtlib-missing", source_hash,
                "0" * 64, "", "", "", 0, [])
    edt_hash = hashlib.sha256(_regular_bytes(edt_path, 1024 * 1024)).hexdigest()
    catalogs = _bindings(zephyr) + _bindings(application)
    if not catalogs:
        return ("unavailable", "binding-catalog-unavailable", source_hash,
                edt_hash, "", "", "", 0, [])
    names = {}
    for path in catalogs:
        names.setdefault(path.name, []).append(path)

    # The upstream API resolves include names by basename. Reject ambiguous
    # referenced names rather than guessing the include from path ordering.
    parsed = _load_safe_yaml(source, relative, kind)
    includes = {}
    pending = _names_in(parsed)
    while pending:
        if len(includes) >= MAX_INCLUDES:
            raise Rejected("too-many-binding-includes")
        name = pending.pop()
        if not isinstance(name, str) or Path(name).name != name or name in ("", ".", ".."):
            raise Rejected("unsafe-include-name")
        choices = names.get(name, [])
        if len(choices) != 1:
            raise Rejected("ambiguous-or-missing-include")
        include = choices[0]
        if include in includes:
            continue
        if any(part.is_symlink() for part in (include, *include.parents)):
            raise Rejected("symlinked-include-path")
        original = _regular_bytes(include)
        root_of_include = zephyr if include.is_relative_to(zephyr) else application
        kind_of_include = "zephyr" if root_of_include == zephyr else "application"
        mapping = _load_safe_yaml(original, include.relative_to(root_of_include).as_posix(),
                                  kind_of_include)
        includes[include] = hashlib.sha256(original).hexdigest()
        pending.extend(_names_in(mapping))

    fname2path = {name: str(paths[0]) for name, paths in names.items() if len(paths) == 1}
    search = zephyr / "scripts/dts/python-devicetree/src"
    if search.is_symlink() or not search.is_dir():
        raise Rejected("selected-edtlib-missing")
    old_path = sys.path[:]
    old_modules = {key: module for key, module in sys.modules.items()
                   if key == "devicetree" or key.startswith("devicetree.")}
    for key in old_modules:
        del sys.modules[key]
    try:
        sys.path.insert(0, str(search))
        edtlib = importlib.import_module("devicetree.edtlib")
        binding = edtlib.Binding(str(selected), fname2path)
        actual_includes = {Path(path) for path in binding.included_binding_paths}
        if actual_includes != set(includes):
            raise Rejected("included-binding-identity-unverified")
        properties = []
        for name, spec in binding.prop2specs.items():
            if len(properties) >= MAX_PROPERTY_SPECS:
                raise Rejected("too-many-binding-properties")
            properties.append((_limited(name, 120), _limited(spec.type, 80),
                               "yes" if spec.required else "no",
                               "yes" if spec.deprecated else "no",
                               _limited(spec.description)))
        if source_hash != hashlib.sha256(read_selected(str(root), relative, kind)).hexdigest():
            raise Rejected("binding-source-changed")
        if edt_hash != hashlib.sha256(_regular_bytes(edt_path, 1024 * 1024)).hexdigest():
            raise Rejected("selected-edtlib-changed")
        for path, digest in includes.items():
            if hashlib.sha256(_regular_bytes(path)).hexdigest() != digest:
                raise Rejected("included-binding-changed")
        return ("resolved", "selected-upstream-edtlib", source_hash, edt_hash,
                _limited(binding.compatible, 200), _limited(binding.title),
                _limited(binding.description), len(includes), properties)
    finally:
        sys.path[:] = old_path
        for key in list(sys.modules):
            if key == "devicetree" or key.startswith("devicetree."):
                del sys.modules[key]
        sys.modules.update(old_modules)


def _field(value):
    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")


def emit(record):
    state, reason, source_sha, edtlib_sha, compatible, title, description, count, props = record
    print("ZEW-YAMLBIND-1")
    print("S\t" + "\t".join(map(_field, (state, reason, source_sha, edtlib_sha,
                                        compatible, title, description, count))))
    for prop in props:
        print("P\t" + "\t".join(map(_field, prop)))


def main(argv=None):
    parser = argparse.ArgumentParser()
    for key in ("root", "relative", "source-root", "zephyr", "application"):
        parser.add_argument("--" + key, required=True)
    args = parser.parse_args(argv)
    try:
        record = inspect(args.root, args.relative, args.source_root,
                         args.zephyr, args.application)
    except (Rejected, SourceRejected) as problem:
        record = ("unavailable", str(problem), "0" * 64, "0" * 64, "", "", "", 0, [])
    except (Exception, RecursionError):
        # Upstream EDTError can embed absolute paths and source literals.
        record = ("unavailable", "selected-edtlib-rejected-or-unavailable",
                  "0" * 64, "0" * 64, "", "", "", 0, [])
    emit(record)
    return 0


if __name__ == "__main__":
    sys.exit(main())
