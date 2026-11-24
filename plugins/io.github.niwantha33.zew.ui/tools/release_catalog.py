#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only, opt-in Zephyr release discovery for the Eclipse source manager.

One command emits base64 TSV records so names/notes never become shell input.
Checking official releases requires an explicit --allow-network flag. This tool
never updates a checkout, SDK, West manifest, Python environment, or project.
"""
import argparse
import base64
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com/repos/zephyrproject-rtos/zephyr/releases?per_page=60"
URL_PREFIX = "https://github.com/zephyrproject-rtos/zephyr/releases/tag/"
STABLE = re.compile(r"^v(0|[1-9][0-9]*)\.([0-9]+)\.([0-9]+)$")
MAX_RESPONSE = 6 * 1024 * 1024
MAX_LOCK = 64 * 1024


def emit(kind, *items):
    print(kind + "\t" + "\t".join(base64.b64encode(str(item).encode("utf-8")).decode("ascii")
                                  for item in items), flush=True)


def parse_version(value):
    match = STABLE.fullmatch(value)
    return tuple(int(part) for part in match.groups()) if match else None


def zephyr_checkout(workspace):
    """Resolve source using upstream West metadata, not a fixed workspace layout."""
    if (workspace / ".west" / "config").is_file():
        try:
            result = subprocess.run(
                [sys.executable, "-m", "west", "list", "zephyr",
                 "--format", "{abspath}"],
                cwd=workspace, shell=False, stdin=subprocess.DEVNULL,
                capture_output=True, text=True, errors="replace", timeout=15)
            candidates = result.stdout.strip().splitlines()
            if result.returncode == 0 and len(candidates) == 1:
                source = Path(candidates[0]).resolve()
                if (source / "VERSION").is_file():
                    return source
        except (OSError, subprocess.TimeoutExpired):
            pass
    # Offline fallback when West is not installed. Never infer arbitrary paths.
    candidates = [p for p in (workspace / "zephyr", workspace / "external" / "zephyr")
                  if (p / "VERSION").is_file()]
    return candidates[0] if len(candidates) == 1 else None


def installed_version(workspace):
    source = zephyr_checkout(workspace)
    if source is None:
        return ""
    version = source / "VERSION"
    if version.is_symlink():
        return ""
    try:
        values = dict(re.findall(r"^(VERSION_MAJOR|VERSION_MINOR|PATCHLEVEL)\s*=\s*(\d+)",
                                 version.read_text(encoding="utf-8"), re.M))
        return "v" + ".".join(values[key] for key in ("VERSION_MAJOR", "VERSION_MINOR", "PATCHLEVEL"))
    except (OSError, KeyError, UnicodeError):
        return ""


def installed_sdk(sdk):
    if sdk is None:
        return ""
    version = sdk / "sdk_version"
    if not version.is_file() or version.is_symlink():
        return ""
    try:
        result = version.read_text(encoding="utf-8").strip()
        return result if re.fullmatch(r"\d+\.\d+\.\d+", result) else ""
    except (OSError, UnicodeError):
        return ""


def prepared_releases(parent):
    """Only inspect recorded source-only candidates immediately beneath a chosen parent."""
    discovered = set()
    if parent is None or not parent.is_dir() or parent.is_symlink():
        return discovered
    try:
        children = sorted(parent.iterdir(), key=lambda path: path.name)[:200]
    except OSError:
        return discovered
    for candidate in children:
        if candidate.is_symlink() or not candidate.is_dir():
            continue
        ready = candidate / ".zew-source-ready"
        locked = candidate / ".zew" / "source-lock.json"
        if not ready.is_file() or ready.is_symlink() or not locked.is_file() or locked.is_symlink():
            continue
        try:
            if locked.stat().st_size > MAX_LOCK:
                continue
            record = json.loads(locked.read_text(encoding="utf-8"))
            release = record.get("release", "")
            if (record.get("kind") == "zephyr-source-only"
                    and record.get("status") == "SOURCE_VERIFIED_ONLY"
                    and parse_version(release)):
                discovered.add(release)
        except (OSError, ValueError, TypeError, UnicodeError):
            continue
    return discovered


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ValueError("Redirect refused for official release metadata")


def official_releases(allow_network, opener=None):
    if not allow_network:
        raise ValueError("A network check requires explicit --allow-network")
    opener = opener or urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(API, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "Zephyr-Eclipse-Workbench-Release-Catalog",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with opener.open(request, timeout=20) as response:
            if response.status != 200:
                raise ValueError("Official release service HTTP " + str(response.status))
            raw = response.read(MAX_RESPONSE + 1)
    except urllib.error.HTTPError as error:
        raise ValueError("Official release service HTTP " + str(error.code)
                         + "; check network or GitHub API limits") from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise ValueError("Cannot check official Zephyr releases: " + str(error)) from error
    if len(raw) > MAX_RESPONSE:
        raise ValueError("Official release response exceeds size limit")
    try:
        payload = json.loads(raw)
    except (UnicodeError, ValueError) as error:
        raise ValueError("Invalid official release metadata") from error
    if not isinstance(payload, list):
        raise ValueError("Official release service did not return a release list")
    releases = {}
    for entry in payload:
        if not isinstance(entry, dict) or entry.get("draft") or entry.get("prerelease"):
            continue
        tag = entry.get("tag_name")
        if not isinstance(tag, str) or parse_version(tag) is None:
            continue
        url = entry.get("html_url")
        if url != URL_PREFIX + tag:
            continue
        published = entry.get("published_at") or ""
        if not isinstance(published, str) or not published:
            continue
        name = entry.get("name") or ("Zephyr " + tag[1:])
        body = entry.get("body") or ""
        if not isinstance(name, str) or not isinstance(body, str):
            continue
        releases[tag] = {
            "tag": tag, "published": published[:10], "title": name[:160],
            "notes": body[:2400], "url": url,
        }
    return sorted(releases.values(), key=lambda item: parse_version(item["tag"]), reverse=True)


def mark(release, current, prepared):
    tag = release["tag"]
    if tag == current:
        return "Current checkout"
    if tag in prepared:
        return "Source-only candidate recorded"
    now = parse_version(current)
    requested = parse_version(tag)
    if now:
        return "Newer release available" if requested > now else "Older release"
    return "Available (current version unknown)"



# Fetch only the selected tag's upstream text files. This is an advisory preview,
# NOT a verified installation or a promise that the user's compiler will work.
RAW_PREFIX = "https://raw.githubusercontent.com/zephyrproject-rtos/zephyr/"
METADATA_FILES = ("SDK_VERSION", "cmake/modules/python.cmake",
                  "scripts/requirements-base.txt", "cmake/modules/zephyr_default.cmake")
MAX_METADATA = 256 * 1024


def release_metadata(tag, filename, opener=None):
    if parse_version(tag) is None or filename not in METADATA_FILES:
        raise ValueError("Invalid stable release or non-allowlisted upstream metadata")
    opener = opener or urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(RAW_PREFIX + tag + "/" + filename,
                                     headers={"User-Agent": "Zephyr-Eclipse-Workbench-Release-Catalog"})
    try:
        with opener.open(request, timeout=15) as response:
            if response.status != 200:
                raise ValueError("Upstream metadata HTTP " + str(response.status))
            data = response.read(MAX_METADATA + 1)
    except urllib.error.HTTPError as error:
        raise ValueError("Release " + tag + " does not provide " + filename
                         + " (HTTP " + str(error.code) + ")") from error
    except (urllib.error.URLError, OSError, TimeoutError) as error:
        raise ValueError("Cannot inspect " + filename + ": " + str(error)) from error
    if len(data) > MAX_METADATA:
        raise ValueError("Upstream metadata exceeds size limit: " + filename)
    try:
        return data.decode("utf-8")
    except UnicodeError as error:
        raise ValueError("Non-text release metadata: " + filename) from error


def requirement_preview(release, workspace, sdk, allow_network, opener=None, cmake=None):
    """Compare exact selected-tag minimums to the CURRENT environment only."""
    if not allow_network:
        raise ValueError("Inspecting version-specific dependencies requires --allow-network")
    if parse_version(release) is None:
        raise ValueError("Select an exact stable Zephyr release first")
    sdk_text, python_text, requirements, cmake_text = (
        release_metadata(release, filename, opener) for filename in METADATA_FILES
    )
    required_sdk = sdk_text.strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", required_sdk):
        raise ValueError("Invalid SDK_VERSION from the selected upstream release")
    python_match = re.search(r"set\(PYTHON_MINIMUM_REQUIRED\s+(\d+\.\d+(?:\.\d+)?)",
                             python_text)
    west_match = re.search(r"(?m)^west\s*>=\s*(\d+\.\d+(?:\.\d+)?)\s*$",
                           requirements)
    cmake_match = re.search(r"cmake_minimum_required\(VERSION\s+(\d+\.\d+(?:\.\d+)?)",
                            cmake_text, re.I)
    if not python_match or not west_match or not cmake_match:
        raise ValueError("Selected release dependency minimums could not be confirmed")
    import importlib.metadata
    try:
        current_west = importlib.metadata.version("west")
    except importlib.metadata.PackageNotFoundError:
        current_west = "Not installed"
    current_python = ".".join(map(str, sys.version_info[:3]))
    current_sdk = installed_sdk(sdk)
    current_zephyr = installed_version(workspace)
    cmake_path = str(cmake) if cmake is not None else shutil.which("cmake")
    current_cmake = "Not found"
    if cmake_path:
        try:
            output = subprocess.run([cmake_path, "--version"], shell=False,
                                    stdin=subprocess.DEVNULL, capture_output=True,
                                    text=True, errors="replace", timeout=10)
            match = re.search(r"cmake version\s+(\d+\.\d+(?:\.\d+)?)", output.stdout, re.I)
            if output.returncode == 0 and match:
                current_cmake = match.group(1)
        except (OSError, subprocess.TimeoutExpired):
            pass

    def at_least(installed, required):
        try:
            lhs = [int(x) for x in re.findall(r"^\d+(?:\.\d+){1,2}", installed)[0].split(".")]
            rhs = [int(x) for x in required.split(".")]
            return tuple((lhs + [0, 0])[:3]) >= tuple((rhs + [0, 0])[:3])
        except (IndexError, ValueError):
            return False

    rows = [
        ("Zephyr source", current_zephyr or "Unknown", release,
         "Separate checkout required", "No in-place update of the current West workspace"),
        ("Zephyr SDK", current_sdk or "Not selected", required_sdk + " (recommended)",
         "Matches recommendation; build unverified" if current_sdk == required_sdk
         else "Needs separate compatibility review",
         "SDK_VERSION is a recommendation from the selected tag, not a full compatibility test"),
        ("Python", current_python, ">=" + python_match.group(1),
         "Minimum met in current interpreter" if at_least(current_python, python_match.group(1))
         else "Current interpreter below minimum",
         "Version alone does not confirm installed wheels/isolated environment"),
        ("West", current_west, ">=" + west_match.group(1),
         "Minimum met in current interpreter" if at_least(current_west, west_match.group(1))
         else "West missing or below minimum",
         "Each West manifest has release-specific module revisions"),
        ("CMake", current_cmake, ">=" + cmake_match.group(1),
         "Minimum met in current CMake" if at_least(current_cmake, cmake_match.group(1))
         else "CMake missing or below minimum",
         "Selected release's upstream Zephyr build scripts; other tools also require verification"),
        ("Python packages", "Current venv only", "Requirements of " + release,
         "Not validated", "Check selected tag's requirements with isolated Python packages"),
        ("West modules and HALs", "Current manifest", "Selected tag's manifest",
         "Not validated", "Must run official West freeze and compare exact module SHAs"),
        ("CMake, Ninja and compilers", "Current installed tools", "Board-dependent",
         "Not validated", "Configure and pristine-build against candidate before switching"),
    ]
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("local", "check", "inspect"))
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--sources-parent", type=Path)
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--release", default="")
    parser.add_argument("--cmake", type=Path)
    args = parser.parse_args(argv)
    current = installed_version(args.workspace)
    sdk_version = installed_sdk(args.sdk)
    recorded = prepared_releases(args.sources_parent)
    emit("STATE", current or "Unknown", sdk_version or "Unknown",
         str(args.workspace), str(args.sdk) if args.sdk else "Not selected")
    if args.action == "local":
        for tag in sorted(recorded | ({current} if parse_version(current) else set()),
                          key=parse_version, reverse=True):
            info = {"tag": tag, "published": "Local", "title": "Local source inventory",
                    "notes": "Source-only records are not proof of SDK or Python compatibility. "
                             "Verify source SHA and modules before using a candidate.",
                    "url": URL_PREFIX + tag}
            emit("RELEASE", tag, info["published"], mark(info, current, recorded),
                 info["title"], info["notes"], info["url"])
        emit("DONE", "Local inspection only. Check releases to request official version metadata.")
        return
    if args.action == "inspect":
        for row in requirement_preview(args.release, args.workspace, args.sdk,
                                       args.allow_network, cmake=args.cmake):
            emit("PACKAGE", *row)
        emit("DONE", "Dependency minimums are advisory; no install or build verification performed.")
        return
    releases = official_releases(args.allow_network)
    for item in releases:
        emit("RELEASE", item["tag"], item["published"], mark(item, current, recorded),
             item["title"], item["notes"], item["url"])
    emit("DONE", str(len(releases)) + " stable official releases. "
         "SDK, West modules and Python requirements are version-specific and NOT prevalidated.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        emit("ERROR", str(error))
        raise SystemExit(2)
