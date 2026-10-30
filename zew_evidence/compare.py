# SPDX-License-Identifier: Apache-2.0
"""Deterministic, read-only comparison of *observed* build evidence.

Differences are not claims of root cause. Hashes of generated Devicetree and ELF
do not imply semantic or runtime differences.
"""
from __future__ import annotations
from .collector import SCHEMA_VERSION


def compare(left: dict, right: dict) -> dict:
    """Diff two locally collected evidence graphs without copying raw artifacts."""
    def nodes(graph, kind):
        return {node["id"]: node for node in graph["nodes"] if node["type"] == kind}

    before = nodes(left, "artifact")
    after = nodes(right, "artifact")
    artifacts = []
    for ident in sorted(before.keys() | after.keys()):
        a, b = before.get(ident), after.get(ident)
        old = a["provenance"]["sha256"] if a else None
        new = b["provenance"]["sha256"] if b else None
        if old == new:
            continue
        artifacts.append({
            "id": ident,
            "state": "added" if a is None else "removed" if b is None else "changed",
            "left_sha256": old, "right_sha256": new,
            "left_provenance": a["provenance"] if a else None,
            "right_provenance": b["provenance"] if b else None,
            "confidence": "confirmed",
            "interpretation": "Byte-level observation only; semantic cause unknown",
        })

    before = nodes(left, "kconfig_observation")
    after = nodes(right, "kconfig_observation")
    symbols = []
    unknown = []
    for ident in sorted(before.keys() | after.keys()):
        a, b = before.get(ident), after.get(ident)
        old = a["data"]["observed_value"] if a else None
        new = b["data"]["observed_value"] if b else None
        if old == "<redacted>" or new == "<redacted>":
            unknown.append({"id": ident, "state": "redacted", "reason": "Value not comparable; check private source evidence"})
            continue
        if old != new:
            symbols.append({
                "id": ident,
                "state": "added" if a is None else "removed" if b is None else "changed",
                "left": old, "right": new, "confidence": "confirmed",
                "left_provenance": a["provenance"] if a else None,
                "right_provenance": b["provenance"] if b else None,
            })
    return {
        "schema_version": SCHEMA_VERSION,
        "status": "observed-differences-only",
        "artifact_changes": artifacts,
        "kconfig_changes": symbols,
        "uncomparable": unknown,
        "limitations": [
            "Kconfig values are observed, not interpreted using kconfiglib dependencies.",
            "Redacted values never reveal whether or how a private value changed.",
            "A differing byte hash never proves semantic or runtime causation.",
            "Missing an artifact does not prove it was not generated elsewhere.",
            "No claim that the two firmware images were flashed to actual hardware.",
        ],
    }
