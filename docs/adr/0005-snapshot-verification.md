<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0005 — Strict observational graph import and explicit artifact verification

Status: implemented Phase-0 feasibility experiment; does not pass G0 or start R1.
Date: 2026-10-30.

## Problem

An archive's matching SHA-256 manifest only establishes internal byte integrity.
It does not authenticate its producer, prove that a Kconfig observation is linked
to its claimed artifact, or show whether the current build still contains those
bytes. Imported schema-0.1.0 metadata previously accepted extra fields, inconsistent
observation links and numeric values under private configuration names.

## Decision

Fail closed on metadata outside the exact observational prototype contract.
Check image-relative paths, artifact identity, Kconfig/artifact hash linkage and
complete relationships. Reject duplicate JSON keys and unexpected metadata.
Schema-0.1.0 remains unchanged for graphs actually emitted by the collector;
future semantic node types require explicit schema evolution.

Add `zew verify --input ZIP --build DIRECTORY` as a read-only comparison of
recorded artifact bytes with a selected directory. Emit expected/observed hashes,
source provenance and matching/changed/missing states. Return 1 for mismatches
or zero recorded artifacts, 2 for unsafe/invalid inputs, and 0 for recorded matches.
Do not equate a match with source freshness, a successful build or flashed identity.
Keep unknown producing tools/versions unknown. Parse Workbench receipts from the
same bounded byte snapshot that produced their hash.

Ship a deterministic stdlib zipapp for independent evaluation outside Eclipse.
Include source, licence, CLI documentation and schema. Normalise source text line
endings and archive timestamps. This unsigned prototype is not a production asset.

## Validation and limits

Adversarial fixtures cover inconsistent provenance, numeric-secret values,
unsafe image roots, malformed types, duplicate graph relationships, duplicate JSON
keys and deep JSON. Verification covers missing/changed/matching artifacts,
explicit multi-image layouts, no-evidence states, symlinks and read-only behaviour.
The packaged CLI is tested from an unrelated directory without PYTHONPATH.

A malicious producer can still forge an internally consistent graph. No signature,
attestation, driver reasoning, source-to-image causality or G1 accuracy is claimed.
Hands-on competitor scenarios and the full redistribution audit remain G0 gates.
