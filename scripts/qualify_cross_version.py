#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Explicit, compile-only Zephyr 4.4 -> 4.5 qualification using two installed setups.

Never downloads, flashes, debugs, or changes the original application binding.
Use only trusted, separately installed managed roots and application sources.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

TOOLS = Path(__file__).resolve().parents[1] / "plugins/io.github.niwantha33.zew.ui/tools"
sys.path.insert(0, str(TOOLS))
import environment_archive as archive
import project_versions as versions

BOARD = re.compile(r"[A-Za-z0-9][A-Za-z0-9_+.-]*(?:/[A-Za-z0-9][A-Za-z0-9_+.-]*){0,3}")
KEYS = ("workspace", "python", "tools.git", "tools.sdk", "tools.cmake", "tools.ninja")


def load_setup(root, expected):
    root = root.resolve(strict=True)
    archive.require_isolated(root)
    values = archive.properties(root)
    paths = tuple(Path(values[key]).resolve(strict=True) for key in KEYS)
    if not versions.zephyr_version(paths[0]).startswith(expected + "."):
        raise versions.VersionError("Expected Zephyr " + expected + ".x in " + str(root))
    return root, paths


def separate(first, second):
    return not first.is_relative_to(second) and not second.is_relative_to(first)


def prepare(original, candidate, application, output, boards):
    original, old = load_setup(original, "4.4")
    candidate, new = load_setup(candidate, "4.5")
    application = application.resolve(strict=True)
    if not separate(original, candidate):
        raise versions.VersionError("Qualification requires two non-nested setup roots")
    if not application.is_dir() or not (application / "CMakeLists.txt").is_file():
        raise versions.VersionError("Select an existing trusted Zephyr application")
    if not output.is_absolute() or versions.is_link(output):
        raise versions.VersionError("Select a new absolute evidence directory")
    output = output.resolve()
    if (output.exists() or not output.parent.is_dir()
            or any(not separate(output, protected) for protected in (original, candidate, application))):
        raise versions.VersionError("Evidence directory must be new and outside both setups and the application")
    if (not 1 <= len(boards) <= 16 or len(set(boards)) != len(boards)
            or any(not BOARD.fullmatch(board) or any(part in (".", "..") for part in board.split("/"))
                   for board in boards)):
        raise versions.VersionError("Select 1–16 distinct exact board targets")
    return (original, old), (candidate, new), application, output


def copy_inputs(application, destination, expected):
    destination.mkdir(exist_ok=False)
    for relative, digest in sorted(expected.items()):
        parts = versions.relative_path(relative).parts
        source = application.joinpath(*parts)
        if (not source.resolve().is_relative_to(application)
                or any(versions.is_link(application.joinpath(*parts[:i])) for i in range(1, len(parts) + 1))
                or versions.file_hash(source) != digest):
            raise versions.VersionError("Original input changed or escaped: " + relative)
        target = destination.joinpath(*parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        with source.open("rb") as incoming, target.open("xb") as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        if versions.file_hash(target) != digest:
            raise versions.VersionError("Copied source differs: " + relative)
    if versions.project_sources(destination) != expected:
        raise versions.VersionError("Copied application differs from original")


def build_one(setup, board, application, output, expected):
    root, paths = setup
    workspace, python, git, sdk, cmake, ninja = paths
    env = versions.managed_environment(root, workspace, python, git, sdk, cmake, ninja)
    frozen, before = versions.system_record(*paths, board, application, managed_root=root)
    if before["application_files"] != expected:
        raise versions.VersionError("Source inputs changed before build")
    build = output / "build"
    copy = output / "application"
    output.mkdir(exist_ok=False)
    copy_inputs(application, copy, expected)
    command = [str(python), "-m", "west", "build", "--pristine", "always",
               "--board", board, "--build-dir", str(build), str(copy), "--",
               "-DCMAKE_MAKE_PROGRAM:FILEPATH=" + ninja.as_posix(),
               "-DPython3_EXECUTABLE:FILEPATH=" + python.as_posix()]
    with (output / "build.log").open("xb") as log:
        subprocess.run(command, cwd=workspace, env=env, stdin=subprocess.DEVNULL,
                       stdout=log, stderr=subprocess.STDOUT, shell=False, check=True, timeout=2700)
    files = {}
    for name in ("zephyr.elf", ".config", "zephyr.dts"):
        path = build / "zephyr" / name
        if not path.is_file() or not path.resolve().is_relative_to(build.resolve()):
            raise versions.VersionError("Build output missing or unsafe: " + name)
        files[name] = versions.file_hash(path)
    if (versions.project_sources(copy) != expected or versions.project_sources(application) != expected):
        raise versions.VersionError("Application inputs changed during build")
    frozen_after, after = versions.system_record(*paths, board, application,
                                                   before.get("manifest_origin"), managed_root=root)
    if versions.compare(before, after) or frozen_after != frozen:
        raise versions.VersionError("Source or environment identity changed during build")
    return {"status": "compiled", "inputs": before, "artifacts": files,
            "physical_hardware": "not-tested", "network_isolation": "not-OS-sandboxed"}


def qualify(original, candidate, application, output, boards, confirm=False):
    old, new, app, evidence = prepare(original, candidate, application, output, boards)
    if not confirm:
        return {"status": "plan-only", "boards": boards, "output": str(evidence),
                "physical_hardware": "not-tested", "firmware_built": False}
    expected = versions.project_sources(app)
    evidence.mkdir(exist_ok=False)
    report = {"schema": 1, "status": "incomplete", "physical_hardware": "not-tested",
              "host": sys.platform, "boards": [], "original_application": str(app)}
    try:
        for index, board in enumerate(boards):
            folder = evidence / ("target-" + str(index + 1))
            folder.mkdir()
            first = build_one(old, board, app, folder / "original", expected)
            second = build_one(new, board, app, folder / "candidate", expected)
            report["boards"].append({"board": board, "original": first, "candidate": second,
                "elf_bytes_match": first["artifacts"]["zephyr.elf"] == second["artifacts"]["zephyr.elf"],
                "config_bytes_match": first["artifacts"][".config"] == second["artifacts"][".config"],
                "devicetree_bytes_match": first["artifacts"]["zephyr.dts"] == second["artifacts"]["zephyr.dts"]})
        report["status"] = "compile-matrix-passed"
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        report["error"] = str(exc)[:1000]
    finally:
        with (evidence / "qualification.json").open("x", encoding="utf-8") as result:
            json.dump(report, result, indent=2, sort_keys=True)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("original-setup", "candidate-setup", "application", "output"):
        parser.add_argument("--" + option, type=Path, required=True)
    parser.add_argument("--board", action="append", required=True)
    parser.add_argument("--confirm-build", action="store_true",
                        help="Explicitly trust both tool installations and application; run builds")
    args = parser.parse_args(argv)
    return qualify(args.original_setup, args.candidate_setup, args.application, args.output,
                   args.board, args.confirm_build)


if __name__ == "__main__":
    try:
        result = main()
        print(json.dumps(result, sort_keys=True))
        sys.exit(0 if result["status"] in ("plan-only", "compile-matrix-passed") else 1)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(2)
