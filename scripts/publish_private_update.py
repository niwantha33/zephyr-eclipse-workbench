#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Safely build and optionally publish a private p2 update without GitHub runners.

Usage: python scripts/publish_private_update.py [--publish]
Default is build/verify only; --publish requires an authenticated git remote.
Never modifies application sources, Zephyr installs, or existing p2 releases.
"""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from prepare_update import prepare

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "releng/repository/target/repository"


def command(*args, cwd=ROOT, capture=False):
    return subprocess.run([str(arg) for arg in args], cwd=cwd, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None).stdout


def expected_version():
    tree = ET.parse(ROOT / "pom.xml").getroot()
    version = tree.find("{http://maven.apache.org/POM/4.0.0}version").text
    if not version or not re.fullmatch(r"\d+\.\d+\.\d+-SNAPSHOT", version):
        raise ValueError("Expected a versioned SNAPSHOT build; no release produced")
    return version[:-len("-SNAPSHOT")]


def site_version():
    expected = expected_version()
    jars = list((SITE / "features").glob("*.jar"))
    if len(jars) != 1:
        raise ValueError("Expected exactly one workbench feature in the p2 site")
    with zipfile.ZipFile(jars[0]) as item:
        info = ET.fromstring(item.read("feature.xml"))
    version = info.attrib.get("version", "")
    if info.attrib.get("id") != "io.github.niwantha33.zew.feature":
        raise ValueError("Unexpected Eclipse feature ID")
    if not re.fullmatch(re.escape(expected) + r"\.[0-9]{8,14}", version):
        raise ValueError("Packaged feature version does not match project or timestamp: " + version)
    return version


def read_channel(path):
    metadata = dict(line.split("=", 1) for line in path.read_text().splitlines()
                    if "=" in line and not line.startswith("#"))
    return metadata


def preflight(existing, new, release_folder):
    previous = existing.get("version", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+\.\d+", previous):
        raise ValueError("Remote private update manifest is missing a valid version")
    if tuple(map(int, new.split("."))) <= tuple(map(int, previous.split("."))):
        raise ValueError(f"Refusing version downgrade/replacement: {new} <= {previous}")
    if release_folder.exists():
        raise ValueError("Refusing to overwrite previously published release: " + new)


def verify_manifest(channel, folder, revision):
    info = read_channel(channel)
    if info.get("version") != folder.name or info.get("directory") != "releases/" + folder.name:
        raise ValueError("Channel points to another version/directory")
    if info.get("revision") != revision or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Channel must pin exact immutable artifact commit")
    count = int(info.get("files", "0"))
    if not 3 <= count <= 100:
        raise ValueError("Invalid p2 manifest file count")
    for index in range(count):
        name = info[f"file.{index}.path"]
        path = folder / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(folder.resolve()):
            raise ValueError("Unsafe or missing p2 release file")
        data = path.read_bytes()
        if len(data) != int(info[f"file.{index}.size"]) or hashlib.sha256(data).hexdigest() != info[f"file.{index}.sha256"]:
            raise ValueError("Release checksum mismatch: " + name)


def publish(version):
    if command("git", "branch", "--show-current", capture=True).strip() != "main":
        raise ValueError("Publish only from the private repository's main branch")
    command("git", "fetch", "--no-tags", "origin", "main", "updates")
    if command("git", "rev-parse", "HEAD", capture=True) != command("git", "rev-parse", "origin/main", capture=True):
        raise ValueError("Local main is not current; fetch/pull and rebuild first")
    if command("git", "status", "--porcelain", capture=True).strip():
        raise ValueError("Uncommitted source changes; refusing release")
    with tempfile.TemporaryDirectory(prefix="zew-private-update-") as scratch:
        directory = Path(scratch)
        worktree = directory / "updates-tree"
        command("git", "worktree", "add", "--detach", worktree, "origin/updates")
        try:
            folder = worktree / "releases" / version
            preflight(read_channel(worktree / "channel.properties"), version, folder)
            shutil.copytree(SITE, folder)
            command("git", "add", "--", folder.relative_to(worktree), cwd=worktree)
            command("git", "-c", "user.name=zew-release",
                    "-c", "user.email=zew-release@users.noreply.github.com",
                    "commit", "-m", "updates: stage immutable " + version, cwd=worktree)
            revision = command("git", "rev-parse", "HEAD", cwd=worktree, capture=True).strip()
            prepared = directory / "prepared"
            prepare(SITE, revision, prepared, "Private 0.1.11 development preview: New/Import Zephyr projects and independent builds; Windows GUI validation pending.")
            manifest = worktree / "channel.properties"
            shutil.copy2(prepared / "channel.properties", manifest)
            verify_manifest(manifest, folder, revision)
            command("git", "add", "channel.properties", cwd=worktree)
            command("git", "-c", "user.name=zew-release",
                    "-c", "user.email=zew-release@users.noreply.github.com",
                    "commit", "-m", "updates: activate " + version, cwd=worktree)
            # Two commits are pushed at once: no partial update-channel state.
            command("git", "push", "origin", "HEAD:updates", cwd=worktree)
            print("Published private version " + version + "; previous releases preserved.")
        finally:
            command("git", "worktree", "remove", "--force", worktree)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true",
                        help="After all local checks pass, commit and push verified p2 files to private updates")
    args = parser.parse_args()
    print("Running Java/Python safety checks...")
    command(sys.executable, "scripts/test_core.py")
    print("Building the workbench Eclipse p2 package...")
    command("mvn", "--batch-mode", "--no-transfer-progress", "clean", "verify")
    print("Verifying compiled Eclipse p2 archive...")
    command(sys.executable, "scripts/verify_site.py")
    version = site_version()
    print("VERIFIED local candidate:", version)
    if args.publish:
        publish(version)
    else:
        print("Build/verify only: the private update channel was not changed.")


if __name__ == "__main__":
    main()
