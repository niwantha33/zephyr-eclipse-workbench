# SPDX-License-Identifier: Apache-2.0
"""Export pinned managed Python wheel identities for vulnerability scanning only."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

LOCKS = ("setup-lock.json", "esp32s3-python-lock.json", "mcuboot-python-lock.json")
ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "plugins" / "io.github.niwantha33.zew.ui" / "tools"


def inventory(document):
    if not isinstance(document, dict) or document.get("schema") != 1:
        raise ValueError("Unsupported lock schema")
    packages = document.get("packages")
    if not isinstance(packages, list) or not packages:
        raise ValueError("Empty package lock")
    result = {}
    for row in packages:
        if not isinstance(row, dict):
            raise ValueError("Malformed package entry")
        name, version = row.get("name"), row.get("version")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
            raise ValueError("Invalid package name")
        if not isinstance(version, str) or not re.fullmatch(r"[0-9][A-Za-z0-9.!+_-]*", version):
            raise ValueError("Invalid pinned package version")
        digest = row.get("sha256")
        if not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Missing package SHA-256")
        url = row.get("url")
        if not isinstance(url, str):
            raise ValueError("Missing wheel URL")
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or parsed.netloc != "files.pythonhosted.org"
                or parsed.query or parsed.fragment or not parsed.path.endswith(".whl")):
            raise ValueError("Unexpected wheel origin")
        normalized = re.sub(r"[-_.]+", "-", name).lower()
        if normalized in result:
            raise ValueError("Duplicate package in lock")
        result[normalized] = version
    return sorted(result.items())


def export(output):
    output.mkdir(parents=True, exist_ok=True)
    for lock in LOCKS:
        source = TOOLS / lock
        entries = inventory(json.loads(source.read_text(encoding="utf-8")))
        target = output / (source.stem + "-requirements.txt")
        target.write_text("".join(f"{name}=={version}\n" for name, version in entries),
                          encoding="utf-8")
        print(f"{source.name}: {len(entries)} pinned packages")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export(args.output)


if __name__ == "__main__":
    main()
