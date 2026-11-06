#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Step 9: bounded Twister metadata observation using selected checkout's scl.

Only inspect saved testcase.yaml, sample.yaml and test_config.yaml.
Validate using the exact checkout's Twister JSON Schema and scl validator,
never invoke Twister's test planner, build, execution or environment discovery.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import stat
import sys

from yaml_observe_file import SourceRejected, read_selected
from zew_evidence.yaml_observation import observe_yaml

MAX_API_BYTES = 64 * 1024
MAX_SCHEMA_BYTES = 128 * 1024
MAX_ROWS = 256
MAX_FIELD_CHARS = 240
ZERO = "0" * 64


class Rejected(Exception):
    """Stable reason code, without private source content."""


def source_kind(relative, source_root):
    parts = relative.replace("\\", "/").split("/")
    name = parts[-1]
    inside_tests = len(parts) >= 2 and parts[0] in ("tests", "samples")
    at_app_root = len(parts) == 1 and source_root == "application"
    if (inside_tests or at_app_root) and name in ("sample.yaml", "testcase.yaml"):
        return "testsuite"
    if (inside_tests or at_app_root) and name == "test_config.yaml":
        return "test-config"
    return "none"


def read_bounded(path, limit):
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise Rejected("selected-twister-api-or-schema-symlinked")
    if not path.is_file():
        raise Rejected("selected-twister-api-or-schema-missing")
    with path.open("rb") as handle:
        before = os.fstat(handle.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise Rejected("twister-api-or-schema-too-large")
        data = handle.read(limit + 1)
        after = os.fstat(handle.fileno())
    if len(data) > limit or (before.st_dev, before.st_ino, before.st_size,
                             before.st_mtime_ns) != (after.st_dev, after.st_ino,
                                                     after.st_size, after.st_mtime_ns):
        raise Rejected("twister-api-or-schema-changed")
    return data


def digest(data):
    return hashlib.sha256(data).hexdigest()


def short(value, width=MAX_FIELD_CHARS):
    """Display a bounded *preview*, not a round-trippable semantic value."""
    if isinstance(value, (dict, list)):
        rendered = json.dumps(value, ensure_ascii=True, separators=(",", ":"))
    elif isinstance(value, bool):
        rendered = "true" if value else "false"
    elif value is None:
        rendered = ""
    else:
        rendered = str(value)
    rendered = rendered.replace("\r", "\\r").replace("\n", "\\n")
    return rendered[:width - 1] + "…" if len(rendered) > width else rendered


def inspect(root_text, relative, source_root, zephyr_text, application_text):
    root = Path(root_text).absolute()
    zephyr = Path(zephyr_text).absolute()
    application = Path(application_text).absolute()
    if source_root not in ("zephyr", "application"):
        raise Rejected("invalid-source-root")
    expected = zephyr if source_root == "zephyr" else application
    if root.resolve(strict=True) != expected.resolve(strict=True):
        raise Rejected("selected-source-root-mismatch")
    raw = read_selected(str(root), relative, source_root)
    source_sha = digest(raw)
    kind = source_kind(relative, source_root)
    if kind == "none":
        return ("not-twister", "not-a-supported-twister-file", source_sha,
                ZERO, ZERO, kind, [])
    prior = observe_yaml(raw, path=relative, source_root=source_root)
    if prior["status"] != "parsed" or not prior["documents"]:
        raise Rejected("twister-source-syntax-or-safety-rejected")

    scl_file = zephyr / "scripts" / "pylib" / "twister" / "scl.py"
    schema_file = (zephyr / "scripts" / "schemas" / "twister"
                   / ("testsuite-schema.yaml" if kind == "testsuite"
                      else "test-config-schema.yaml"))
    api_bytes = read_bounded(scl_file, MAX_API_BYTES)
    schema_bytes = read_bounded(schema_file, MAX_SCHEMA_BYTES)
    api_sha = digest(api_bytes)
    schema_sha = digest(schema_bytes)

    import yaml
    loaded = yaml.safe_load(raw)
    if not isinstance(loaded, dict):
        raise Rejected("twister-source-not-a-mapping")
    schema = yaml.safe_load(schema_bytes)
    if not isinstance(schema, dict):
        raise Rejected("selected-twister-schema-invalid")
    # Use the actual upstream Twister validation path, but not its runner.
    # Validate an in-memory byte snapshot rather than reopening user source.
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        search = zephyr / "scripts" / "pylib" / "twister"
        if search.is_symlink() or not search.is_dir():
            raise Rejected("selected-twister-api-unavailable")
        old_path = sys.path[:]
        old_scl = sys.modules.pop("scl", None)
        try:
            sys.path.insert(0, str(search))
            scl = importlib.import_module("scl")
            if Path(scl.__file__).resolve(strict=True) != scl_file.resolve(strict=True):
                raise Rejected("selected-twister-api-mismatch")
            validator = getattr(scl, "_yaml_validate", None)
            if not callable(validator):
                raise Rejected("selected-twister-validator-unavailable")
            try:
                validator(loaded, schema)
            except Exception as error:
                raise Rejected("twister-schema-rejected") from error
        finally:
            sys.modules.pop("scl", None)
            if old_scl is not None:
                sys.modules["scl"] = old_scl
            sys.path[:] = old_path

    rows = []
    def add(code, *fields):
        if len(rows) >= MAX_ROWS:
            raise Rejected("too-many-twister-metadata-rows")
        rows.append((code, *(short(value) for value in fields)))

    if kind == "testsuite":
        sample = loaded.get("sample", {})
        if sample:
            for key in ("name", "description"):
                if key in sample:
                    add("M", key, sample[key])
        common = loaded.get("common", {})
        for key, value in common.items():
            add("C", key, value)
        scenarios = loaded.get("tests", {})
        if not isinstance(scenarios, dict) or not scenarios:
            raise Rejected("twister-scenarios-not-resolved")
        for name, config in scenarios.items():
            add("T", name)
            for key, value in config.items():
                add("A", name, key, value)
    else:
        options = loaded.get("options", {})
        for key, value in options.items():
            add("O", "options." + key, value)
        platforms = loaded.get("platforms", {})
        for key in ("override_default_platforms", "increased_platform_scope"):
            if key in platforms:
                add("O", "platforms." + key, platforms[key])
        for platform in platforms.get("default_platforms", []):
            add("P", platform)
        for level in loaded.get("levels", []):
            add("L", level["name"], level.get("description", ""),
                level.get("adds", []), level.get("inherits", []))

    if digest(read_selected(str(root), relative, source_root)) != source_sha:
        raise Rejected("twister-source-changed")
    if digest(read_bounded(scl_file, MAX_API_BYTES)) != api_sha:
        raise Rejected("selected-twister-validator-changed")
    if digest(read_bounded(schema_file, MAX_SCHEMA_BYTES)) != schema_sha:
        raise Rejected("selected-twister-schema-changed")
    return ("resolved", "selected-twister-schema-validated", source_sha,
            api_sha, schema_sha, kind, rows)


def encoded(value):
    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")


def emit(record):
    state, reason, source_sha, api_sha, schema_sha, kind, rows = record
    print("ZEW-TWISTER-1")
    print("H\t" + "\t".join(encoded(x) for x in
                        (state, reason, source_sha, api_sha, schema_sha, kind)))
    for row in rows:
        print(row[0] + "\t" + "\t".join(encoded(item) for item in row[1:]))


def main(argv=None):
    parser = argparse.ArgumentParser(allow_abbrev=False)
    for option in ("root", "relative", "source-root", "zephyr", "application"):
        parser.add_argument("--" + option, required=True)
    args = parser.parse_args(argv)
    try:
        report = inspect(args.root, args.relative, args.source_root,
                         args.zephyr, args.application)
    except (Rejected, SourceRejected) as error:
        report = ("unavailable", str(error), ZERO, ZERO, ZERO, "none", [])
    except (Exception, SystemExit, RecursionError):
        report = ("unavailable", "selected-twister-validation-unavailable-or-rejected",
                  ZERO, ZERO, ZERO, "none", [])
    emit(report)


if __name__ == "__main__":
    main()
