#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepare and compare a separate Zephyr source workspace, never an SDK or app upgrade.

Actions:
  plan     no network/no writes
  install  explicitly authorised network clone + West update into a new folder
  compare  validate source identity and compare to an application's saved lock

A source-ready workspace is deliberately NOT marked .zew-ready. The managed
board dependency provisioner remains pinned to Zephyr 4.4 and SDK 1.0.1.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

from project_versions import VersionError, get_manifest, git_head, zephyr_version

OFFICIAL_SOURCE = "https://github.com/zephyrproject-rtos/zephyr"
RELEASE = re.compile(r"^v([1-9][0-9]*|0)\.([0-9]+)\.([0-9]+)$")
SHA = re.compile(r"^[0-9a-f]{40}$")
SOURCE_LOCK = Path(".zew/source-lock.json")
SOURCE_FROZEN = Path(".zew/source-frozen.yml")
SOURCE_READY = ".zew-source-ready"
MAX_MANIFEST = 16 * 1024 * 1024


def check_release(release):
    match = RELEASE.fullmatch(release)
    if not match:
        raise VersionError("Select an exact stable release tag such as v4.5.0; "
                           "branches, RCs and moving names are not supported.")
    return ".".join(match.groups())


def check_destination(destination, current):
    if not destination.is_absolute() or not current.is_absolute():
        raise VersionError("The new workspace and the original workspace need absolute paths")
    destination = destination.resolve()
    current = current.resolve()
    if destination == current or destination.is_relative_to(current) or current.is_relative_to(destination):
        raise VersionError("The candidate must be separate from the original West workspace")
    if destination.exists() or destination.is_symlink():
        raise VersionError("Candidate destination already exists; choose a NEW empty path. "
                           "No existing workspace is ever overwritten.")
    if not destination.parent.is_dir() or destination.parent.is_symlink():
        raise VersionError("Candidate's parent folder must already exist and be a regular directory")
    return destination


def run(argv, cwd, seconds=1800, capture=False):
    environment = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_LFS_SKIP_SMUDGE="1")
    environment.pop("ZEPHYR_BASE", None)
    environment.pop("WEST_TOPDIR", None)
    command = list(map(str, argv))
    try:
        result = subprocess.run(command, cwd=cwd, env=environment,
                                stdin=subprocess.DEVNULL, check=True, shell=False,
                                stdout=subprocess.PIPE if capture else None,
                                stderr=subprocess.PIPE if capture else None,
                                text=True, errors="replace", timeout=seconds)
        return result.stdout.strip() if capture else ""
    except (subprocess.CalledProcessError, OSError, subprocess.TimeoutExpired) as exc:
        detail = getattr(exc, "stderr", None) or str(exc)
        raise VersionError("West/Git command failed: " + Path(command[0]).name
                           + " (" + detail[:500] + ")") from exc


def capture_sources(workspace, python, git, release):
    frozen, modules, origin = get_manifest(workspace, python, git)
    if origin != "west-freeze" or any(not module["installed_at_snapshot"] for module in modules):
        raise VersionError("Cannot verify all modules with west manifest --freeze; source setup is incomplete")
    encoded = frozen.encode("utf-8")
    if len(encoded) > MAX_MANIFEST:
        raise VersionError("Frozen West manifest exceeds the inspection limit")
    version = zephyr_version(workspace)
    if version != check_release(release):
        raise VersionError("Zephyr VERSION disagrees with the selected release: " + version)
    head = git_head(git, workspace / "zephyr")
    ref = run([git, "-C", workspace / "zephyr", "rev-parse",
               "refs/tags/" + release + "^{commit}"], workspace, 30, capture=True)
    if not SHA.fullmatch(ref) or head != ref:
        raise VersionError("Checked-out Zephyr commit does not match the requested release tag")
    return frozen, {
        "schema": 1,
        "kind": "zephyr-source-only",
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "release": release,
        "zephyr_commit": head,
        "origin": OFFICIAL_SOURCE,
        "west_manifest_sha256": hashlib.sha256(encoded).hexdigest(),
        "modules": modules,
        "module_count": len(modules),
        "status": "SOURCE_VERIFIED_ONLY",
        "sdk_installed": False,
        "firmware_built": False,
        "limitations": [
            "This is not a ready Workbench setup; it has no .zew-ready marker.",
            "No SDK/toolchain or Python dependency compatibility has been established.",
            "Git tag availability and transport do not guarantee a five-year offline archive.",
            "No firmware compile, flash, debug or board support is claimed."
        ]
    }


def write_file_once(path, text):
    if path.exists() or path.is_symlink():
        raise VersionError("Refusing to replace existing source evidence: " + str(path))
    with path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(text)


def install(release, destination, current, python, git, allow_network):
    check_release(release)
    destination = check_destination(destination, current)
    if not allow_network:
        raise VersionError("Installation needs explicit --allow-network permission")
    if not python.is_file() or not git.is_file():
        raise VersionError("Use the already-installed Workbench Python and Git executables")
    stage = Path(tempfile.mkdtemp(prefix=".zew-source-stage-", dir=destination.parent))
    print("Preparing isolated staging folder: " + str(stage), flush=True)
    try:
        run([python, "-m", "west", "init", "-m", OFFICIAL_SOURCE,
             "--mr", release, str(stage)], destination.parent, 900)
        run([python, "-m", "west", "update"], stage, 5400)
        frozen, record = capture_sources(stage, python, git, release)
        evidence = stage / ".zew"
        evidence.mkdir(exist_ok=False)
        write_file_once(evidence / "source-frozen.yml", frozen)
        write_file_once(evidence / "source-lock.json",
                        json.dumps(record, indent=2, sort_keys=True) + "\n")
        # Marker means source integrity only. ProjectContext.load must reject it.
        write_file_once(stage / SOURCE_READY, "SOURCE_VERIFIED_ONLY\n")
        if destination.exists():
            raise VersionError("Destination appeared during download; preserving both folders")
        stage.rename(destination)
        return {"status": "source-prepared", "destination": str(destination),
                "release": release, "zephyr_commit": record["zephyr_commit"],
                "modules": record["module_count"], "build_ready": False,
                "next": "SDK compatibility and isolated trial builds are separate milestones."}
    except Exception:
        print("Candidate incomplete; original workspace unchanged. "
              "Inspect staged files before manually removing them: " + str(stage), flush=True)
        raise


def verify_candidate(candidate, python, git):
    candidate = candidate.resolve()
    lock_file = candidate / SOURCE_LOCK
    manifest_file = candidate / SOURCE_FROZEN
    if not (candidate / SOURCE_READY).is_file() or not lock_file.is_file() or not manifest_file.is_file():
        raise VersionError("Select a source-prepared candidate, not an arbitrary checkout")
    lock = json.loads(lock_file.read_text(encoding="utf-8"))
    if lock.get("schema") != 1 or lock.get("kind") != "zephyr-source-only":
        raise VersionError("Unknown source-lock format")
    frozen, now = capture_sources(candidate, python, git, lock["release"])
    if (hashlib.sha256(manifest_file.read_bytes()).hexdigest() != lock["west_manifest_sha256"]
            or frozen != manifest_file.read_text(encoding="utf-8")
            or now["zephyr_commit"] != lock["zephyr_commit"]
            or now["modules"] != lock["modules"]):
        raise VersionError("Candidate checkout differs from its recorded source lock")
    return lock


def compare(application, candidate, python, git):
    from project_versions import load_lock
    original = load_lock(application)
    proposed = verify_candidate(candidate, python, git)
    old_modules = {item["name"]: item for item in original["modules"]}
    new_modules = {item["name"]: item for item in proposed["modules"]}
    differences = []
    if original["zephyr"]["commit"] != proposed["zephyr_commit"]:
        differences.append({"item": "zephyr", "old": original["zephyr"]["commit"],
                            "new": proposed["zephyr_commit"]})
    for name in sorted(old_modules.keys() | new_modules.keys()):
        old = old_modules.get(name)
        new = new_modules.get(name)
        if (old and old["commit"]) != (new and new["commit"]):
            differences.append({"item": "module:" + name, "old": old["commit"] if old else None,
                                "new": new["commit"] if new else None})
    return {"status": "source-comparison-only", "release": proposed["release"],
            "differences": differences, "build_ready": False,
            "next": "Validate an SDK and Python environment, then trial-build a COPY of the application."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "install", "compare"))
    parser.add_argument("--release", default="")
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--original-workspace", type=Path)
    parser.add_argument("--application", type=Path)
    parser.add_argument("--python", type=Path)
    parser.add_argument("--git", type=Path)
    parser.add_argument("--allow-network", action="store_true")
    args = parser.parse_args(argv)
    if args.action in ("plan", "install"):
        if args.original_workspace is None:
            raise VersionError("Specify the original West workspace")
        version = check_release(args.release)
        destination = check_destination(args.destination, args.original_workspace)
        if args.action == "plan":
            return {"status": "plan-only", "release": args.release,
                    "destination": str(destination), "current": str(args.original_workspace.resolve()),
                    "upstream": OFFICIAL_SOURCE, "network_required": True, "source_only": True,
                    "next": "Explicitly approve download before installing in the new path."}
        return install(args.release, destination, args.original_workspace,
                       args.python, args.git, args.allow_network)
    if not args.application or not args.python or not args.git:
        raise VersionError("Source comparison needs the project and existing Python/Git executables")
    return compare(args.application.resolve(), args.destination.resolve(), args.python, args.git)


if __name__ == "__main__":
    try:
        print(json.dumps(main(), sort_keys=True))
    except (VersionError, OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(2)
