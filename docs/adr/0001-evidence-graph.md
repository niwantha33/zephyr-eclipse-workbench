<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0001 — Evidence graph and narrower market gap
Status: proposed, pending G0 (2026-10-30).

Ac6 supports broad IDE/MCP and SPDX workflows; Zephyr SPDX 3 already maps compiled sources to build outputs. Decision: consume upstream metadata, build only a versioned **normalised evidence graph** that makes traceable and offline comparisons possible. Schema 0.1.0 supports project/image/artifact/observed-Kconfig nodes only; it must not fake driver-inclusion edges. Each observation records artifact path, SHA-256, extraction method and unknown producing-tool version until known. JSON is stable. Alternatives rejected: copy west spdx, simplified DTS/Kconfig semantic parsers, generic second IDE. Remaining graph node/edge model requires feasibility and G0 review.
