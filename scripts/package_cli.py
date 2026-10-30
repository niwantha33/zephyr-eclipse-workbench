#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build a deterministic, stdlib-only Python zipapp without downloading tools."""
import argparse
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def build(output):
    output = Path(output)
    if output.exists() or output.is_symlink():
        raise ValueError("Choose a new output; existing files are never overwritten")
    output.parent.mkdir(parents=True, exist_ok=True)
    files = {"__main__.py": ROOT / "zew.py", "LICENSE": ROOT / "LICENSE",
             "CLI_REFERENCE.md": ROOT / "docs/CLI_REFERENCE.md",
             "schemas/evidence-graph-0.1.0.json": ROOT / "schemas/evidence-graph-0.1.0.json"}
    files.update({p.relative_to(ROOT).as_posix(): p for p in (ROOT / "zew_evidence").glob("*.py")})
    created = False
    try:
        with output.open("xb") as target:
            created = True
            target.write(b"#!/usr/bin/env python3\n")
            with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as archive:
                for name, source in sorted(files.items()):
                    entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                    entry.create_system = 3
                    entry.external_attr = 0o100644 << 16
                    archive.writestr(entry, source.read_text(encoding="utf-8").encode("utf-8"))
        return hashlib.sha256(output.read_bytes()).hexdigest()
    except BaseException:
        if created:
            output.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(build(args.output) + "  " + args.output.name)
