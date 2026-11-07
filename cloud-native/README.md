<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr cloud-native foundation

**Experimental sidecar — no Theia app or production service is deployed.**

This folder develops shared interfaces for a possible separate
Eclipse Theia/CDT.cloud Blueprint client. The current main Workbench remains
Eclipse RCP/CDT with Tycho/OSGi; no existing functionality is replaced.

## Run the core tests

Requires Node.js >=22 and npm with registry access to install pinned
TypeScript 5.8.3. From this directory run:

    npm install --ignore-scripts
    npm test

The code provides pure TypeScript services and negative-path unit tests,
not a deployable hardware lab, build daemon, vector database, backend server,
marketplace or HTTPS collector. No network activity occurs on module import.
The transport exists but is never configured automatically. All builds and
workspace changes require adapters and explicit approval.

## Contents

- src/contracts.ts: shared event, registry, hardware, RAG and command DTOs
- src/telemetry.ts: opt-in, coarse allowlisted build aggregation
- src/transport.ts: explicitly configured HTTPS telemetry sender
- src/marketplace.ts: safe review-only installation plans
- src/remote-lab.ts: in-memory board lease model
- src/rag.ts: approved-document semantic vector retrieval
- src/compliance.ts: upstream Zephyr verification command generation
- src/verification.ts: approval-gated command orchestration via injected runner
- proto/zephyr_lab.proto: internal hardware lab gRPC service contract
- native/board_agent.hpp: hardware-facing C++ interface (not implemented)
- protocols/theia-cloud-rpc.ts: intended Theia frontend/backend JSON-RPC API
- theia-extension/src/browser/telemetry-preferences.ts: unmounted Theia preference schema
- docs/ARCHITECTURE.md: system design, security, product boundary and review plan

The unmounted Theia schema cannot be typechecked without a selected Theia
application dependency graph. No UI integration or hardware validation is claimed.

## Safety constraints

Consent alone is not proof of anonymity. Third-party Theia extensions
can implement their own telemetry senders. Never install arbitrary West modules
from Open VSX. Never present a built Devicetree as a physically measured pin
mapping. Never grant the browser direct probe/process/device access.
