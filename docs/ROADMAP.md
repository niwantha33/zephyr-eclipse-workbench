# Implementation roadmap

Status: step 1 documentation foundation complete; steps 2–17 pending. Planning documents are the only deliverables currently present.

| Step | Work | Deliverable | Exit criterion |
|---:|---|---|---|
| 1 | Repository foundation | README, architecture, roadmap, compatibility and validation policies | Scope and status are explicit; repository stays private |
| 2 | Confirm baseline and licensing | Exact Eclipse/CDT/Java/Python/SDK versions; licence decision | Reproducible dependency list and distribution terms |
| 3 | Eclipse integration spike | Minimal installable plug-in and CDT/build/debug API decisions | Installs in clean Eclipse 2026-09; APIs documented |
| 4 | Environment diagnostics | Detect Python, West, CMake, Ninja, SDK and existing workspace | Recognises existing installation; actionable missing-tool messages |
| 5 | Workspace management | Import existing workspace; initialise manifest-based workspace | Standard and external/zephyr layouts work without personal paths |
| 6 | Hardware discovery | Vendor/SoC/board/revision/core/qualifier selector | Catalogue matches selected checkout and refreshes on version change |
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
| 17 | Release packaging | Versioned p2 site, install/upgrade guides and release notes | Fresh install and upgrade pass; public publication separately authorised |

## Release milestones

| Milestone | Scope | Required evidence |
|---|---|---|
| v0.1 developer preview | Install, detect workspace, discover board, create app, build and code navigation | Fresh Windows/Eclipse install and ESP32-S3 build through plug-in |
| v0.2 configuration | Kconfig, Devicetree, memory reports and diagnostics | Settings persistence and diagnostic checks |
| v0.3 hardware workflow | Flash, serial and debug | Physical evidence for explicitly listed initial boards |
| v0.4 broader coverage | Five priority vendors assessed, Linux and multi-image support | Published per-capability matrix, including unsupported workflows |
| v1.0 stable | Reproducible setup, regression checks, upgrades and documentation | Clean-machine onboarding and upgrade criteria pass |

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

Create the minimal installable Eclipse plug-in and pin its target platform after recording local Eclipse, CDT and Java versions. Do not present an empty installer or mock UI as functional Zephyr integration.
