#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reproducible Zephyr project fingerprints and read-only migration previews.

Use West's frozen manifest, never a home-grown manifest resolver. No network,
checkout, environment change, project source rewrite, or automatic upgrade.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

import yaml

SCHEMA = 1
LOCK = Path(".zew/project-lock.json")
FROZEN = Path(".zew/west-frozen.yml")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
EXCLUDED = {".git", ".west", ".venv", ".zew-build", "build", "__pycache__", ".pytest_cache"}
MAX_FILES = 10000
MAX_FILE_BYTES = 64 * 1024 * 1024


class VersionError(ValueError):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def command(args, cwd, timeout=120):
    try:
        result = subprocess.run(list(map(str, args)), cwd=cwd, check=True, capture_output=True,
                                text=True, errors="replace", stdin=subprocess.DEVNULL,
                                timeout=timeout, shell=False)
        return result.stdout.strip()
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        message = getattr(error, "stderr", "") or str(error)
        raise VersionError("Command failed: " + Path(str(args[0])).name + ": " + message[:700]) from error


def relative_path(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or ".." in path.parts or "\\" in value or ":" in value:
        raise VersionError("Unsafe West project path: " + value)
    return path


def git_head(git, directory):
    sha = command([git, "-C", directory, "rev-parse", "HEAD"], directory)
    if not HEX40.fullmatch(sha):
        raise VersionError("Git did not return a full 40-character commit SHA: " + str(directory))
    return sha


def require_clean(git, directory):
    dirty = command([git, "-C", directory, "status", "--porcelain",
                     "--untracked-files=normal"], directory)
    if dirty:
        raise VersionError("Source checkout has uncommitted files; preserve them in Git before freezing: "
                           + str(directory) + " (" + dirty.splitlines()[0][:120] + ")")


def project_sources(application):
    files = {}
    for path in sorted(application.rglob("*")):
        relative = path.relative_to(application)
        if any(component in EXCLUDED for component in relative.parts):
            continue
        if relative.as_posix() in (LOCK.as_posix(), FROZEN.as_posix()):
            continue
        if path.is_symlink():
            raise VersionError("Project has an unsupported symlink: " + relative.as_posix())
        if not path.is_file():
            continue
        if len(files) >= MAX_FILES or path.stat().st_size > MAX_FILE_BYTES:
            raise VersionError("Project snapshot exceeds bounded source-file limits")
        files[relative.as_posix()] = file_hash(path)
    return files


def zephyr_version(workspace):
    source = workspace / "zephyr" / "VERSION"
    if not source.is_file():
        raise VersionError("Expected a Zephyr source tree with VERSION at " + str(source))
    data = source.read_text(encoding="utf-8")
    values = dict(re.findall(r"^(VERSION_MAJOR|VERSION_MINOR|PATCHLEVEL)\s*=\s*(\d+)", data, re.M))
    if len(values) != 3:
        raise VersionError("Unrecognized Zephyr VERSION file")
    return ".".join(values[key] for key in ("VERSION_MAJOR", "VERSION_MINOR", "PATCHLEVEL"))


def sdk_info(sdk):
    path = sdk / "sdk_version"
    if not path.is_file():
        raise VersionError("Selected Zephyr SDK lacks sdk_version: " + str(sdk))
    # The SDK version is not an artifact integrity proof; tool digests below
    # only attest the installed executables, not every SDK library/header.
    return {"version": path.read_text(encoding="utf-8").strip(),
            "sdk_version_sha256": file_hash(path), "full_sdk_archive_attested": False}


def tool_info(executable, args, cwd):
    exe = Path(executable).resolve()
    if not exe.is_file():
        raise VersionError("Selected tool is not a file: " + str(exe))
    version = command([exe, *args], cwd, 30).splitlines()
    return {"version": version[0][:240] if version else "unknown",
            "executable_sha256": file_hash(exe)}


def get_manifest(workspace, python, git, preferred_origin=None):
    if not (workspace / ".west" / "config").is_file():
        raise VersionError("Select an initialized West workspace containing .west/config")
    if preferred_origin not in (None, "west-freeze", "west-resolve-all-sha"):
        raise VersionError("Unknown saved West manifest collection method")
    try:
        if preferred_origin == "west-resolve-all-sha":
            raise VersionError("Saved manifest used West's SHA-only resolver")
        frozen = command([python, "-m", "west", "manifest", "--freeze"], workspace, 180) + "\n"
        origin = "west-freeze"
    except VersionError:
        if preferred_origin == "west-freeze":
            raise
        # Optimized managed setups may intentionally omit *unused* modules.
        # Zephyr's own resolver is authoritative; do not resolve revisions
        # ourselves. Accept fallback only if ALL resolved revisions are SHAs.
        frozen = command([python, "-m", "west", "manifest", "--resolve"], workspace, 180) + "\n"
        origin = "west-resolve-all-sha"
    try:
        # Preserve numeric-looking Git SHAs as strings, not YAML integers.
        payload = yaml.load(frozen, Loader=yaml.BaseLoader)
        projects = payload["manifest"]["projects"]
    except (KeyError, TypeError, yaml.YAMLError) as error:
        raise VersionError("West returned an invalid resolved manifest") from error
    if not isinstance(projects, list):
        raise VersionError("Resolved manifest has no project list")
    resolved = []
    names = set()
    for project in projects:
        name = project["name"]
        revision = project["revision"]
        if not HEX40.fullmatch(str(revision)) or name in names:
            raise VersionError("West did not pin a unique full SHA for " + str(name)
                               + "; a floating branch or tag cannot become a lock")
        names.add(name)
        relative = relative_path(str(project.get("path", name)))
        directory = workspace.joinpath(*relative.parts)
        present = directory.is_dir()
        if present:
            actual = git_head(git, directory)
            if actual != revision:
                raise VersionError("Module " + name + " is at " + actual +
                                   ", but resolved manifest expects " + revision)
            require_clean(git, directory)
        url = project.get("url", "")
        # Never copy access credentials into a lock committed to Git.
        if isinstance(url, str) and "://" in url:
            from urllib.parse import urlsplit
            parsed = urlsplit(url)
            if parsed.password is not None or (parsed.scheme in ("https", "http") and parsed.username):
                raise VersionError("Manifest project " + name +
                                   " includes credentials in its URL; use credential-free remotes")
        resolved.append({"name": name, "path": relative.as_posix(), "commit": revision,
                         "url": url, "installed_at_snapshot": present})
    zephyr = workspace / "zephyr"
    require_clean(git, zephyr)
    return frozen, sorted(resolved, key=lambda item: item["name"]), origin


def system_record(workspace, python, git, sdk, cmake, ninja, board, application,
                  preferred_origin=None):
    frozen, modules, origin = get_manifest(workspace, python, git, preferred_origin)
    data = {
        "schema": SCHEMA,
        "created_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "zephyr": {"version": zephyr_version(workspace), "commit": git_head(git, workspace / "zephyr")},
        "board": board,
        "west_manifest_sha256": sha256(frozen.encode("utf-8")),
        "manifest_origin": origin,
        "modules": modules,
        "modules_not_installed": [p["name"] for p in modules if not p["installed_at_snapshot"]],
        "sdk": sdk_info(sdk),
        "tools": {
            "python": tool_info(python, ["--version"], workspace),
            "west": {"version": command([python, "-m", "west", "--version"], workspace, 30)},
            "git": tool_info(git, ["--version"], workspace),
            "cmake": tool_info(cmake, ["--version"], workspace),
            "ninja": tool_info(ninja, ["--version"], workspace),
        },
        "application_files": project_sources(application),
        "notes": ["Application files must also be saved in source control or backup.",
                  "Installed SDK libraries, Python wheel archives, and external blobs are not fully attested.",
                  "Matching inputs do not guarantee byte-identical firmware without a verified rebuild."]
    }
    return frozen, data


def load_lock(application):
    path = application / LOCK
    if not path.is_file():
        raise VersionError("No project lock found. Use Snapshot Project Environment first.")
    try:
        lock = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError) as error:
        raise VersionError("Project lock is not valid JSON") from error
    if lock.get("schema") != SCHEMA or not HEX40.fullmatch(lock.get("zephyr", {}).get("commit", "")):
        raise VersionError("Unsupported or invalid project lock")
    return lock


def compare(old, new):
    differences = []
    for section, key in (("zephyr", "version"), ("zephyr", "commit"),
                         ("sdk", "version"), ("board", None)):
        before = old[section] if key is None else old[section][key]
        after = new[section] if key is None else new[section][key]
        if before != after:
            differences.append({"item": section + ("." + key if key else ""),
                                "before": before, "after": after})
    prior = {item["name"]: item for item in old["modules"]}
    current = {item["name"]: item for item in new["modules"]}
    for name in sorted(prior.keys() | current.keys()):
        for field in ("commit", "path", "url", "installed_at_snapshot"):
            before = prior.get(name, {}).get(field)
            after = current.get(name, {}).get(field)
            if before != after:
                differences.append({"item": "module:" + name + ":" + field,
                                    "before": before, "after": after})
    before_sdk = old["sdk"].get("sdk_version_sha256")
    after_sdk = new["sdk"].get("sdk_version_sha256")
    if before_sdk != after_sdk:
        differences.append({"item": "sdk.sdk_version_sha256",
                            "before": before_sdk, "after": after_sdk})
    for name in sorted(old["tools"].keys() | new["tools"].keys()):
        before = old["tools"].get(name)
        after = new["tools"].get(name)
        if before != after:
            differences.append({"item": "tool:" + name, "before": before, "after": after})
    for name in sorted(old["application_files"].keys() | new["application_files"].keys()):
        before = old["application_files"].get(name)
        after = new["application_files"].get(name)
        if before != after:
            differences.append({"item": "source:" + name, "before": before, "after": after})
    return differences


def write_once(path, contents):
    if path.is_symlink():
        raise VersionError("Refusing to overwrite a symlink: " + str(path))
    temporary = path.with_suffix(path.suffix + ".partial")
    if temporary.exists() or temporary.is_symlink():
        raise VersionError("Previous interrupted snapshot needs manual inspection: " + str(temporary))
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(contents)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def snapshot(application, frozen, record):
    target_lock = application / LOCK
    target_manifest = application / FROZEN
    if target_lock.exists() or target_manifest.exists():
        previous = load_lock(application)
        if not target_manifest.is_file() or file_hash(target_manifest) != previous["west_manifest_sha256"]:
            raise VersionError("Existing frozen manifest changed; original lock is not safe to replace")
        changed = compare(previous, record)
        if changed or previous["west_manifest_sha256"] != record["west_manifest_sha256"]:
            raise VersionError("Existing project lock differs; use a new migration copy. Never overwrite its history.")
        return {"status": "unchanged", "lock": str(LOCK)}
    target_lock.parent.mkdir(parents=True, exist_ok=True)
    write_once(target_manifest, frozen)
    try:
        write_once(target_lock, json.dumps(record, indent=2, sort_keys=True) + "\n")
    except Exception:
        target_manifest.unlink(missing_ok=True)
        raise
    return {"status": "created", "lock": str(LOCK), "modules": len(record["modules"]),
            "zephyr": record["zephyr"], "modules_not_installed": record["modules_not_installed"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "verify", "verify-env", "plan"))
    parser.add_argument("--application", required=True, type=Path)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--python", required=True, type=Path)
    parser.add_argument("--git", required=True, type=Path)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--cmake", required=True, type=Path)
    parser.add_argument("--ninja", required=True, type=Path)
    parser.add_argument("--board", required=True)
    parser.add_argument("--candidate-workspace", type=Path)
    parser.add_argument("--candidate-sdk", type=Path)
    parser.add_argument("--candidate-python", type=Path)
    parser.add_argument("--candidate-git", type=Path)
    parser.add_argument("--candidate-cmake", type=Path)
    parser.add_argument("--candidate-ninja", type=Path)
    args = parser.parse_args(argv)
    application = args.application.resolve()
    workspace = args.workspace.resolve()
    if not application.is_dir():
        raise VersionError("Application directory not found: " + str(application))
    if not args.board.strip():
        raise VersionError("Board must be the selected full target identifier")
    if args.action == "plan":
        if not args.candidate_workspace or not args.candidate_sdk:
            raise VersionError("Select a separate, installed candidate West workspace and SDK")
        previous = load_lock(application)
        candidate = args.candidate_workspace.resolve()
        if candidate == workspace or candidate.is_relative_to(workspace) or workspace.is_relative_to(candidate):
            raise VersionError("Upgrade trial requires a separate West workspace; in-place update is forbidden")
        _, new = system_record(candidate, args.candidate_python or args.python,
                               args.candidate_git or args.git, args.candidate_sdk.resolve(),
                               args.candidate_cmake or args.cmake,
                               args.candidate_ninja or args.ninja, args.board, application)
        return {"status": "preview-only", "old": previous["zephyr"],
                "candidate": new["zephyr"], "differences": compare(previous, new),
                "next": "Copy the application, bind it to the new setup, and run a pristine trial build. No files were modified."}
    if args.action in ("verify", "verify-env"):
        previous = load_lock(application)
        frozen_path = application / FROZEN
        if not frozen_path.is_file() or file_hash(frozen_path) != previous["west_manifest_sha256"]:
            raise VersionError("Frozen west manifest missing or changed")
        _, current = system_record(workspace, args.python, args.git, args.sdk.resolve(),
                                   args.cmake, args.ninja, args.board, application,
                                   previous.get("manifest_origin"))
        changes = compare(previous, current)
        if args.action == "verify-env":
            changes = [item for item in changes if not item["item"].startswith("source:")]
        if previous["west_manifest_sha256"] != current["west_manifest_sha256"]:
            changes.insert(0, {"item": "west-manifest", "before": previous["west_manifest_sha256"],
                               "after": current["west_manifest_sha256"]})
        return {"status": "match" if not changes else "drift", "differences": changes}
    old = load_lock(application) if (application / LOCK).exists() else None
    frozen, record = system_record(workspace, args.python, args.git, args.sdk.resolve(),
                                   args.cmake, args.ninja, args.board, application,
                                   old.get("manifest_origin") if old else None)
    return snapshot(application, frozen, record)


if __name__ == "__main__":
    try:
        answer = main()
        print(json.dumps(answer, sort_keys=True))
        sys.exit(0 if answer["status"] != "drift" else 2)
    except (VersionError, OSError, KeyError, TypeError) as error:
        print(json.dumps({"status": "error", "message": str(error)}, sort_keys=True))
        sys.exit(2)
