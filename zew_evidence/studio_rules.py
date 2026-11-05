# SPDX-License-Identifier: Apache-2.0
"""CS-08 deterministic diagnosis contract shared by CLI reports and native IDs.

Conditions are established by upstream-backed adapters, never a guessed rule
parser. This layer normalizes identity, confidence and evidence, and combines
reports without promoting a candidate condition to a confirmed root cause.
"""
from __future__ import annotations

import hashlib
import json
import re

from .collector import InputError

SCHEMA = "zew.studio-diagnosis/1"
MAX_FINDINGS = 8192
MAX_REPORT_BYTES = 8 * 1024 * 1024
RULE_ID = re.compile(r"[A-Z][A-Z0-9-]{2,95}")
SHA = re.compile(r"[a-f0-9]{64}")
CONFIDENCES = {"confirmed", "derived", "possible", "unknown"}
SEVERITIES = {"error", "warning", "info"}


def _encoded(report, **options):
    try:
        return json.dumps(report, sort_keys=True, ensure_ascii=True, allow_nan=False, **options)
    except (TypeError, ValueError, RecursionError) as error:
        raise InputError("Diagnosis report is not bounded JSON data") from error


def _text(value, maximum, nonempty=False):
    return (isinstance(value, str) and len(value) <= maximum
            and (bool(value) or not nonempty)
            and not any(ord(char) < 32 or ord(char) == 127 for char in value))


def canonical_hash(report):
    return hashlib.sha256(_encoded(report, separators=(",", ":")).encode()).hexdigest()


def normalize(reports):
    if not isinstance(reports, list) or len(reports) > 64:
        raise InputError("At most 64 diagnosis reports are allowed")
    results, sources, limitations = {}, [], set()
    total = 0
    for report in reports:
        if not isinstance(report, dict):
            raise InputError("Diagnosis report must be an object")
        encoded = _encoded(report)
        total += len(encoded.encode())
        if total > MAX_REPORT_BYTES:
            raise InputError("Diagnosis reports exceed the bounded input size")
        schema, status = report.get("schema_version", "unknown"), report.get("status", "unknown")
        if not _text(schema, 128, True) or not _text(status, 128, True):
            raise InputError("Invalid report schema or status")
        sources.append({"schema_version": schema,
                        "sha256": canonical_hash(report), "status": status})
        report_limits = report.get("limitations", [])
        if not isinstance(report_limits, list) or len(report_limits) > 256:
            raise InputError("Report limitations must be a bounded array")
        for limit in report_limits:
            if not _text(limit, 4096):
                raise InputError("Invalid report limitation")
            limitations.add(limit)
        findings = report.get("findings", [])
        if not isinstance(findings, list):
            raise InputError("Findings must be an array")
        for raw in findings:
            if not isinstance(raw, dict):
                raise InputError("Finding must be an object")
            rule = raw.get("rule_id")
            if not isinstance(rule, str) or not RULE_ID.fullmatch(rule):
                raise InputError("Invalid deterministic rule identifier")
            image, subject = raw.get("image", "unknown"), raw.get("symbol", raw.get("subject", "unknown"))
            if not all(_text(value, 512, True) for value in (image, subject)):
                raise InputError("Invalid finding image or subject")
            severity, confidence = raw.get("severity", "warning"), raw.get("confidence", "unknown")
            if (not isinstance(severity, str) or not isinstance(confidence, str)
                    or severity not in SEVERITIES or confidence not in CONFIDENCES):
                raise InputError("Invalid finding severity or confidence")
            evidence = raw.get("evidence", [])
            if not isinstance(evidence, list) or len(evidence) > 100:
                raise InputError("Invalid finding evidence")
            checked = []
            for source in evidence:
                if not isinstance(source, dict):
                    raise InputError("Evidence must be an object")
                path, sha = source.get("path"), source.get("sha256")
                if not isinstance(path, str) or not path or len(path) > 4096 or any(ord(c) < 32 for c in path):
                    raise InputError("Evidence needs a bounded source path")
                if not isinstance(sha, str) or not SHA.fullmatch(sha):
                    raise InputError("Evidence needs an exact SHA-256")
                item = {"path": path, "sha256": sha}
                if "line" in source:
                    if type(source["line"]) is not int or not 1 <= source["line"] <= 10_000_000:
                        raise InputError("Invalid evidence source line")
                    item["line"] = source["line"]
                if "kind" in source:
                    if not _text(source["kind"], 100, True):
                        raise InputError("Invalid evidence kind")
                    item["kind"] = source["kind"]
                checked.append(item)
            if not checked:
                confidence = "unknown"
            message, action = raw.get("message", ""), raw.get("next_action", "Inspect missing evidence.")
            if not all(_text(value, 8192) for value in (message, action)):
                raise InputError("Invalid finding text")
            identifier = f"{rule}:{image}:{subject}"
            finding = {"id": identifier, "rule_id": rule, "image": image, "subject": subject,
                       "severity": severity, "confidence": confidence,
                       "message": message, "next_action": action,
                       "evidence": sorted(checked, key=lambda x: (x["path"], x.get("line", 0), x["sha256"]))}
            if identifier in results and results[identifier] != finding:
                raise InputError("Conflicting findings use the same deterministic identity")
            results[identifier] = finding
            if len(results) > MAX_FINDINGS:
                raise InputError("Too many diagnosis findings")
    rows = [results[key] for key in sorted(results)]
    return {"schema_version": SCHEMA, "status": "observed-findings" if rows else "no-findings-in-supplied-evidence",
            "freshness": "unverified", "findings": rows,
            "sources": sorted(sources, key=lambda x: x["sha256"]),
            "counts": {key: sum(row["severity"] == key for row in rows) for key in sorted(SEVERITIES)},
            "limitations": sorted(limitations | {"No findings does not prove a valid build or functioning hardware.",
                "Evidence hashes identify inspected bytes; original-build freshness and root cause remain separate."})}
