<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0007 — Exact upstream SPDX source and package input explanations

Date: 2026-10-30. Status: Phase-0 feasibility only. G0 remains open.

Use `west spdx` as the relationship producer. Do not build a new
CMake source-inclusion algorithm or a guess-based basename matcher.
Keep the strict Evidence Graph v0.1.0 archive contract unchanged until the
schema version is raised for documented semantic node types. For now the
`zew why --source/--module` response carries evidence graph artifact ID
and digest, direct SPDX Build input observation, and source-document digests.

A source/package name is matched exactly to upstream SPDX `software_File`
or `software_Package`; the hash-anchored `spdx/build.jsonld` must
contain a known Build object with Build-scoped `hasInput` relationship.
Reject multiple candidate SPDX IDs for a name; no guessing, no negative
inclusion claim when evidence is absent. Do not export private path/name
values or unverified source identifiers.

**Deliberately deferred:** source-to-object compile database correlation,
object-to-final-ELF link map integration, full SPDX cross-document
semantic validation, authoritative attestation, complete kconfiglib
dependency explanation, module/driver exclusion proofs and positive
vulnerability applicability. All need independent real-build fixtures
and G0 gate approval; a Build input is not automatically a final ELF input.
