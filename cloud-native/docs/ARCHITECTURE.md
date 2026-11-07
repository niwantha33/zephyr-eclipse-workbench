<!-- SPDX-License-Identifier: Apache-2.0 -->
# Cloud-native sidecar architecture (design + isolated implementation)

Status: experimental, not integrated into the Eclipse application. G0 remains OPEN.
This path is a separate proposed Theia/CDT.cloud product and does not change
existing Eclipse RCP/OSGi/West user workflows.

## Existing repository baseline

The primary application is Java 21, Tycho, Eclipse CDT and p2, not Theia.
The existing docs/EVIDENCE_ENGINE_PROGRAM_V2.md calls for offline-first,
no telemetry and no second frontend in that release program. The product
owner's cloud-native request proposes changing that direction. Before shipping,
approve a new ADR, privacy review, and product boundary. Do not claim existing
Eclipse features have migrated.

## Deployment boundaries

Browser/Electron Theia frontend <-> authenticated Theia backend JSON-RPC.
The backend owns processes, workspace writes, consent and all network actions.
Backend <-> lab gateway is mTLS gRPC; the physical runner is a native C++
process and may talk to OpenOCD, West, GDB or UART. The browser's debug client
uses normal DAP via the trusted CDT.cloud adapter and gateway, not a custom
browser implementation of GDB/MI. UI UART is proxied from the agent through
the gateway with authenticated WebSocket or binary gRPC/WebSocket adaptation;
WebSerial is a different explicitly selected local-only option.

A small common TypeScript core exists in src/. It is actually implemented and
unit tested, but is not a runnable Theia extension, agent, marketplace daemon,
telemetry collector, AI model service or hosted hardware lab.

## 1. Telemetry: opt-in, minimal and auditable

Use Theia 1.74+ @theia/telemetry service and its REMOTE sink only once a Theia
app exists. Keep telemetry.telemetryLevel off by default. Add a second
build-specific consent gate; do not enable all third-party extension telemetry
when the user opts into Zephyr build metrics. Backend consent always wins.
Implement an on-screen preview and explicit Send action. The standalone
PrivateBuildTelemetry core currently has no automatic timer or HTTP client.

Payload schemaVersion 1 contains counts by allowlisted MCU family, outcome,
toolchain category and PUBLIC major.minor SDK/compiler versions. Never collect
source, path, project name, manifest content, raw build logs, IP/session IDs,
user IDs, board serials or exception stack traces. Numeric version validator
rejects free-form values. A bucket must contain at least 20 observations
before sending. This is data minimisation, not a guarantee of anonymity:
rare combination analysis, small cohorts and retained server IP logs can
still re-identify users. Collector policy must avoid source IP retention,
revalidate schema and rate-limit. Use TLS, pin intended service origin,
bound request sizes, retry without losing an approved payload, support
erasure and give a retention notice. Opt-out immediately discards local data.
Do not expose a remote collector until independent privacy/legal review.

Theia's telemetry consent covers participating extensions but third-party
extension senders can have independent destinations; review plugin permissions
separately.

## 2. Marketplace: two distinct registries

Open VSX provides *editor extensions*. Zephyr modules (Modbus, CANopen,
mesh stacks, drivers) require a separate curated module catalogue mapping
module digest, licence, maintained Zephyr versions, Kconfig symbols,
manifest metadata and compatible board/SOC evidence. Never install source
code from an arbitrary Open VSX listing as a West module.

Node service uses registry read-only lookup -> immutable plan -> user review
-> secure download to quarantine -> verify publisher, checksum/signature and
licence -> isolated dependency resolution -> exact diffs -> atomic apply.
Use one workspace lock; rollback all manifest/fragment changes on failure.
Resolve modules through West manifest mechanisms rather than inventing a
compiler dependency resolver. Kconfig changes must go into a reviewed
profile-specific fragment only when the selected Zephyr checkout confirms
symbol existence/dependencies. Do not edit generated .config, blindly append
CMakeLists lines or execute downloaded postinstall scripts.
The implemented MarketplacePlanner generates a review-required plan only.

## 3. Remote hardware lab: explicit sessions

List physical inventory without claiming availability until agent heartbeat
and probe health checks succeed. Acquire authenticated TTL lease, enforce
one owner per physical board, verify lease on every Flash/Debug/Serial RPC.
Store uploaded ELF/HEX by content hash and treat it as untrusted input.
Compare selected board/architecture with build metadata; fail on unknown.
No board serial numbers or unrestricted device files to browser. Use mTLS
between gateway and agents; encrypt WS with wss and validate origin, bearer
credentials, allowed operations, message size, heartbeat, sequence and
backpressure. Debug port is private to the gateway and DAP tunnel. Map
USB runner ownership, remote leases and serial sessions to one lifecycle,
clean up on cancellation, timeout and disconnect. Native board-agent header
defines the runner boundary; proto/zephyr_lab.proto defines the gRPC API.
No physical execution or serial streaming is currently implemented.

## 4. RAG: reviewed, cited retrieval with no implicit code upload

Index locally licensed, versioned vendor PDFs, reference designs, exact
part-number schematics and Zephyr drivers only after source approval.
Metadata includes document source URI, vendor, silicon part, revision,
licence/provenance and extract hash in a production index. Split into
citation-sized chunks; embeddings are produced by a pluggable local provider
by default. Remote embeddings/chat require distinct informed permission.
Filter by selected board and exact part number *before* vector similarity,
return cited snippets and explicitly unknown answers on weak matches.
Treat retrieved text as untrusted reference data, never as commands.
ApprovedDocumentRetriever currently performs deterministic filtering and
cosine ranking on supplied embeddings; neither crawling nor model calls
are implemented.

## 5. Board verification and compliance

Board selection can schedule read-only preflight immediately. Real
configure/build and check_compliance must be explicit reviewed actions
that execute in bounded, isolated jobs. Use the selected checkout's
upstream Zephyr check_compliance.py with immutable commit range when
available; do not invent a substitute Devicetree/Kconfig parser.
On successful build inspect the corresponding generated zephyr.dts,
.config, build.ninja, runner metadata, build warnings and artefact SHA-256.
Board YAML, DTS/DTSI and overlay failures are surfaced with source links
and provenance. Treat generated build state as configured/built evidence,
never proof of electrically connected or correctly wired hardware.
Peripheral map is 'configured' unless physical test provides measurements.
Reports need board qualifier, checkout hash, build profile, command,
exit status, artifact digests, timestamps, checks performed, unknowns,
and explicit NOT_RUN status for missing data.

ZephyrVerificationPlanner emits argument-vector West commands and optional
upstream compliance invocations. It does not execute commands or modify files.

## Trust and deployment requirements

- Per-user identity: OIDC with scoped roles, short-lived tokens, independent
  grants for telemetry, registry install, doc upload, lab use and source writes.
- Trust boundary: Theia backend RPC authenticate **every call**. Do not trust
  frontend flags or browser-provided filesystem paths.
- Transport: Theia JSON-RPC/WebSocket for workbench UI; DAP for debugger;
  internal mTLS gRPC for physical lab; serial can use WS binary frames.
- Observability: structured, redacted errors and request correlation with
  bounded retention; no content-bearing telemetry.
- Backpressure: per-user job quotas, bounded serial buffers, interruptible
  runners and lease cleanup after lost connections.
- Licensing: metadata and explicit user approval before obtaining protocol
  stacks, drivers, vendor SVDs or proprietary tools.

## Recommended reviewable delivery PRs

1. Shared contracts and isolated testable TypeScript core (this branch).
2. Theia/CDT.cloud application skeleton and actual side panel contributions.
3. Telemetry backend sink, consent UX and independently audited collector.
4. Open VSX query adapter plus curated West registry and transactional installer.
5. Lab gateway/agent, DAP tunnel, serial streaming and hardware lab tests.
6. Local vendor reference corpus, RAG provider and cited source widgets.
7. Board preflight executor, upstream compliance, evidence report and CI.
8. Security, licensing, privacy review, end-to-end browser validation and
   release-gate ADRs.

Do not merge this branch into the current release train as a completed feature.
