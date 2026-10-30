<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commercial-release acceptance matrix

**Product:** Zephyr Eclipse Workbench. **Gate:** OPEN, not approved for commercial release.
**Source of requirements:** .github/chatgpt-instruction.md (2026-10-30).
**Evidence vocabulary:** DISCOVERED, CONFIGURED, BUILT, FLASHED, DEBUGGED, ANALYSED.
These are separate claims. A source-code implementation, unit test, UI smoke, build,
and a physical target measurement are different evidence levels.

This is a blocking acceptance checklist, not a list of features to advertise.
Each unchecked requirement must be implemented, explicitly limited by a
capability adapter, or identified as a precise blocker with reproducible tests.
No blanket "all SoCs", "production-ready", certification, or superiority claims.

## 1. Baseline and inventory
- [x] Source structure, available tests, CI, releases and active PR audited on 2026-10-30
- [ ] Verify and reconcile every documentation-only/historical statement against current source
- [ ] Record pinned exact host/SDK/Zephyr/tool versions for every tested capability
- [ ] All CI failures resolved without disabling checks; regressions remain stable
- [ ] Complete third-party source, dependency, redistributability and licence inventory
- [ ] Store tested release evidence with immutable build identity and test logs

## 2. Native Eclipse user experience
- [ ] First-run new/import → target → configure → build → flash → debug → investigate exercise on real desktop
- [ ] Native Build/Flash/Debug/Stop/Configuration commands, enablement and correct selected-project context
- [ ] Exact target/core and Debug/Release selection visible consistently across views
- [ ] SWT background jobs, progress, cancellation, disposal, error handling, keyboard and high-DPI verified
- [ ] Light/dark modes, navigation focus/selection and meaningful empty states visually inspected
- [x] Linux CI real installed-Eclipse UI smoke includes native Configuration Studio and CDT
- [x] A real screenshot is captured by Linux CI; inspect the artifact before using in publicity

## 3. Installer and updates
- [ ] Clean Windows installation, first launch, p2 archive identification, setup and first build
- [ ] Clean Linux installation and first-use smoke in supported distribution
- [ ] Existing tools reused safely; managed tools isolated without damaging user installations
- [ ] Partial/interrupted download, checksum, provenance, extraction and rollback tested end to end
- [ ] Update from prior version and reversible failure recovery on real desktop
- [ ] Dependency licence notices and any user-install requirements correctly shown
- [ ] Versioned assets, manifest, release hash and release-notes inspection

## 4. Projects and builds
- [ ] Direct single-application import and parent-directory multi-candidate selection in UI
- [ ] Spaces and non-ASCII paths, multiple simultaneous projects and owned independent build dirs
- [ ] Exact board/revision/SoC/core qualifiers and external board roots
- [ ] Debug/Release profile fragments, overlays and CMake options correctly applied and visible
- [ ] ELF/HEX/BIN/map/compile_commands visibility when generated
- [ ] Clean, rebuild and pristine operations and stale artifact rejection
- [ ] No silent different-core selection, source overwrite or local path leakage

## 5. Flash and debug
- [x] TargetOperations exact-build receipt and OpenOCD safety tests present
- [x] Software-only cross-process Workbench physical-operation lock and lifecycle tests present
- [ ] Zephyr runner discovery and adapters for validated OpenOCD, J-Link, pyOCD, LinkServer
- [ ] Correct vendor OpenOCD tools/scripts and licence boundaries; missing-tool guidance
- [ ] Probe identity policy, no unsafe force flash, no collisions/foreign-process kills
- [ ] Bounded startup, cancellation, unplug/reconnect, port and owned-process cleanup
- [ ] Real 20-cycle Flash/Debug/Terminate/relaunch evidence on each claimed physical backend
- [ ] Matching ELF/GDB, breakpoints, stepping, variables, threads, reset and core selection verified

## 6. Kconfig Studio
- [x] Real generated effective config and Kconfiglib-backed inspection implemented
- [ ] Hierarchy, search, symbol provenance, help, visibility and dependency UX validated in desktop UI
- [ ] Explain overridden, unavailable and disabled choices with exact generated evidence
- [ ] Profile-specific inputs and safe writes with non-destructive preview and concurrent edit handling
- [ ] Refresh after source changes/checkout changes and navigate from build diagnostic to source

## 7. Devicetree and physical chip workflow
- [x] Generated-tree browsing, controller/channel parameters, pinctrl route display and overlay preview implemented
- [ ] Binding-driven typed ADC/GPIO/UART/I2C/SPI/PWM parameters verified for named targets
- [ ] Board wiring, SoC pin capability and exact package geometry separated; no invented pad numbers
- [ ] Supported vendor pinctrl adapters and pin conflict detection with authoritative metadata
- [ ] Source DTS, selected overlays and resolved tree provenance / navigation
- [ ] Safe overlay save/restore, external modification rejection and rebuild validation
- [ ] Complete visual ADC configuration on nrf52840dk/nrf52840 plus physical measurement
- [x] Standalone bounded ADC example source and exact board overlay committed; **firmware CI pending**

## 8. Guided configuration diagnosis
- [x] Read-only CONFIRMED/POSSIBLE/MISSING_EVIDENCE checks for stale/missing generated data and disabled nodes
- [ ] Combined Kconfig, driver, bindings, pinctrl, build diagnostics and runtime init logs with provenance
- [ ] Precise reproducible next action and previewed proposed changes
- [ ] Real project/peripheral diagnosis smoke, malformed inputs and no surprise edits
- [ ] Optional AI explanations must remain separate from deterministic checks

## 9. SoC/CPU register inspection
- [x] Separate Eclipse SVD and SoC Registers view with existing parser/tests
- [ ] Exact-device SVD matching and compatible debugger/session/frame selection
- [ ] Bitfields, watches, enumeration, changed/reset comparison UI and stale-session invalidation
- [ ] Side-effectful read, write-one-to-clear and read-modify-write policies for each supported target
- [ ] SVD malformed/derived constructs, explicit read limits and ownership tests
- [ ] Physical register evidence on supported target(s)
- [ ] No copying proprietary mcu_register_viewer repository without licence permission

## 10. RTOS and trace analysis
- [x] OpenOCD Zephyr RAM CTF snapshot preview and offline trace validation tooling
- [ ] Guided configuration, capture readiness, buffer capacity and event-loss evidence
- [ ] Trace Compass install/open, import and timeline support in real Eclipse session
- [ ] Captured task names, scheduling, stack method and reliable timestamp domains
- [ ] CPU charts only with complete, justified timing accounting; no sampled-data percentages
- [ ] Correlate logs and traces only when clocks/provenance permit
- [ ] Instrumentation impact, trace capacity and unsupported analyses disclosed

## 11. Fault/core-dump investigation
- [x] Offline Cortex-M fatal-log register/status decoding and explicit ELF symbol lookup implemented
- [ ] Exact firmware/ELF/provenance matching, mismatch refusal and source navigation
- [ ] Supported Zephyr binary core-dump import, GDB backtraces and captured thread context
- [ ] Cortex-M stacked PC/LR/SP, EXC_RETURN and architecture fault registers validated
- [ ] Xtensa/RISC-V behind separate supported adapters; never decode as Cortex-M
- [ ] Export a reproducible fault report and distinguish hardware status/software hypothesis/missing data

## 12. Safe peripheral tests
- [ ] Guided GPIO, ADC and I2C test selection, declared pin/wiring/prerequisite preview
- [x] Bounded standalone ADC example avoids arbitrary channel probing
- [ ] Full ADC software build + physical known-voltage read/tolerance evidence
- [ ] GPIO and I2C examples with named board/pin and approved bounded operations
- [ ] UART, SPI and PWM only after validated adapter coverage
- [ ] Explicit approval before flash; note replacement of prior firmware; stop and timeout
- [ ] Keep generated tests separate from production application until opt-in integration

## 13. Offline investigations and comparisons
- [x] Portable allowlisted ZIP with manifest, hashes and offline Kconfig diff implementation
- [x] Build target and bounded ELF SHA-256 fingerprint without exporting binary
- [x] Default export Kconfig string redaction, diagnosis-path masking, redacted DTS inventory added; **CI pending**
- [ ] Comprehensive sensitive-data review, no accidental credential/path inclusion and malformed ZIP test matrix
- [ ] Config, Devicetree, tool/version, memory and comparable runtime-difference views
- [ ] Explicit inclusion preview, offline verification and provenance; differences are not causation

## 14. First-use demonstration
- [ ] Labelled real captured failure and firmware identity, never pretend recorded values are live
- [ ] From failure to source location and plausible diagnosis in a few minutes
- [ ] Working/failing configuration comparison and hardware/diagnostic explanation
- [ ] Reproducible source example, screenshot and known validation outcome

## 15. Validation policy
- [ ] Python/Java core and Eclipse-installed UI smoke green on final **head SHA**
- [ ] Native Windows managed firmware matrix green on final **head SHA**
- [ ] Compiled CI firmware images from TI, STM32, Nordic, Espressif Xtensa/RISC-V, MCUboot and ADC example
- [ ] Fault injection: missing tools, stale builds, ports, cancellation, subprocess trees, bad archives
- [ ] Known-good and malformed SVD, trace, Kconfig, DTS, core dumps and identity mismatches
- [ ] Repeated physical hardware sessions with logs and exact board/probe/revision
- [ ] Evidence split into DISCOVERED / CONFIGURED / BUILT / FLASHED / DEBUGGED / ANALYSED

## 16. Public commercial release and approval
- [ ] Fresh-install instructions, update/recovery instructions and known limitations
- [ ] Supported host/board/probe matrix with per-capability evidence links
- [ ] Real screenshots and guided demonstration retained from verified builds
- [ ] Complete open-source licences, third-party notice/SBOM and distribution decision
- [ ] Current release notes, repository metadata, issue templates and samples
- [ ] Versioned p2 archive inspected and tested from clean host, not source archive
- [ ] No inaccurate blanket support, safety guarantee or claimed measurements
- [ ] Owner's required final publication approval and review gates satisfied

## Current evidence references

- Pull request: https://github.com/niwantha33/zephyr-eclipse-workbench/pull/57
- Previously passed patch CI (before later implementation): https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41035069657
- Linux installed Eclipse/package/screenshot validation for ADC-example head SHA
  78b5eb7001f3edd015495851384fd50ffe10a27f:
  https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41039792877
- Exact latest-head Windows + Linux evidence must be recorded **only after** the new CI finishes.
- Real hardware for all requested vendors is **not attached to this environment**.
- Release publication is **not authorised by passing this document**.
