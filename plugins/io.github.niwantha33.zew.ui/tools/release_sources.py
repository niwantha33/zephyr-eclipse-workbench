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


def check_destination(destination, current, original_setup=None):
    if not destination.is_absolute() or (current is not None and not current.is_absolute()):
        raise VersionError("The new workspace and the original workspace need absolute paths")
    if destination.is_symlink():
        raise VersionError("Candidate destination must not be a symbolic link")
    destination = destination.resolve()
    protected = [current.resolve()] if current is not None else []
    if original_setup is not None:
        protected.append(original_setup.resolve())
    for path in protected:
        if destination == path or destination.is_relative_to(path) or path.is_relative_to(destination):
            raise VersionError("The candidate must be separate from the original West workspace "
                               "AND the managed setup folder")
    if destination.exists() or destination.is_symlink():
        raise VersionError("Candidate destination already exists; choose a NEW empty path. "
                           "No existing workspace is ever overwritten.")
    if not destination.parent.is_dir() or destination.parent.is_symlink():
        raise VersionError("Candidate's parent folder must already exist and be a regular directory")
    return destination


def source_environment(tool_root, workspace, python, git, *, network=False, host=None):
    """Use only explicitly selected managed bootstrap tools and candidate config."""
    root = Path(tool_root).resolve(strict=True)
    if not root.is_dir() or not (root / ".zew-managed").is_file():
        raise VersionError("Select a Workbench-managed tools root")
    selected = []
    for label, value in (("Git", git), ("Python", python)):
        if value is None or not Path(value).is_absolute():
            raise VersionError(label + " must be an explicit absolute executable")
        actual = Path(value).resolve(strict=True)
        if not actual.is_file() or not actual.is_relative_to(root):
            raise VersionError(label + " is outside the selected managed tools root")
        selected.append(actual)
    directories = [item.parent for item in selected]
    host = os.environ if host is None else host
    blocked = ("PYTHON", "PIP_", "GIT_", "WEST_", "ZEPHYR_", "CMAKE_", "CONDA_", "VIRTUAL_ENV",
               "GNUARMEMB_", "CROSS_COMPILE")
    result = {key: value for key, value in host.items()
              if key.upper() != "PATH" and not key.upper().startswith(blocked)}
    if os.name == "nt":
        windows = next((v for k, v in host.items() if k.upper() == "SYSTEMROOT"), "")
        if not windows or not Path(windows).is_absolute():
            raise VersionError("Windows SystemRoot is missing or invalid")
        system = Path(windows).resolve(strict=True)
        directories.extend((system / "System32", system))
    if any(os.pathsep in str(directory) for directory in directories):
        raise VersionError("Selected source tool search path contains a PATH delimiter")
    result.update(PATH=os.pathsep.join(dict.fromkeys(map(str, directories))),
                  PYTHONNOUSERSITE="1", PIP_CONFIG_FILE=os.devnull, PIP_NO_INDEX="1",
                  GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
                  GIT_TERMINAL_PROMPT="0", GIT_LFS_SKIP_SMUDGE="1",
                  GIT_ALLOW_PROTOCOL="https:file" if network else "file",
                  WEST_CONFIG_SYSTEM=os.devnull, WEST_CONFIG_GLOBAL=os.devnull,
                  WEST_CONFIG_LOCAL=str(Path(workspace).resolve() / ".west/config"))
    if not network:
        result.update(GIT_OPTIONAL_LOCKS="0", GIT_NO_LAZY_FETCH="1")
    return result


def run(argv, cwd, seconds=1800, capture=False, *, env=None):
    environment = (dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_LFS_SKIP_SMUDGE="1")
                   if env is None else env)
    if env is None:
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


def sdk_recommendation(zephyr):
    """Read the official exact checkout's recommendation; do not infer from a tag."""
    file = zephyr / "SDK_VERSION"
    if not file.is_file() or file.is_symlink():
        return None
    version = file.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise VersionError("Invalid SDK_VERSION in candidate Zephyr checkout")
    return version


def capture_sources(workspace, python, git, release, *, tool_root=None):
    env = source_environment(tool_root, workspace, python, git) if tool_root is not None else None
    frozen, modules, origin = get_manifest(workspace, python, git, env=env)
    if origin != "west-freeze" or any(not module["installed_at_snapshot"] for module in modules):
        raise VersionError("Cannot verify all modules with west manifest --freeze; source setup is incomplete")
    encoded = frozen.encode("utf-8")
    if len(encoded) > MAX_MANIFEST:
        raise VersionError("Frozen West manifest exceeds the inspection limit")
    version = zephyr_version(workspace)
    if version != check_release(release):
        raise VersionError("Zephyr VERSION disagrees with the selected release: " + version)
    head = git_head(git, workspace / "zephyr", env=env)
    ref = run([git, "-C", workspace / "zephyr", "rev-parse",
               "refs/tags/" + release + "^{commit}"], workspace, 30, capture=True, env=env)
    if not SHA.fullmatch(ref) or head != ref:
        raise VersionError("Checked-out Zephyr commit does not match the requested release tag")
    return frozen, {
        "schema": 1,
        "kind": "zephyr-source-only",
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "release": release,
        "zephyr_commit": head,
        "origin": OFFICIAL_SOURCE,
        "recommended_sdk_version": sdk_recommendation(workspace / "zephyr"),
        "west_manifest_sha256": hashlib.sha256(encoded).hexdigest(),
        "modules": modules,
        "module_count": len(modules),
        "status": "SOURCE_VERIFIED_ONLY",
        "sdk_installed": False,
        "firmware_built": False,
        "limitations": [
            "SDK_VERSION from source is a recommendation, not proof of an installed compatible toolchain.",
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


def install(release, destination, current, python, git, allow_network, original_setup=None, *, tool_root=None):
    if current is None:
        raise VersionError("Specify the original West workspace")
    return _install(release, destination, current, python, git, allow_network, original_setup, tool_root=tool_root)


def prepare_fresh(root, release, python, git):
    """Fresh wizard only: separate version-bound owned root; never build-ready."""
    check_release(release)
    root = root.resolve()
    marker = root / ".zew-managed"
    if (release == "v4.4.0" or not marker.is_file() or marker.is_symlink()
            or marker.read_text() != "source-only-win-x64-v1:" + release + "\n"
            or (root / ".zew-ready").exists() or (root / "environment.properties").exists()):
        raise VersionError("Fresh source setup requires a separate version-owned root")
    target = root / "workspace"
    if target.is_symlink():
        raise VersionError("Refusing a linked source destination")
    if target.exists():
        record = verify_candidate(target, python, git, tool_root=root)
        if record["release"] != release:
            raise VersionError("Existing source candidate belongs to a different release")
        return record
    return _install(release, target, None, python, git, True, tool_root=root)


def _install(release, destination, current, python, git, allow_network, original_setup=None, *, tool_root=None):
    check_release(release)
    destination = check_destination(destination, current, original_setup)
    if not allow_network:
        raise VersionError("Installation needs explicit --allow-network permission")
    if python is None or git is None or not python.is_file() or not git.is_file():
        raise VersionError("Use the already-installed Workbench Python and Git executables")
    # Refuse foreign tools before creating any staging directory.
    if tool_root is not None:
        source_environment(tool_root, destination, python, git, network=True)
    stage = Path(tempfile.mkdtemp(prefix=".zew-source-stage-", dir=destination.parent)).resolve()
    print("Preparing isolated staging folder: " + str(stage), flush=True)
    try:
        env = (source_environment(tool_root, stage, python, git, network=True)
               if tool_root is not None else None)
        run([python, "-m", "west", "init", "-m", OFFICIAL_SOURCE,
             "--mr", release, str(stage)], stage, 900, env=env)
        run([python, "-m", "west", "update"], stage, 5400, env=env)
        frozen, record = capture_sources(stage, python, git, release, tool_root=tool_root)
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
                "modules": record["module_count"],
                "recommended_sdk_version": record["recommended_sdk_version"],
                "build_ready": False,
                "next": "SDK compatibility and isolated trial builds are separate milestones."}
    except Exception:
        print("Candidate incomplete; original workspace unchanged. "
              "Inspect staged files before manually removing them: " + str(stage), flush=True)
        raise


def verify_candidate(candidate, python, git, *, tool_root=None):
    candidate = candidate.resolve()
    lock_file = candidate / SOURCE_LOCK
    manifest_file = candidate / SOURCE_FROZEN
    if not (candidate / SOURCE_READY).is_file() or not lock_file.is_file() or not manifest_file.is_file():
        raise VersionError("Select a source-prepared candidate, not an arbitrary checkout")
    lock = json.loads(lock_file.read_text(encoding="utf-8"))
    if lock.get("schema") != 1 or lock.get("kind") != "zephyr-source-only":
        raise VersionError("Unknown source-lock format")
    frozen, now = capture_sources(candidate, python, git, lock["release"], tool_root=tool_root)
    if (hashlib.sha256(manifest_file.read_bytes()).hexdigest() != lock["west_manifest_sha256"]
            or frozen != manifest_file.read_text(encoding="utf-8")
            or now["zephyr_commit"] != lock["zephyr_commit"]
            or now["modules"] != lock["modules"]):
        raise VersionError("Candidate checkout differs from its recorded source lock")
    return lock


def evaluate_base_requirements(text, installed, reviewed):
    """PEP 508 evaluation; never execute requirement directives or fetch packages."""
    from packaging.requirements import Requirement, InvalidRequirement
    from packaging.utils import canonicalize_name
    from packaging.version import Version, InvalidVersion
    rows = []
    if len(text.encode("utf-8")) > 262144:
        raise VersionError("Base requirements exceed inspection limit")
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        row = {"line": number, "requirement": line[:512]}
        try:
            if len(line) > 512:
                raise InvalidRequirement("oversized")
            requirement = Requirement(line)
            name = canonicalize_name(requirement.name)
            if requirement.url or requirement.extras:
                row["status"] = "unsupported-requirement"
            elif requirement.marker and not requirement.marker.evaluate():
                row["status"] = "not-applicable"
            else:
                versions = installed.get(name, [])
                row["installed"] = versions
                row["reviewed"] = reviewed.get(name)
                if not versions:
                    row["status"] = "missing"
                elif len(versions) != 1:
                    row["status"] = "ambiguous"
                elif not requirement.specifier.contains(Version(versions[0]), prereleases=True):
                    row["status"] = "version-mismatch"
                elif reviewed.get(name) != versions[0]:
                    row["status"] = "unreviewed-installed-version"
                else:
                    row["status"] = "matches-reviewed-version"
        except (InvalidRequirement, InvalidVersion, ValueError):
            row["status"] = "unsupported-requirement"
        rows.append(row)
    return {"scope": "upstream base Python requirements only",
            "status": "base-versions-match" if rows and all(
                row["status"] in ("matches-reviewed-version", "not-applicable") for row in rows)
                else "base-requirements-unresolved",
            "requirements": rows, "firmware_built": False, "dependency_compatibility_qualified": False}


def inspect_base_dependencies(root, release, python, git, lock_path):
    """Read only candidate-local metadata after the fresh source verification."""
    from importlib.metadata import distributions
    from packaging.utils import canonicalize_name
    root = Path(root).resolve(strict=True)
    workspace = root / "workspace"
    record = verify_candidate(workspace, python, git, tool_root=root)
    if record["release"] != release or Path(sys.executable).resolve() != Path(python).resolve():
        raise VersionError("Dependency inspection must use the selected release and its own interpreter")
    site = root / "python/Lib/site-packages"
    if not site.is_dir() or not site.resolve().is_relative_to(root):
        raise VersionError("Selected managed Python site-packages directory is missing or escapes setup")
    selected = {}
    for dist in distributions(path=[str(site)]):
        name = dist.metadata.get("Name")
        if name:
            selected.setdefault(canonicalize_name(name), []).append(dist.version)
    lock_path = Path(lock_path).resolve(strict=True)
    if not lock_path.is_relative_to(root) or lock_path.stat().st_size > 4 * 1024 * 1024:
        raise VersionError("Reviewed dependency lock must be inside the selected setup")
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    reviewed = {canonicalize_name(item["name"]): item["version"] for item in lock["packages"]
                if item.get("review") == "approved-build-use"}
    requirements = workspace / "zephyr/scripts/requirements-base.txt"
    if (not requirements.is_file() or requirements.is_symlink()
            or not requirements.resolve().is_relative_to(workspace.resolve())
            or requirements.stat().st_size > 262144):
        raise VersionError("Release base requirements unavailable or unsafe; source remains unqualified")
    text = requirements.read_text(encoding="utf-8")
    env = source_environment(root, workspace, python, git)
    # Compare the file actually inspected to the committed source (normalise CRLF
    # and final newline only). No network or pip resolver is invoked.
    committed = run([git, "-C", workspace / "zephyr", "show",
                     record["zephyr_commit"] + ":scripts/requirements-base.txt"],
                    workspace, 30, capture=True, env=env)
    if text.strip() != committed.strip():
        raise VersionError("Base requirements differ from the verified source commit")
    report = evaluate_base_requirements(text, selected, reviewed)
    report.update(release=release, zephyr_commit=record["zephyr_commit"],
                  requirements_sha256=hashlib.sha256(text.encode("utf-8")).hexdigest())
    return report


def compare(application, candidate, python, git, *, tool_root=None):
    from project_versions import load_lock
    original = load_lock(application)
    proposed = verify_candidate(candidate, python, git, tool_root=tool_root)
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
    parser.add_argument("--original-setup", type=Path)
    parser.add_argument("--application", type=Path)
    parser.add_argument("--python", type=Path)
    parser.add_argument("--git", type=Path)
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--managed-tools-root", type=Path, help="Isolate source operations to selected managed Python/Git")
    args = parser.parse_args(argv)
    if args.action in ("plan", "install"):
        if args.original_workspace is None:
            raise VersionError("Specify the original West workspace")
        version = check_release(args.release)
        destination = check_destination(args.destination, args.original_workspace, args.original_setup)
        if args.action == "plan":
            return {"status": "plan-only", "release": args.release,
                    "destination": str(destination), "current": str(args.original_workspace.resolve()),
                    "upstream": OFFICIAL_SOURCE, "network_required": True, "source_only": True,
                    "next": "Explicitly approve download before installing in the new path."}
        return install(args.release, destination, args.original_workspace,
                       args.python, args.git, args.allow_network, args.original_setup, tool_root=args.managed_tools_root)
    if not args.application or not args.python or not args.git:
        raise VersionError("Source comparison needs the project and existing Python/Git executables")
    return compare(args.application.resolve(), args.destination.resolve(), args.python, args.git,
                   tool_root=args.managed_tools_root)


if __name__ == "__main__":
    try:
        print(json.dumps(main(), sort_keys=True))
    except (VersionError, OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(2)
