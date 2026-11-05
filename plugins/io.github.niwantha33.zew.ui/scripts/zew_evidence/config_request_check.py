# SPDX-License-Identifier: Apache-2.0
"""Compare explicit Kconfig requests with the last generated build snapshot.

This is an observational check, not a Kconfiglib evaluator. Never infer that a
missing generated symbol is undefined, unreachable, or disabled. Never export
raw values from user-provided fragments, even when comparing them.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import re

from .collector import InputError, SCHEMA_VERSION

MAX_SNIPPET_BYTES = 128 * 1024
MAX_SNIPPET_LINES = 4096
_SYMBOL = r"CONFIG_[A-Z0-9_]{1,160}"
_SET = re.compile(r"^(" + _SYMBOL + r")=(.*)$")
_UNSET = re.compile(r"^# (" + _SYMBOL + r") is not set$")
_BOOLEAN = frozenset(("y", "m", "n"))


def _read_snippet(path: Path) -> tuple[str, str, int]:
    path = Path(path).absolute()
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise InputError("Symlinked snippet paths are not accepted")
    if not path.is_file():
        raise InputError("--snippet must name an existing regular file")
    before = path.stat()
    if before.st_size > MAX_SNIPPET_BYTES:
        raise InputError("Snippet exceeds the 128 KiB limit")
    with path.open("rb") as stream:
        data = stream.read(MAX_SNIPPET_BYTES + 1)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise InputError("Snippet changed during inspection; retry")
    if len(data) > MAX_SNIPPET_BYTES:
        raise InputError("Snippet exceeds the 128 KiB limit")
    try:
        content = data.decode("utf-8", errors="strict")
    except UnicodeError as error:
        raise InputError("Snippet must be UTF-8") from error
    if "\x00" in content:
        raise InputError("Snippet contains a NUL byte")
    return content, hashlib.sha256(data).hexdigest(), len(data)


def _requests(content: str) -> tuple[list[dict], list[int]]:
    lines = content.splitlines()
    if len(lines) > MAX_SNIPPET_LINES:
        raise InputError("Snippet exceeds the 4096-line limit")
    requests: dict[str, dict] = {}
    invalid: list[int] = []
    for number, original in enumerate(lines, 1):
        stripped = original.strip()
        if not stripped:
            continue
        unset = _UNSET.fullmatch(stripped)
        assigned = _SET.fullmatch(stripped)
        if unset:
            name, value = unset.group(1), "n"
        elif assigned:
            name, value = assigned.groups()
            if not value or len(value) > 4096:
                invalid.append(number)
                continue
        elif stripped.startswith("#") and not stripped.startswith("# CONFIG_"):
            continue
        else:
            invalid.append(number)
            continue
        entry = requests.setdefault(name, {"symbol": name, "lines": [], "values": set()})
        entry["lines"].append(number)
        # Strings and numbers are never copied into the report.
        entry["values"].add(value if value in _BOOLEAN else "opaque:" + hashlib.sha256(value.encode("utf-8")).hexdigest())
    return [requests[name] for name in sorted(requests)], invalid


def check(graph: dict, snippet: Path) -> dict:
    """Read-only comparison against collector-observed .config, never Kconfig semantics."""
    content, digest, size = _read_snippet(snippet)
    requests, invalid = _requests(content)
    images = sorted(node["data"]["name"] for node in graph["nodes"] if node["type"] == "image")
    checks = []
    counts: dict[str, int] = {}
    for image in images:
        artifact = next((node for node in graph["nodes"]
                         if node["id"] == f"artifact:{image}:kconfig"), None)
        observed = {node["data"]["name"]: node["data"]["observed_value"]
                    for node in graph["nodes"]
                    if node["type"] == "kconfig_observation"
                    and node["id"].startswith(f"kconfig:{image}:")}
        results = []
        for request in requests:
            values = request["values"]
            if len(values) != 1:
                state = "conflicting-requests"
            elif artifact is None:
                state = "no-generated-config"
            elif next(iter(values)) not in _BOOLEAN:
                state = "unsupported-value-type"
            elif request["symbol"] not in observed:
                state = "symbol-not-observed"
            elif observed[request["symbol"]] not in _BOOLEAN:
                state = "generated-value-redacted-or-unsupported"
            elif next(iter(values)) == observed[request["symbol"]]:
                state = "matches-generated"
            else:
                state = "differs-from-generated"
            counts[state] = counts.get(state, 0) + 1
            results.append({
                "symbol": request["symbol"], "source_lines": request["lines"],
                "state": state,
                "requested": next(iter(values)) if len(values) == 1 and next(iter(values)) in _BOOLEAN else "<redacted>",
                "observed": observed.get(request["symbol"], "unknown")
                    if observed.get(request["symbol"]) in _BOOLEAN else "<redacted-or-unknown>",
                "confidence": "confirmed" if state in ("matches-generated", "differs-from-generated", "conflicting-requests")
                    else "unknown",
            })
        checks.append({
            "image": image,
            "generated_config": {"state": "observed", "sha256": artifact["provenance"]["sha256"]}
                if artifact else {"state": "missing"},
            "requests": results,
        })
    success = bool(requests) and not invalid and bool(images) and counts.get("matches-generated", 0) == len(requests) * len(images)
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "matching-snapshot" if success else "partial-or-mismatched",
        "input": {"sha256": digest, "bytes": size, "requests": len(requests), "invalid_lines": invalid},
        "images": checks,
        "counts": dict(sorted(counts.items())),
        "limitations": [
            "Matches refer only to recorded generated .config bytes, not Kconfig dependency evaluation.",
            "A differing or absent value does not prove an undefined symbol or a root cause.",
            "Snippet inclusion in the original build and build freshness are not established.",
            "String/numeric requests are not compared, and original values are never exported.",
            "No files are modified; hardware, runtime state and the flashed image are unknown.",
        ],
    }
