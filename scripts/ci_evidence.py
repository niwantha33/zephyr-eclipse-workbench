#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail-closed checks on CI references and upstream CodeQL quality telemetry."""
import argparse
import csv
import io
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
METRICS = ("calls with call target", "expressions with known type")
THRESHOLD = 85


def quality_metrics(csv_text):
    """Read the actual ExtractorInformation metric rows; never infer success."""
    found = {}
    for row in csv.reader(io.StringIO(csv_text)):
        if not row:
            continue
        if len(row) != 2:
            raise ValueError("Expected two telemetry columns")
        name, value = row
        if name not in {"Percentage of " + metric for metric in METRICS}:
            continue
        if name in found or not re.fullmatch(r"[0-9]{1,3}", value):
            raise ValueError("Invalid or duplicate CodeQL metric: " + name)
        number = int(value)
        if not 0 <= number <= 100:
            raise ValueError("Out-of-range CodeQL metric: " + name)
        found[name] = number
    if len(found) != len(METRICS):
        raise ValueError("Missing required CodeQL quality telemetry")
    return found


def passes(metrics):
    return all(metrics.get("Percentage of " + metric, -1) >= THRESHOLD
               for metric in METRICS)


def audit_workflows(directory):
    errors = []
    files = sorted(directory.glob("*.yml")) + sorted(directory.glob("*.yaml"))
    if not files:
        raise ValueError("No CI workflows found")
    for path in files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = re.match(r"^\s*(?:-\s*)?uses:\s*([^#]+)", line)
            if match:
                value = match.group(1).strip().strip("'\"")
                if not value.startswith("./") and not re.fullmatch(
                    r"[^@\s]+@[0-9a-f]{40}", value
                ):
                    errors.append(f"{path.name}:{number}: unpinned action {value}")
            match = re.match(r"^\s*image:\s*([^#]+)", line)
            if match:
                value = match.group(1).strip().strip("'\"")
                if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", value):
                    errors.append(f"{path.name}:{number}: unpinned image {value}")
    return errors


def codeql():
    database = Path(os.environ["CODEQL_DATABASE"])
    if not database.is_dir():
        raise ValueError("CodeQL database directory not found")
    candidates = list((database / "results").rglob("Telemetry/ExtractorInformation.bqrs"))
    if len(candidates) != 1:
        raise ValueError("Expected exactly one upstream Java telemetry result")
    result = subprocess.run(
        [os.environ["CODEQL_CLI"], "bqrs", "decode", "--format=csv",
         "--result-set=#select", str(candidates[0])],
        check=True, capture_output=True, text=True, encoding="utf-8"
    )
    metrics = quality_metrics(result.stdout)
    lines = ["## CodeQL Java extraction quality",
             "Commit: " + os.environ["GITHUB_SHA"],
             "", "| Metric | Observed | Required |", "| --- | --- | --- |"]
    for metric in METRICS:
        value = metrics["Percentage of " + metric]
        lines.append(f"| {metric} | {value}% | {THRESHOLD}% |")
    lines.append("\n" + ("Thresholds met." if passes(metrics) else "Quality gate failed."))
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0 if passes(metrics) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["audit", "codeql"])
    args = parser.parse_args()
    if args.command == "codeql":
        return codeql()
    errors = audit_workflows(ROOT / ".github" / "workflows")
    if errors:
        print("\n".join(errors))
        return 1
    print("Workflow action and container references are pinned; this is not a vulnerability scan.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
