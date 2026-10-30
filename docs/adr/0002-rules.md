<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0002 — Deterministic rule packs
Status: proposed; not implemented (2026-10-30).

Rules must have stable ID, scope/version, evidence requirements, severity, confidence calculation, proposed diff and documentation source. Every rule needs a failing and a passing fixture. A missing, ambiguous or malformed artifact cannot yield confirmed root cause. External Python plugins require explicit local trust and sandboxing. The required 30 documented rules are not implemented; G1 must not be declared passed.
