#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Installed native bridge for real CMake preflight of an UNSAVED guided draft.

The exact headless implementation is packaged as checked byte-for-byte.
Only bounded enum/hash/count evidence crosses the native UI boundary.
Never print raw CMake output, overlay contents, project paths or exceptions.
"""
from __future__ import annotations

import argparse
import base64
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from zew_evidence.collector import InputError
from zew_evidence.dt_draft_validation import preflight

ZERO = "0" * 64
SHA = re.compile(r"[a-f0-9]{64}")
REASONS = frozenset({
    "real-zephyr-cmake-accepted",
    "requested-node-or-property-not-observed",
    "scratch-build-identity-or-overlay-unverified",
    "source-or-build-changed-during-check",
    "configure-timeout-or-launch-failed",
    "undefined-devicetree-node-or-label",
    "required-binding-property",
    "devicetree-syntax-or-preprocessing",
    "kconfig-configuration",
    "missing-tool-or-module",
    "cmake-configure-failed",
    "binding-evidence-incomplete",
    "reviewed-draft-changed",
    "invalid-or-missing-input",
})


def evidence(report, node, prop):
    state = report["status"]
    guide = report["guide"]
    check = report.get("real_configure") or {}
    configured = state == "configured-in-scratch"
    if configured:
        outcome = "configured"
    elif state == "manual-review-required":
        outcome = "incomplete"
    elif state in ("unavailable", "source-identity-unverified", "input-changed",
                   "identity-unverified"):
        outcome = "unavailable"
    else:
        outcome = "failed"
    diagnostic = check.get("diagnostic") or {}
    reason = diagnostic.get("code") if check else "binding-evidence-incomplete"
    if state == "source-identity-unverified":
        reason = "scratch-build-identity-or-overlay-unverified"
    if reason not in REASONS:
        reason = "invalid-or-missing-input"
    if configured and reason != "real-zephyr-cmake-accepted":
        raise InputError("Contradictory CMake success")
    prepared = (check.get("overlay") or {}).get("prepared_sha256") or ZERO
    scratch = check.get("scratch_generated_dts_sha256") or ZERO
    observed = report.get("post_generated_value") or {}
    value = observed.get("state") or "not-evaluated"
    if value not in ("exact-value-observed", "unavailable-or-mismatched", "not-evaluated"):
        raise InputError("Unknown generated Devicetree value evidence")
    staged = ((check.get("overlay") or {}).get("draft_staged_in_scratch_only") is True)
    if configured and (not staged or value != "exact-value-observed"
                       or prepared != guide["proposed_overlay_sha256"]
                       or scratch == ZERO):
        raise InputError("Unproven proposed source acceptance")
    expected_prop_hash = __import__("hashlib").sha256(prop.encode("utf-8")).hexdigest()
    expected_node_hash = __import__("hashlib").sha256(node.encode("utf-8")).hexdigest()
    if guide["property_sha256"] != expected_prop_hash or guide["node_sha256"] != expected_node_hash:
        raise InputError("Selection evidence mismatch")
    fields = [
        outcome, reason, guide["generated_dts_sha256"], scratch,
        guide["original_overlay_sha256"] or ZERO,
        guide["proposed_overlay_sha256"] or ZERO, prepared,
        guide["node_sha256"], guide["property_sha256"], value,
        "staged" if staged else "not-staged",
        "identity-verified" if configured else "identity-unknown",
        str(check.get("application_snapshot_files") or 0),
    ]
    for item in fields[2:9]:
        if not SHA.fullmatch(item):
            raise InputError("Invalid generated evidence digest")
    return fields


def wire(fields):
    print("ZEW-DRAFT-CMAKE-1")
    print("\t".join(base64.b64encode(str(value).encode("ascii")).decode("ascii")
                    for value in fields))


def main(argv=None):
    parser = argparse.ArgumentParser()
    for name in ("build", "application", "zephyr", "node", "overlay", "board",
                 "expected-dts-sha256", "expected-overlay-sha256",
                 "expected-addition-sha256"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--ninja")
    selector = parser.add_mutually_exclusive_group(required=True)
    selector.add_argument("--status", choices=("okay", "disabled"))
    selector.add_argument("--set-int")
    selector.add_argument("--set-enum")
    opts = parser.parse_args(argv)
    prop = "status"
    if opts.set_int is not None:
        prop = opts.set_int.split("=", 1)[0]
    elif opts.set_enum is not None:
        prop = opts.set_enum.split("=", 1)[0]
    fallback = ["unavailable", "invalid-or-missing-input", ZERO, ZERO,
                ZERO, ZERO, ZERO, ZERO, ZERO,
                "not-evaluated", "not-staged", "identity-unknown", "0"]
    try:
        selected = (opts.expected_dts_sha256, opts.expected_overlay_sha256,
                    opts.expected_addition_sha256)
        if not all(SHA.fullmatch(value) for value in selected):
            raise InputError("Malformed reviewed source identity")
        report = preflight(
            Path(opts.build), Path(opts.application), Path(opts.zephyr),
            Path(sys.executable), opts.board, opts.node, Path(opts.overlay),
            status=opts.status, integer=opts.set_int, enum=opts.set_enum,
            ninja=Path(opts.ninja) if opts.ninja else None,
            expected_dts_sha256=opts.expected_dts_sha256,
            expected_overlay_sha256=opts.expected_overlay_sha256,
            expected_addition_sha256=opts.expected_addition_sha256)
        fields = evidence(report, opts.node, prop)
    except (InputError, OSError, ValueError, KeyError, TypeError, UnicodeError) as error:
        fields = fallback.copy()
        if isinstance(error, InputError) and str(error).startswith(
                "Reviewed guided draft changed"):
            fields[1] = "reviewed-draft-changed"
    wire(fields)
    return 0 if fields[0] == "configured" else 1


if __name__ == "__main__":
    raise SystemExit(main())
