# SPDX-License-Identifier: Apache-2.0
"""Focused, synthetic package/CLI regression tests; no Zephyr checkout needed."""
from __future__ import annotations

import hashlib
from importlib.metadata import distribution
from pathlib import Path
import subprocess
import sys
import zipfile

import pytest

from zew_evidence.bundle import inspect, write
from zew_evidence.cli import main
from zew_evidence.collector import InputError, collect
from zew_evidence.compare import compare


def _build(root: Path, configuration: str, *, devicetree: bool = True) -> Path:
    zephyr = root / "zephyr"
    zephyr.mkdir(parents=True)
    (zephyr / ".config").write_text(configuration, encoding="utf-8")
    if devicetree:
        (zephyr / "zephyr.dts").write_text("/ { chosen { }; };\n", encoding="utf-8")
    return root


def test_snapshot_creation_is_deterministic_and_read_only(tmp_path: Path) -> None:
    build = _build(tmp_path / "build", 'CONFIG_GPIO=y\nCONFIG_SECRET_TOKEN="private"\n')
    before = {path.relative_to(build): path.read_bytes() for path in build.rglob("*") if path.is_file()}
    first = collect(build)
    assert first == collect(build)
    assert first["status"] == "observations-only"
    assert {path.relative_to(build): path.read_bytes() for path in build.rglob("*") if path.is_file()} == before

    artifact = next(node for node in first["nodes"] if node["id"] == "artifact:default:kconfig")
    assert artifact["provenance"]["sha256"] == hashlib.sha256(before[Path("zephyr/.config")]).hexdigest()
    assert "private" not in str(first)


def test_comparison_matching_snapshots_has_no_changes(tmp_path: Path) -> None:
    left = _build(tmp_path / "left", "CONFIG_GPIO=y\n")
    right = _build(tmp_path / "right", "CONFIG_GPIO=y\n")
    result = compare(collect(left), collect(right))
    assert result["status"] == "observed-differences-only"
    assert result["artifact_changes"] == []
    assert result["kconfig_changes"] == []
    assert result["uncomparable"] == []


def test_comparison_changed_missing_and_unknown_are_explicit(tmp_path: Path) -> None:
    left = _build(tmp_path / "left", 'CONFIG_GPIO=y\nCONFIG_SECRET_TOKEN="old"\n')
    right = _build(tmp_path / "right", 'CONFIG_GPIO=n\nCONFIG_SECRET_TOKEN="new"\n', devicetree=False)
    left_graph, right_graph = collect(left), collect(right)
    result = compare(left_graph, right_graph)

    assert any(x["id"] == "kconfig:default:CONFIG_GPIO" and x["state"] == "changed"
               and x["left"] == "y" and x["right"] == "n" for x in result["kconfig_changes"])
    assert any(x["id"] == "artifact:default:kconfig" and x["state"] == "changed"
               for x in result["artifact_changes"])
    assert any(x["id"] == "artifact:default:devicetree" and x["state"] == "removed"
               for x in result["artifact_changes"])
    assert {"image": "default", "artifact": "zephyr/zephyr.dts", "state": "missing"} in right_graph["missing_evidence"]
    assert any(x["id"] == "kconfig:default:CONFIG_SECRET_TOKEN" and x["state"] == "redacted"
               for x in result["uncomparable"])
    assert "old" not in str(result) and "new" not in str(result)


def test_bundle_manifest_hash_verification_and_tamper_rejection(tmp_path: Path) -> None:
    build = _build(tmp_path / "build", "CONFIG_GPIO=y\n")
    archive = tmp_path / "evidence.zip"
    write(collect(build), archive)
    report = inspect(archive, include_graph=True)
    assert report["status"] == "verified-integrity-only"
    assert report["graph"]["status"] == "observations-only"

    with zipfile.ZipFile(archive, "r") as source:
        graph = source.read("graph.json")
        manifest = source.read("manifest.json")
        metadata = {item.filename: item for item in source.infolist()}
    assert report["graph_sha256"] == hashlib.sha256(graph).hexdigest()

    tampered = tmp_path / "tampered.zip"
    with zipfile.ZipFile(tampered, "w") as target:
        target.writestr(metadata["manifest.json"], manifest)
        target.writestr(metadata["graph.json"], graph.replace(b"observations-only", b"observations-onlz"))
    with pytest.raises(InputError, match="SHA-256 or size mismatch"):
        inspect(tampered)
    assert not (tmp_path / "graph.json").exists(), "Inspection must not extract archive members"


def test_console_entry_point_and_legacy_shim(tmp_path: Path) -> None:
    import zew

    assert zew.main is main
    scripts = [entry for entry in distribution("zew-evidence").entry_points
               if entry.group == "console_scripts" and entry.name == "zew"]
    assert len(scripts) == 1 and scripts[0].load() is main

    repo = Path(__file__).resolve().parents[2]
    process = subprocess.run([sys.executable, "-I", str(repo / "zew.py"), "--version"],
                             cwd=tmp_path, capture_output=True, text=True, check=True, timeout=15)
    assert "zew evidence prototype 0.1.1" in process.stdout
