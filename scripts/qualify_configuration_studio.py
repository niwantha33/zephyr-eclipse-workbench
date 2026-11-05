#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""CS-19 integration qualification; no gate passes from a roadmap checkbox.

Runs local core checks, resource parity, and a genuinely isolated CLI zipapp
smoke. Packaging/UI/hardware remain separately evidenced and explicitly unknown.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def check(name, argv, cwd, timeout):
    try:
        result = subprocess.run(argv, cwd=cwd, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        return {"id": name, "status": "passed" if result.returncode == 0 else "failed",
                "exit_code": result.returncode,
                "output_sha256": hashlib.sha256(result.stdout).hexdigest()}
    except (OSError, subprocess.TimeoutExpired):
        return {"id": name, "status": "unavailable-or-timeout", "exit_code": None}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", action="store_true", help="Run full Java/Python core suite; requires JDK21")
    args = parser.parse_args(argv)
    checks = [check("installed-headless-resource-parity", [sys.executable, str(ROOT / "scripts/sync_studio_resources.py"), "--check"], ROOT, 30)]
    checks.append(check("studio-regression-fixtures", [sys.executable, "-m", "unittest", "discover", "-s", "tests/tools", "-p", "test_studio_*.py"], ROOT, 90))
    if args.core:
        checks.append(check("complete-core-suite", [sys.executable, str(ROOT / "scripts/test_core.py")], ROOT, 600))
    else:
        checks.append({"id": "complete-core-suite", "status": "not-requested"})
    with tempfile.TemporaryDirectory(prefix="zew-studio-qualification-Ω ") as temporary:
        target = Path(temporary)
        archive = target / "zew.pyz"
        checks.append(check("package-standalone-cli", [sys.executable, str(ROOT / "scripts/package_cli.py"), "--output", str(archive)], ROOT, 30))
        checks.append(check("isolated-cli-command-discovery", [sys.executable, "-I", str(archive), "studio-kconfig", "--help"], target, 30))
    for ident in ("exact-head-linux-p2", "installed-eclipse-ui", "windows-managed-firmware", "physical-hardware"):
        checks.append({"id": ident, "status": "separate-evidence-required"})
    failed = any(row["status"] in ("failed", "unavailable-or-timeout") for row in checks)
    print(json.dumps({"schema_version": "zew.studio-qualification/1", "status": "failed" if failed else "local-checks-only",
        "checks": checks, "release_gate": "open", "milestone_acceptance": "partial-evidence-not-release-completion",
        "limitations": ["CS-19 is the integration/qualification interpretation of the user's request; the recovered roadmap ends at CS-18.",
                        "Skipped optional fixtures do not establish that their upstream semantic coverage passed.",
                        "A successful local run cannot stand in for native Windows, installed UI or hardware acceptance."]}, sort_keys=True, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
