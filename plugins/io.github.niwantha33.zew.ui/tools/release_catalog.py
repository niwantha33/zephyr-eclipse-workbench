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


def installed_version(workspace):
    version = workspace / "zephyr" / "VERSION"
    if not version.is_file() or version.is_symlink():
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("local", "check"))
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--sources-parent", type=Path)
    parser.add_argument("--allow-network", action="store_true")
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
