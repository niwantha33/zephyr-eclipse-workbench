#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail-closed project-root zephyr-tracepoints.properties parser.

One declaration per line: NAME = Display label | integer|float
Names are limited to 19 ASCII bytes because Zephyr's CTF named_event stores
a 20-byte, zero-terminated name. No configuration -> inactive (not an error).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re

FILE_NAME = "zephyr-tracepoints.properties"
MAX_BYTES = 32 * 1024
MAX_TRACEPOINTS = 64
NAME_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,18}\Z", re.ASCII)


@dataclass(frozen=True)
class TracepointSpec:
    name: str
    label: str
    type: str


@dataclass(frozen=True)
class TracepointConfig:
    status: str  # inactive | active | invalid
    specs: tuple[TracepointSpec, ...] = ()
    error: str = ""


def parse_text(text: str) -> TracepointConfig:
    specs: list[TracepointSpec] = []
    seen: set[str] = set()
    for number, original in enumerate(text.splitlines(), 1):
        row = original.strip()
        if not row or row.startswith("#"):
            continue
        if row.count("=") != 1 or row.count("|") != 1:
            return TracepointConfig("invalid", error=(
                f"line {number}: expected NAME = Display label | integer or float"))
        name, rhs = row.split("=", 1)
        label, kind = rhs.split("|", 1)
        name, label, kind = name.strip(), label.strip(), kind.strip()
        if not NAME_PATTERN.fullmatch(name):
            return TracepointConfig("invalid", error=(
                f"line {number}: name must be 1-19 ASCII letters/digits/_ and begin with a letter"))
        if name in seen:
            return TracepointConfig("invalid", error=f"line {number}: duplicate tracepoint '{name}'")
        if not label or len(label) > 100 or any(ord(ch) < 32 for ch in label):
            return TracepointConfig("invalid", error=f"line {number}: display label is empty or invalid")
        if kind not in ("integer", "float"):
            return TracepointConfig("invalid", error=(
                f"line {number}: type must be integer or float"))
        if len(specs) == MAX_TRACEPOINTS:
            return TracepointConfig("invalid", error=f"line {number}: too many tracepoints (maximum 64)")
        seen.add(name)
        specs.append(TracepointSpec(name, label, kind))
    return TracepointConfig("active", tuple(specs)) if specs else TracepointConfig("inactive")


def load_project(project_root: Path) -> TracepointConfig:
    path = Path(project_root) / FILE_NAME
    try:
        if path.is_symlink():
            return TracepointConfig("invalid", error="line 1: tracepoint configuration must not be a symlink")
        if not path.exists():
            return TracepointConfig("inactive")
        if not path.is_file() or path.stat().st_size > MAX_BYTES:
            return TracepointConfig("invalid", error="line 1: tracepoint configuration must be a regular file of at most 32 KiB")
        raw = path.read_bytes()
        if len(raw) > MAX_BYTES:
            return TracepointConfig("invalid", error="line 1: tracepoint configuration exceeds 32 KiB")
        try:
            return parse_text(raw.decode("utf-8-sig"))
        except UnicodeDecodeError:
            return TracepointConfig("invalid", error="line 1: configuration is not UTF-8 text")
    except OSError as error:
        return TracepointConfig("invalid", error=f"line 1: cannot read configuration ({error.strerror or 'I/O error'})")
