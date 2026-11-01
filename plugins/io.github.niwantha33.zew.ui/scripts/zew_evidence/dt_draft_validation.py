# SPDX-License-Identifier: Apache-2.0
"""Opt-in real CMake preflight of a GUIDED, UNSAVED Devicetree override.

The selected source/build are read-only. The exact EDT-approved addition is
applied only to the copied application in the existing trusted CMake scratch
validator. Values are verified using upstream dtlib on scratch zephyr.dts.
"""
from __future__ import annotations

import hashlib
from pathlib import Path

from .collector import InputError, SCHEMA_VERSION
from .peripheral_guide import guide
from .dt_build_validation import validate


def _extract_addition(report: dict) -> str:
    proposal = report["proposal"]
    diff = proposal["visible_diff"]
    if not proposal["available"] or not diff:
        raise InputError("No exact reviewed source addition is available")
    # Single appended hunk from the authoritative headless guide, including
    # the original comment and any UTF-8 source values.
    addition = "".join(line[1:] for line in diff.splitlines(keepends=True)
                       if line.startswith("+") and not line.startswith("+++ "))
    digest = hashlib.sha256(addition.encode("utf-8")).hexdigest()
    if digest != proposal["source_addition_sha256"]:
        raise InputError("Draft source addition hash mismatch")
    return addition


def preflight(build: Path, application: Path, zephyr: Path, python: Path, board: str,
              node: str, overlay: Path, status: str | None = None,
              integer: str | None = None, enum: str | None = None,
              kind: str | None = None, ninja: Path | None = None,
              timeout: int = 300, scratch_parent: Path | None = None,
              bindings: list[Path] | None = None,
              expected_dts_sha256: str | None = None,
              expected_overlay_sha256: str | None = None,
              expected_addition_sha256: str | None = None) -> dict:
    """Run real West/CMake only for an actual verified user-selected guide draft."""
    if overlay is None:
        raise InputError("Choose the exact in-application .overlay target")
    guidance = guide(build, zephyr, node, status=status, integer=integer,
                     enum=enum, app=application, overlay=overlay, kind=kind,
                     bindings=bindings, show_diff=True)
    safe_guide = {
        "status": guidance["status"],
        "generated_dts_sha256": guidance["generated_dts_sha256"],
        "node_sha256": guidance["node_sha256"],
        "property_sha256": guidance["property_sha256"],
        "binding": guidance["source"]["binding"],
        "confidence": guidance["source"]["confidence"],
        "original_overlay_sha256": guidance["overlay"]["original_sha256"],
        "proposed_overlay_sha256": guidance["proposal"]["post_change_overlay_sha256"],
        "source_addition_sha256": guidance["proposal"]["source_addition_sha256"],
    }
    if (expected_dts_sha256 is not None
            and guidance["generated_dts_sha256"] != expected_dts_sha256
            or expected_overlay_sha256 is not None
            and (guidance["overlay"]["original_sha256"] or "0" * 64) != expected_overlay_sha256
            or expected_addition_sha256 is not None
            and guidance["proposal"]["source_addition_sha256"] != expected_addition_sha256):
        raise InputError("Reviewed guided draft changed; request a fresh source preview")
    if guidance["status"] != "draft-requires-real-configuration":
        return {
            "schema_version": SCHEMA_VERSION, "status": "manual-review-required",
            "guide": safe_guide, "real_configure": None,
            "limitations": [
                "No West, CMake or scratch process was launched without binding-grounded source guidance.",
                "Unknown binding/property is manual-review-required; no driver or physical pin was inferred.",
            ],
        }
    addition = _extract_addition(guidance)
    if integer is not None:
        property_name, raw = integer.split("=", 1)
        expected: tuple[str, str | int] = ("int", int(raw, 0))
    elif enum is not None:
        property_name, raw = enum.split("=", 1)
        expected = ("enum", raw)
    else:
        property_name, expected = "status", ("status", status)
    original_overlay_sha256 = (guidance["overlay"]["original_sha256"]
                               or "0" * 64)
    checked = validate(
        build, application, zephyr, python, board,
        overlay=overlay, ninja=ninja, timeout=timeout,
        scratch_parent=scratch_parent, expect_node=node,
        expect_property=property_name, draft_addition=addition,
        draft_original_sha256=original_overlay_sha256,
        draft_expected=expected,
    )
    # The prepared scratch source must be BYTE-IDENTICAL to the reviewed
    # guide proposal before this could possibly claim CMake acceptance.
    same_proposal = (checked["overlay"]["prepared_sha256"] ==
                     guidance["proposal"]["post_change_overlay_sha256"])
    same_original = (checked["selected_build_dts_sha256"] ==
                     guidance["generated_dts_sha256"])
    if not same_proposal or not same_original:
        outcome = "source-identity-unverified"
    else:
        outcome = checked["status"]
    return {
        "schema_version": SCHEMA_VERSION,
        "status": outcome,
        "guide": safe_guide,
        "real_configure": checked,
        "post_generated_value": {
            "requested": True,
            "state": ("exact-value-observed" if outcome == "configured-in-scratch"
                      else "unavailable-or-mismatched"),
            "property_sha256": guidance["property_sha256"],
        },
        "limitations": [
            "Only a copy of the source application was changed. The selected application and original build remain unmodified.",
            "West/CMake and project hooks executed with host permissions. Scratch is NOT a security sandbox.",
            "Successful CMake and matching generated Devicetree value do not prove firmware compilation, flash or runtime.",
            "No pin availability, pad package, electrical routing or physical hardware behavior was verified.",
            "The saved overlay need not be active in the original build; a scratch EXTRA_DTC_OVERLAY_FILE is explicitly added.",
            "Source addition is not exported. Review the separate guided diff before deliberately saving your actual overlay.",
        ],
    }
