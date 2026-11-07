<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Eclipse Workbench documentation

Start with the installed Workbench workflow, then use the technical guides
for configuration, debugging and evidence collection. **Current release
qualification and open gates** are maintained in [RELEASE_STATUS.md](../RELEASE_STATUS.md).
Dated reports are historical snapshots, not statements of current support.

## Start here

1. **Install:** [Fresh installation (Windows)](FRESH_INSTALL.md) — Eclipse, the Workbench p2 archive and managed tools.
2. **First build:** [Beginner setup](BEGINNER_SETUP.md) — create a workspace and compile the first application; then follow the [project workflow](ONE_CLICK_WORKFLOW.md).
3. **Hardware Studio:** [Visual Hardware Studio](VISUAL_HARDWARE_STUDIO.md) — explore built Devicetree nodes, controllers and logical pin routes.
4. **Debug:** [Registers and thread diagnostics](DEBUG_REGISTERS_AND_THREADS.md) — use compatible OpenOCD/CDT/GDB and inspect an exact matching SVD.
5. **Evidence CLI:** [CLI reference](CLI_REFERENCE.md) — collect and compare build snapshots and inspect offline bundles.

## Configuration

- [Configuration Studio](CONFIGURATION_STUDIO.md) — native Eclipse Kconfig/Devicetree views.
- [Configuration Studio CLI](CONFIGURATION_STUDIO_CLI.md) — headless configuration and diagnostics commands.
- [Kconfig configuration diagnostics](KCONFIG_CONFIGURATION_DIAGNOSTICS.md) — generated settings and dependency observations.
- [Configuration evidence](CONFIGURATION_EVIDENCE.md) and [request checking](CONFIG_REQUEST_CHECK.md).
- [Devicetree Truth Check](DEVICETREE_TRUTH_CHECK.md) — selected-build hardware evidence.
- [Peripheral Start Guide](PERIPHERAL_START_GUIDE.md) — guided, evidence-gated peripheral setup.
- [Configuration Studio roadmap](CONFIGURATION_STUDIO_ROADMAP.md).

## Debugging

- [Build Failure Investigator](BUILD_FAILURE_INVESTIGATOR.md) — inspect compilation and setup failures.
- [Shared OpenOCD debug tools](SHARED_OPENOCD_TOOLS.md) — select a compatible debugger.
- [Registers and thread diagnostics](DEBUG_REGISTERS_AND_THREADS.md) — CPU/SVD state and Zephyr thread checks.
- [Fault investigation](FAULT_INVESTIGATION.md) — fault logs, registers and ELF context.
- [ESP32-S3 debug startup](ESP32S3_DEBUG_STARTUP.md) — target-specific notes.

## RTOS / Trace

- [RTOS investigation guide](RTOS_INVESTIGATION_GUIDE.md) — trace and thread workflows.
- [Trace Compass integration](TRACE_COMPASS_INTEGRATION.md) — optional trace analysis.
- [RTOS observability specification](RTOS_OBSERVABILITY_SPEC.md) — design scope.
- [Advanced RTOS diagnostics roadmap](RTOS_ANALYSIS_ROADMAP.md) — planned areas and constraints.

## Releases / Evidence

- [Current release status](../RELEASE_STATUS.md) — authoritative qualification state.
- [Evidence Engine Program v2](EVIDENCE_ENGINE_PROGRAM_V2.md) — current engineering scope.
- [Evidence qualification gate](EVIDENCE_QUALIFICATION_GATE.md) — gate and reporting contract.
- [Validation](VALIDATION.md) and [quality](QUALITY.md) — test scope and requirements.
- [CLI reference](CLI_REFERENCE.md) — read-only artifact and bundle contract.
- [Updates](UPDATES.md) and [fresh install](FRESH_INSTALL.md) — installation/update guidance.
- [Licensing](LICENSING.md) and [dependency policy](DEPENDENCIES.md) — redistribution context.
- [Historical release status (2026-10-30)](reports/RELEASE_STATUS_2026-10-30.md).

## Architecture decisions (ADRs)

- [Architecture overview](ARCHITECTURE.md) and [engineering roadmap](ROADMAP.md).
- [ADR 0001 — Evidence graph](adr/0001-evidence-graph.md).
- [ADR 0002 — Rules](adr/0002-rules.md).
- [ADR 0003 — CLI and adapters](adr/0003-cli-and-adapters.md).
- [ADR 0004 — Release trains](adr/0004-release-trains.md).
- [ADR 0005 — Snapshot verification](adr/0005-snapshot-verification.md).
- [ADR 0006 — Upstream SPDX adapter](adr/0006-upstream-spdx-adapter.md).
- [ADR 0007 — SPDX source input](adr/0007-spdx-why-direct-input.md).

## Reports archive

Archived status, acceptance and build reports are kept for provenance and
historical review. Read [current release status](../RELEASE_STATUS.md) before
using a report to assess current implementation.

**Dated investigations and release status**

- [Release status — 2026-10-30](reports/RELEASE_STATUS_2026-10-30.md)
- [Devicetree Intelligence Studio — 2026-11-01](reports/DEVICETREE_INTELLIGENCE_STUDIO_2026-11-01.md)
- [Cross-vendor DTS Source Explorer — 2026-11-02](reports/CROSS_VENDOR_DTS_SOURCE_EXPLORER_2026-11-02.md)
- [Devicetree overlay editor audit — 2026-11-05](reports/DEVICETREE_OVERLAY_EDITOR_AUDIT_2026-11-05.md)

**Acceptance records**

- [Advanced RTOS diagnostics acceptance](reports/ADVANCED_RTOS_DIAGNOSTICS_ACCEPTANCE.md)
- [Commercial release acceptance (superseded)](reports/COMMERCIAL_RELEASE_ACCEPTANCE.md)

**Build reports**

- [Beginner build report](reports/BEGINNER_BUILD_REPORT.md)
- [Build report](reports/BUILD_REPORT.md)
- [Tools build report](reports/TOOLS_BUILD_REPORT.md)
- [Update build report](reports/UPDATE_BUILD_REPORT.md)

Trace-analysis support and validation claims are governed by the [single capability table](TRACE_ANALYSIS_CAPABILITIES.md).
