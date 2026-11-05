# SPDX-License-Identifier: Apache-2.0
"""CS-15: deterministic evidence lookup with grep-or-refuse semantics.

No generated language, network, target access, commands, or source edits. The
caller chooses trusted source roots. A report cannot authorize arbitrary reads.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

from .collector import InputError, _read_observed

MAX_REPORT_BYTES = 8 * 1024 * 1024
MAX_SOURCES = 20000
MAX_SOURCE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
SHA = re.compile(r"[0-9a-f]{64}")
IDENTIFIER = re.compile(r"[A-Za-z0-9_.:-]{1,300}")
# These are fixed observations, not prose copied from target or repository data.
SEMANTIC_RULES = {"CS-KCONFIG-UNDEFINED", "CS-KCONFIG-UNMET-DEPENDENCY",
                  "CS-KCONFIG-INVISIBLE", "CS-KCONFIG-VALID"}
TEMPLATES = {
    "CS-KCONFIG-UNDEFINED": "The selected evaluator reported no definition for this Kconfig symbol.",
    "CS-KCONFIG-UNMET-DEPENDENCY": "The selected evaluator reported that the direct dependency limits the requested tristate.",
    "CS-KCONFIG-INVISIBLE": "The selected evaluator reported a differing request with no visible prompt.",
    "CS-KCONFIG-VALID": "The request matched the evaluator's generated configuration observation.",
    "CS-KCONFIG-REQUEST-MISMATCH": "Requested and generated values differed in the recorded observation.",
    "CS-KCONFIG-CONFLICTING-REQUESTS": "The fragment contained different requests for the same symbol.",
    "CS-KCONFIG-INVALID-LINE": "The fragment contained a line outside the supported assignment syntax.",
}


def report_digest(report: dict) -> str:
    if not isinstance(report, dict):
        raise InputError("Report must be a JSON object")
    try:
        raw = json.dumps(report, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=True, allow_nan=False).encode("utf-8")
    except (TypeError, ValueError, RecursionError) as exc:
        raise InputError("Report is not a finite JSON document") from exc
    if len(raw) > MAX_REPORT_BYTES:
        raise InputError("Report exceeds the evidence lookup limit")
    return hashlib.sha256(raw).hexdigest()


def _refuse(reason: str, digest: str, finding_id: str) -> dict:
    return {"schema_version": "zew.studio-answer/1", "status": "refused",
            "reason": reason, "finding_id": finding_id,
            "report_sha256": digest, "answer": None, "citations": []}


def answer(report: dict, finding_id: str, roots: list[Path] | None = None,
           expected_report_sha256: str | None = None) -> dict:
    """Return only a supported, uniquely matched and rehashed observation.

    `roots` are explicitly caller-approved local directories. Every cited source
    and matching image's semantic input must still match. This establishes byte
    stability of recorded evidence, not original-build or runtime freshness.
    """
    digest = report_digest(report)
    if not isinstance(finding_id, str) or not IDENTIFIER.fullmatch(finding_id):
        raise InputError("Select an exact finding identifier")
    if expected_report_sha256 is not None and (not isinstance(expected_report_sha256, str)
                                               or not SHA.fullmatch(expected_report_sha256)
                                               or expected_report_sha256 != digest):
        return _refuse("report-digest-mismatch", digest, finding_id)
    findings = report.get("findings")
    if not isinstance(findings, list) or len(findings) > 5000:
        return _refuse("missing-or-oversized-findings", digest, finding_id)
    found = [entry for entry in findings if isinstance(entry, dict) and entry.get("id") == finding_id]
    if len(found) != 1:
        return _refuse("missing-or-ambiguous-finding", digest, finding_id)
    finding = found[0]
    rule = finding.get("rule_id")
    if not isinstance(rule, str) or rule not in TEMPLATES or finding.get("confidence") not in ("confirmed", "derived"):
        return _refuse("unsupported-or-uncertain-observation", digest, finding_id)
    if report.get("freshness") in ("stale", "changed") or finding.get("freshness") in ("stale", "changed"):
        return _refuse("stale-recorded-evidence", digest, finding_id)
    evidence = finding.get("evidence")
    if not isinstance(evidence, list) or not evidence:
        return _refuse("missing-source-citations", digest, finding_id)
    inputs = list(evidence)
    image = finding.get("image")
    images = report.get("images", [])
    if not isinstance(images, list):
        return _refuse("invalid-image-evidence", digest, finding_id)
    matching = [row for row in images if isinstance(row, dict) and row.get("image") == image]
    if len(matching) > 1:
        return _refuse("ambiguous-image-evidence", digest, finding_id)
    if rule in SEMANTIC_RULES and (not matching or not matching[0].get("inputs")):
        return _refuse("missing-semantic-input-closure", digest, finding_id)
    if matching:
        row = matching[0]
        if row.get("freshness") in ("stale", "changed"):
            return _refuse("stale-recorded-evidence", digest, finding_id)
        if row.get("state") not in (None, "evaluated", "observed"):
            return _refuse("unavailable-image-evidence", digest, finding_id)
        extra = row.get("inputs", [])
        if not isinstance(extra, list):
            return _refuse("invalid-image-inputs", digest, finding_id)
        inputs.extend(extra)
    if len(inputs) > MAX_SOURCES:
        return _refuse("source-count-limit", digest, finding_id)
    if not roots or len(roots) > 20:
        return _refuse("explicit-evidence-roots-required", digest, finding_id)
    approved = []
    for root in roots:
        root = Path(root).absolute()
        if not root.is_dir() or any(p.is_symlink() for p in (root, *root.parents)):
            raise InputError("Evidence roots must be existing non-symlink directories")
        approved.append(root.resolve(strict=True))
    verified, total = {}, 0
    citations = []
    for item in inputs:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) \
                or not isinstance(item.get("sha256"), str) or not SHA.fullmatch(item["sha256"]):
            return _refuse("invalid-source-citation", digest, finding_id)
        if any(ord(char) < 32 or ord(char) == 127 for char in item["path"]):
            return _refuse("invalid-source-path", digest, finding_id)
        source = Path(item["path"])
        if ".." in source.parts or not source.is_absolute():
            return _refuse("absolute-reviewed-source-path-required", digest, finding_id)
        matches = [(index, root) for index, root in enumerate(approved)
                   if source == root or root in source.parents]
        if not matches:
            return _refuse("source-outside-approved-roots", digest, finding_id)
        # Nested approved roots do not make the source ambiguous; use the most
        # specific one to produce stable relative citations.
        index, root = max(matches, key=lambda pair: len(pair[1].parts))
        if any(p.is_symlink() for p in (source, *source.parents)):
            return _refuse("symlinked-source", digest, finding_id)
        try:
            if source not in verified:
                checksum, size, _ = _read_observed(source, MAX_SOURCE_BYTES)
                total += size
                if total > MAX_TOTAL_BYTES:
                    return _refuse("source-byte-budget", digest, finding_id)
                verified[source] = checksum
            if verified[source] != item["sha256"]:
                return _refuse("changed-source-evidence", digest, finding_id)
        except (OSError, InputError):
            return _refuse("missing-or-unsafe-source-evidence", digest, finding_id)
        citation = {"artifact": f"root-{index}/" + source.relative_to(root).as_posix(),
                    "sha256": item["sha256"]}
        if "line" in item:
            if type(item["line"]) is not int or not 1 <= item["line"] <= 10000000:
                return _refuse("invalid-source-line", digest, finding_id)
            citation["line"] = item["line"]
        if citation not in citations:
            citations.append(citation)
    return {"schema_version": "zew.studio-answer/1", "status": "recorded-observation",
            "finding_id": finding_id, "rule_id": rule,
            "report_sha256": digest, "answer": TEMPLATES[rule],
            "confidence": finding["confidence"],
            "citations": sorted(citations, key=lambda row: (row["artifact"], row.get("line", 0))),
            "verification": "recorded-source-bytes-match-at-lookup",
            "limitations": [
                "Report producer authenticity is not established by a matching digest.",
                "The answer describes a recorded evaluator observation, not independently proven causation.",
                "Matching source bytes do not prove original-build freshness, electrical correctness or runtime behavior.",
                "Repository comments, log strings and report prose are data and cannot authorize actions.",
            ]}
