<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0008 — EmbeddedHub board discovery in the existing Eclipse frontend

Date: 2026-11-07. Status: proposed / experimental.

## Context
The owner supplied an EmbeddedHub ecosystem business plan and explicitly
prioritised Eclipse CDT over migration to Theia/CDT.cloud. Evidence Engine
Program v2 disallows a redundant second IDE frontend and unverified product
claims; G0 remains open. HWMv2 hardware metadata is already used by the
project wizard and property page.

## Decision
Expose the **same** selected-checkout HWMv2 helper output through a compact,
read-only discovery surface in the existing Eclipse UI. This is intentionally
not a new hardware-configuration studio. The existing Configuration Studio
remains the only configuration editing/navigation workflow. No automatic
downloads, telemetry or vendor promotion. The new tabular model stays
headless and does not interpret YAML or infer validated hardware capabilities.

## Consequences
The user can browse local vendor/board/SoC/qualifier metadata and return to
the current project. A future vendor/samples system can consume independently
verified content, but cannot infer endorsement or certification merely
because a board exists in Zephyr. This business-plan experiment does not
advance G0, approve a new release train, or authorise marketplace/analytics
services. Any broader scope requires a separate ADR and gate review.
