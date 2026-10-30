<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0006 — Read-only upstream SPDX build-evidence adapter

Date: 2026-10-30. Status: proposed, Phase-0 feasibility only. G0 not passed.

**Problem:** Zephyr 4.5 provides SPDX 3 Build Profile with file-API-derived
source, object, tool and build relationships. Recreating that generator
would duplicate upstream code and diverge on toolchain and sysbuild detail.

**Decision:** use `west spdx` output as an upstream source of truth.
Start with a bounded, deterministic, read-only `zew sbom` status command:
record exact file digests, observe existing SPDX 3 build relationships, show
missing metadata prerequisites and their upstream command. Do not run
generator commands silently. Treat all JSON-LD fields as untrusted, never
fetch JSON-LD contexts, and export no absolute source/tool paths by default.
Individual images remain explicit. Report unknown actual ELF binding.

**Deferred:** complete SPDX 2.3/3.0 validators, robust source/object graph
normalisation and compiled/configuration applicability. Those require
upstream fixtures, FMEA, supply-chain and licensing reviews, plus G0/G1
evidence. This adapter does not start or pass R2.
