# Implementation roadmap

Status: step 1 complete; the first development slice implements parts of steps 2–8. Core checks and p2 packaging pass on Linux. No full product milestone is complete; desktop, Windows, firmware and hardware checks remain pending.

| Step | Work | Deliverable | Exit criterion |
|---:|---|---|---|
| 1 | Repository foundation | README, architecture, roadmap, compatibility and validation policies | Scope and status are explicit; repository stays private |
| 2 | Confirm baseline and licensing | Exact Eclipse/CDT/Java/Python/SDK versions; Apache-2.0 for original material, dependency audit and submodule policy | Exact-version open-source/commercial-use inventory; notices and distribution obligations recorded |
| 3 | Eclipse integration spike | Minimal installable plug-in and CDT/build/debug API decisions | Installs in clean Eclipse 2026-09; APIs documented |
| 4 | Environment diagnostics | Detect Python, West, CMake, Ninja, SDK and existing workspace | Recognises existing installation; actionable missing-tool messages |
| 5 | Workspace management | Import existing workspace; initialise manifest-based workspace | Standard and external/zephyr layouts work without personal paths |
| 6 | Hardware discovery | Vendor/SoC/board/revision/core/qualifier selector and feature/tool licence eligibility | Catalogue matches checkout/version; non-open dependencies labelled and excluded from compliant workflow |
| 7 | Project wizard | Standalone Hello World application and sample import | App stays outside upstream source and builds from terminal |
| 8 | Build operations | Build, pristine rebuild, clean, cancel, console and error markers | Correct exit states, separate outputs, clickable compiler errors |
| 9 | Code navigation | CDT compilation settings and generated-header indexing | Completion and definitions work in app, kernel and drivers |
| 10 | Kconfig workflow | Upstream configuration launch, resolved values and persistent fragments | Intended settings survive pristine rebuild |
| 11 | Devicetree workflow | Overlay creation, generated tree and source diagnostics | Board overlay change is visible in build and errors |
| 12 | Output reports | ELF/BIN/HEX paths where generated; RAM/flash and map access | Reports match build outputs; no universal filename assumption |
| 13 | Flash and serial | Capability discovery, runner/port/probe selection and console | Board-specific programming and serial output recorded |
| 14 | Debug | Eclipse launch integration, server/GDB selection and attach | Breakpoints, stepping and variables verified on Arm and Xtensa |
| 15 | Advanced projects | Sysbuild, domains, multi-core, external boards and modules | Representative multi-image and external-board cases pass |
| 16 | Vendor/host validation | Five priority vendors assessed; Linux coverage | Claims linked to automated or physical evidence |
| 17 | Update centre and maintenance | p2-backed compatible patch updates, ownership-aware tool updates, idle coordination and staged migrations | Upgrade, restart, offline failure and recovery suite in UPDATES.md passes; project pins preserved; changed dependency licences rechecked |
| 18 | Quality and comparison | Benchmark fixtures, UI responsiveness, accessibility, reliability and competitor workflow comparison | Measured results against QUALITY.md; no unsupported superiority claims |
| 19 | Release packaging | Versioned p2 site, install/upgrade guides and release notes | Fresh install/upgrade pass; resolved SBOM/notices/source obligations complete; public publication separately authorised |

## Release milestones

| Milestone | Scope | Required evidence |
|---|---|---|
| v0.1 developer preview | Install, detect workspace, discover board, create app, build and code navigation; update inventory/channel design | Fresh Windows/Eclipse install and ESP32-S3 build through plug-in; version ownership recorded |
| v0.2 configuration | Kconfig, Devicetree, memory reports, diagnostics and update checks/staging | Settings persistence, diagnostic and update-download checks |
| v0.3 hardware workflow | Flash, serial and debug | Physical evidence for explicitly listed initial boards |
| v0.4 broader coverage | Five priority vendors assessed, Linux, multi-image support and eligible automatic maintenance/recovery | Per-capability matrix and upgrade/recovery evidence, including unsupported workflows |
| v1.0 stable | Reproducible setup, regression checks, updates/recovery, quality benchmarks and documentation | Clean-machine onboarding, update/recovery and applicable QUALITY.md criteria pass |

No release dates are committed. Steps may be split into PRs, but a milestone is complete only after its exit criteria pass.

## Initial validation order

1. ESP32-S3: reuse the reported command-line baseline; confirm exact physical board before flashing.
2. STM32 Nucleo F446RE: representative Arm/ST-Link workflow.
3. Nordic nRF52840 DK: representative Nordic workflow.
4. TI MSPM0G3507 or MSP432P401R LaunchPad: choose based on available hardware.
5. Realtek RTL872xD EVB: establish image/programming and debug requirements.
6. ESP32-C3: representative RISC-V build and hardware workflow.
7. Additional NXP, Renesas, Microchip, Silicon Labs and other targets as resources allow.

Hardware availability governs physical validation. Assessing a vendor does not mean every board from that vendor is verified.

## Immediate next task

Implement the guided Tools Manager, beginning with detection/reuse and a dependency status/progress view, then installation. Follow slices A-G in GUIDED_WORKFLOW.md. Existing global preferences are a prototype fallback, not the intended project workflow. The user has shown a partial Windows desktop smoke test; full firmware completion and hardware tests remain unrecorded.

## Revised delivery order

[GUIDED_WORKFLOW.md](GUIDED_WORKFLOW.md) defines the normal user interface and supersedes manual setup as the product goal. Deliver Tools Manager detection/install, vendor-aware exact hardware selection, File > New/Import wizards, per-project CDT build/run/debug and update notifications in slices A-G. Design update-channel and portable-project contracts alongside the first slices.

## First implementation checkpoint

| Area | Implemented | Still pending |
|---|---|---|
| Build foundation | Java 21; Tycho 5.0.3; immutable Eclipse 4.41 target; core/UI/feature/p2 modules | Clean Eclipse installation and upgrade smoke tests |
| Environment | Local preferences; explicit venv Python; West-root and Zephyr-path checks; host-tool lookup | SDK compatibility inventory and automatic setup |
| Hardware | Board-name list from West and explicit qualified-target entry | Automatic vendor/SoC/revision/core discovery and licence eligibility |
| Firmware build | Incremental/pristine build; per-board output directories; streaming console; cancellation; one active operation | Project wizard, profiles, CDT indexing and error markers |
| Validation | 29 real-process/core checks; Java compilation; update-site archive and metadata checks | GUI, Windows runtime, actual firmware build through Eclipse, hardware and complete dependency audit |

This checkpoint does not implement auto-updates, flash/debug, Kconfig or Devicetree UI. Those remain on the roadmap.

## Cross-cutting quality and update work

Steps 17–18 are acceptance milestones, not instructions to defer architecture until the end. Plan component ownership, update compatibility, cancellation, recovery and measurement during steps 2–4. Implement incrementally according to the release table.

- [Update and recovery requirements](UPDATES.md)
- [Product quality targets and comparison method](QUALITY.md)

## Dependency acceptance

Root LICENSE now establishes Apache-2.0 for original project material. Step 2 remains pending because exact third-party versions, licences, transitives and redistribution conditions are not audited.

Apply [LICENSING.md](LICENSING.md) throughout implementation. Use pinned submodules only when appropriate under [DEPENDENCIES.md](DEPENDENCIES.md). Proprietary dependencies cannot be treated as open source merely because they cost nothing.
