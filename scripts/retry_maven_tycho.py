#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Retry only missing/temporarily untransferred Tycho Maven extensions.

The release build remains fail-closed. Normal compile/test/packaging failures
are never retried or ignored. No Maven repositories, versions or mirrors change.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
MAX_ATTEMPTS = 3
BACKOFF_SECONDS = 10


def tycho_version(pom: Path = ROOT / "pom.xml") -> str:
    root = ET.parse(pom).getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    element = root.find("m:properties/m:tycho.version", ns)
    value = element.text.strip() if element is not None and element.text else ""
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise ValueError("Expected an exact numeric Tycho plugin version in pom.xml")
    return value


def retryable_tycho_failure(line: str, version: str) -> bool:
    # Avoid masking ordinary test, compilation, p2 verification or publishing
    # errors. Only retry when Maven specifically cannot fetch its *own*
    # Tycho build extension from Central.
    artifact = r"org\.eclipse\.tycho:tycho-maven-plugin:(?:jar|pom):" + re.escape(version)
    return bool(re.search(
        r"Could not (?:find|transfer) artifact\s+" + artifact,
        line, flags=re.IGNORECASE))


def run_once(command: list[str], version: str) -> tuple[int, bool]:
    retryable = False
    with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                          errors="replace", bufsize=1) as process:
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            retryable |= retryable_tycho_failure(line, version)
        return process.wait(), retryable


def run_with_retry(command: list[str], version: str,
                   execute: Callable[[list[str], str], tuple[int, bool]] = run_once,
                   pause: Callable[[float], None] = time.sleep,
                   attempts: int = MAX_ATTEMPTS) -> int:
    if attempts < 1:
        raise ValueError("At least one build attempt is required")
    for attempt in range(1, attempts + 1):
        print(f"Tycho clean build: attempt {attempt}/{attempts}", flush=True)
        code, missing = execute(command, version)
        if code == 0:
            return 0
        if not missing:
            print("Maven failed for a non-Tycho-resolution reason; refusing retry.",
                  file=sys.stderr, flush=True)
            return code
        if attempt == attempts:
            print("Tycho extension still unavailable after bounded retries.",
                  file=sys.stderr, flush=True)
            return code
        wait = BACKOFF_SECONDS * attempt
        print(f"::warning::Maven could not resolve Tycho {version}; retrying in "
              f"{wait}s with -U (no version or repository change).", flush=True)
        pause(wait)
    raise AssertionError("Unreachable")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qualifier", required=True,
                        help="Already-reserved, immutable p2 qualifier")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[0-9]{8,14}", args.qualifier):
        parser.error("Invalid p2 qualifier: expected 8–14 decimal digits")
    version = tycho_version()
    command = ["mvn", "--batch-mode", "--no-transfer-progress", "-U",
               f"-DforceContextQualifier={args.qualifier}", "clean", "verify"]
    return run_with_retry(command, version)


if __name__ == "__main__":
    raise SystemExit(main())
