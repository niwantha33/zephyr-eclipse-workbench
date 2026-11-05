# SPDX-License-Identifier: Apache-2.0
"""CS-18: versioned inert JSON diagnostic extensions with fixture requirements.

Extensions match already extracted facts. They cannot execute code, open URLs,
write files, parse Kconfig/DTS or elevate a condition to confirmed causation.
"""
from __future__ import annotations

import json
from pathlib import Path
import re

from .collector import InputError, _read_observed
from .studio_rules import RULE_ID, normalize

SCHEMA = "zew.studio-rulepack/1"
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
FIELD = re.compile(r"[a-z][a-z0-9_]{0,63}")


def _version(value):
    if not isinstance(value, str) or not VERSION.fullmatch(value):
        raise InputError("Use an exact major.minor.patch version")
    return tuple(map(int, value.split(".")))


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InputError("Duplicate JSON object key")
        result[key] = value
    return result


def load_json(path: Path, limit=1024 * 1024):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise InputError("Symlinked JSON input rejected")
    sha, _, data = _read_observed(path, limit, capture=True)
    try:
        value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=_unique,
                           parse_constant=lambda value: (_ for _ in ()).throw(InputError("Non-finite JSON value")))
    except (UnicodeError, ValueError, RecursionError) as error:
        raise InputError("Invalid bounded JSON input") from error
    return value, {"path": str(path), "sha256": sha, "kind": "rulepack"}


def _scalar(value):
    return value is None or type(value) in (bool, int) or isinstance(value, str) and len(value) <= 1024


def _matches(conditions, facts):
    return all(term["field"] in facts and facts[term["field"]] == term["equals"]
               for term in conditions)


def load(path: Path):
    pack, provenance = load_json(path, 256 * 1024)
    if not isinstance(pack, dict) or set(pack) != {"schema_version", "pack_id", "version", "rules"}:
        raise InputError("Unsupported rule-pack fields")
    if pack["schema_version"] != SCHEMA:
        raise InputError("Unsupported rule-pack schema version")
    if not isinstance(pack["pack_id"], str) or not re.fullmatch(r"[a-z][a-z0-9.-]{2,79}", pack["pack_id"]):
        raise InputError("Invalid rule-pack identity")
    _version(pack["version"])
    if not isinstance(pack["rules"], list) or not 1 <= len(pack["rules"]) <= 128:
        raise InputError("Rule-pack must contain 1 to 128 rules")
    seen = set()
    for rule in pack["rules"]:
        fields = {"id", "title", "when", "required_evidence", "severity", "confidence",
                  "message", "next_action", "documentation", "scope", "fixtures"}
        if not isinstance(rule, dict) or set(rule) != fields:
            raise InputError("Unsupported rule fields")
        if not isinstance(rule["id"], str) or not RULE_ID.fullmatch(rule["id"]) or rule["id"] in seen:
            raise InputError("Invalid or duplicate rule identifier")
        seen.add(rule["id"])
        for key in ("title", "message", "next_action", "documentation"):
            if not isinstance(rule[key], str) or not 0 < len(rule[key]) <= 2048 or any(ord(c) < 32 for c in rule[key]):
                raise InputError("Invalid rule text")
        if not rule["documentation"].startswith("https://"):
            raise InputError("Rule documentation must be an HTTPS reference; never fetched")
        if rule["severity"] not in ("warning", "error", "info") or rule["confidence"] not in ("derived", "possible", "unknown"):
            raise InputError("Extension cannot assert confirmed root cause")
        terms = rule["when"]
        if not isinstance(terms, list) or not 1 <= len(terms) <= 16:
            raise InputError("Rule requires bounded all-of equality predicates")
        for term in terms:
            if (not isinstance(term, dict) or set(term) != {"field", "equals"}
                    or not isinstance(term["field"], str) or not FIELD.fullmatch(term["field"])
                    or not _scalar(term["equals"])):
                raise InputError("Unsupported rule predicate")
        evidence = rule["required_evidence"]
        if not isinstance(evidence, list) or not 1 <= len(evidence) <= 16 or any(not isinstance(k, str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", k) for k in evidence):
            raise InputError("Rule requires named evidence kinds")
        scope = rule["scope"]
        if not isinstance(scope, dict) or set(scope) - {"zephyr_min", "zephyr_max_exclusive", "vendors", "socs"}:
            raise InputError("Invalid rule scope")
        for key in ("zephyr_min", "zephyr_max_exclusive"):
            if key in scope:
                _version(scope[key])
        if "zephyr_min" in scope and "zephyr_max_exclusive" in scope and _version(scope["zephyr_min"]) >= _version(scope["zephyr_max_exclusive"]):
            raise InputError("Empty version scope")
        for key in ("vendors", "socs"):
            if key in scope and (not isinstance(scope[key], list) or not 1 <= len(scope[key]) <= 64
                                 or any(not isinstance(x, str) or not FIELD.fullmatch(x) for x in scope[key])):
                raise InputError("Invalid vendor/SoC scope")
        fixtures = rule["fixtures"]
        if not isinstance(fixtures, dict) or set(fixtures) != {"passing", "failing"}:
            raise InputError("Every rule needs passing and failing fixtures")
        for facts in fixtures.values():
            if not isinstance(facts, dict) or len(facts) > 32 or any(not isinstance(k, str) or not FIELD.fullmatch(k) or not _scalar(v) for k, v in facts.items()):
                raise InputError("Invalid rule fixture facts")
        if _matches(terms, fixtures["passing"]) or not _matches(terms, fixtures["failing"]):
            raise InputError("Rule's passing/failing fixture contract failed")
    return pack, provenance


def evaluate(paths, observations, zephyr_version=None):
    if not isinstance(paths, list) or not 1 <= len(paths) <= 16:
        raise InputError("Explicitly select 1 to 16 local JSON rule packs")
    if not isinstance(observations, list) or len(observations) > 4096:
        raise InputError("Too many fact observations")
    version = _version(zephyr_version) if zephyr_version is not None else None
    packs, seen, findings, skipped = [], set(), [], []
    for path in paths:
        pack, source = load(path)
        if pack["pack_id"] in seen:
            raise InputError("Duplicate extension pack identity")
        seen.add(pack["pack_id"])
        packs.append({"pack_id": pack["pack_id"], "version": pack["version"], **source})
        for rule in pack["rules"]:
            scope = rule["scope"]
            for observation in observations:
                if not isinstance(observation, dict) or set(observation) != {"id", "image", "subject", "facts", "evidence"}:
                    raise InputError("Invalid extension observation")
                facts = observation["facts"]
                if not isinstance(facts, dict) or len(facts) > 64 or any(not isinstance(k, str) or not FIELD.fullmatch(k) or not _scalar(v) for k, v in facts.items()):
                    raise InputError("Invalid extracted fact map")
                reason = None
                if ("zephyr_min" in scope or "zephyr_max_exclusive" in scope) and version is None:
                    reason = "zephyr-version-unknown"
                elif "zephyr_min" in scope and version < _version(scope["zephyr_min"]):
                    reason = "outside-zephyr-version-scope"
                elif "zephyr_max_exclusive" in scope and version >= _version(scope["zephyr_max_exclusive"]):
                    reason = "outside-zephyr-version-scope"
                elif any(facts.get(key[:-1]) not in scope[key] for key in ("vendors", "socs") if key in scope):
                    reason = "outside-vendor-soc-scope"
                evidence = observation["evidence"]
                if not isinstance(evidence, list) or len(evidence) > 99 or any(not isinstance(e, dict) for e in evidence):
                    raise InputError("Invalid extracted evidence")
                if not set(rule["required_evidence"]).issubset({e.get("kind") for e in evidence}):
                    reason = reason or "required-evidence-missing"
                if reason:
                    skipped.append({"rule_id": rule["id"], "observation": observation["id"], "reason": reason})
                elif _matches(rule["when"], facts):
                    findings.append({"rule_id": rule["id"], "image": observation["image"],
                        "subject": observation["subject"], "severity": rule["severity"],
                        "confidence": rule["confidence"], "message": rule["message"],
                        "next_action": rule["next_action"], "evidence": evidence + [source]})
                if len(findings) + len(skipped) > 8192:
                    raise InputError("Rule evaluation output budget exceeded")
    result = normalize([{"schema_version": SCHEMA, "status": "extensions-evaluated", "findings": findings}])
    result.update(extensions=sorted(packs, key=lambda p: p["pack_id"]),
                  skipped=sorted(skipped, key=lambda x: (x["rule_id"], str(x["observation"]))))
    result["limitations"].append("Extension predicates match supplied facts, whose producer authenticity is not established.")
    return result
