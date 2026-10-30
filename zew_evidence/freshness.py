# SPDX-License-Identifier: Apache-2.0
"""Verify archived artifact byte identities against an explicitly selected build.

Matching bytes do not prove the build is up to date with source inputs, nor that
this firmware was flashed. No repository commands or target operations are run.
"""
from pathlib import Path
from .collector import InputError, SCHEMA_VERSION, FILES, _digest, _regular


def verify(graph: dict, build: Path) -> dict:
    from .bundle import _validate_graph
    _validate_graph(graph)
    root = Path(build)
    if root.is_symlink() or not root.is_dir():
        raise InputError("--build must be an existing non-symlink directory")
    root = root.resolve(strict=True)
    images = {n["data"]["name"]: n["data"]["artifact_root"]
              for n in graph["nodes"] if n["type"] == "image"}
    results = []
    expected_paths = dict(FILES)
    for node in sorted(graph["nodes"], key=lambda n: n["id"]):
        if node["type"] != "artifact":
            continue
        _, image, kind = node["id"].split(":", 2)
        relative = expected_paths[kind]
        directory = root
        for part in Path(images[image]).parts:
            directory = directory / part
            if directory.is_symlink():
                raise InputError("Symlinked image path rejected")
        if not directory.is_dir():
            path = None
        else:
            path = _regular(directory, relative)
        expected = node["provenance"]["sha256"]
        if path is None:
            actual, state = None, "missing"
        else:
            actual, _ = _digest(path)
            state = "matching" if actual == expected else "changed"
        results.append({"artifact_id": node["id"], "state": state,
                        "expected_sha256": expected, "observed_sha256": actual,
                        "confidence": "confirmed", "provenance": node["provenance"]})
    changed = sum(row["state"] != "matching" for row in results)
    return {"schema_version": SCHEMA_VERSION,
            "status": "artifact-mismatch" if changed else "recorded-artifacts-match" if results else "no-recorded-artifacts",
            "not_recorded_in_snapshot": graph["missing_evidence"],
            "checked_artifacts": len(results), "mismatches": changed, "artifacts": results,
            "unknown": ["Freshness relative to all source/configuration inputs",
                        "Firmware identity on any connected or deployed device",
                        "Authenticity of the archived evidence producer"],
            "interpretation": "Only recorded artifact byte identities were checked; matching is not a successful build or hardware test."}
