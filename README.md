<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Eclipse Workbench

### See your hardware. Understand your configuration. Investigate your firmware.

**More than a Zephyr code editor — a hardware-aware engineering and evidence workbench.**

[![PR build](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/workflows/build.yml/badge.svg?event=pull_request)](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/workflows/build.yml)
[![GitHub release](https://img.shields.io/github/v/release/niwantha33/zephyr-eclipse-workbench?include_prereleases&label=latest%20release)](https://github.com/niwantha33/zephyr-eclipse-workbench/releases)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)

**Zephyr Eclipse Workbench** is an open-source, Eclipse-based IDE integration for **Zephyr RTOS firmware development**. It connects **chip-level hardware views, effective Kconfig and Devicetree evidence, firmware builds, debug registers and RTOS diagnostics** in a familiar embedded C/C++ workflow. Instead of only asking *what did I configure?*, Workbench helps engineers ask *what did the build actually select, what can the target reveal, and what evidence supports the answer?*

**[Download releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases) · [Installation guide](docs/FRESH_INSTALL.md) · [Getting started](docs/BEGINNER_SETUP.md) · [Report an issue](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)**

> **Release availability:** Current published packages are **preview releases**, not a certified production release. Feature availability and physical-board validation vary by target. See [release status](RELEASE_STATUS.md) for verified coverage and open acceptance gates.

## Built for engineers who need proof, not just a green build

**Most Zephyr IDEs help you build and flash. Zephyr Eclipse Workbench also focuses on verifying which artifacts, configuration and debugger assumptions you are actually using.** Its strongest direction is an **evidence-aware workflow** from selected board to build artifacts, target debug checks and shareable investigations.

### Flagship: build and debug integrity safeguards

**Debug-server preflight:** Before attaching GDB for a supported OpenOCD launch, the Workbench can compare bounded Zephyr thread metadata read from the target with the matching launch ELF. When metadata differs, it **fails closed** with concrete mismatch information instead of proceeding with invalid RTOS offsets. This protects the diagnostic workflow from misleading thread views; it does **not** validate the complete flashed firmware image.

**Build and artifact verification:** Its read-only Evidence CLI collects allowlisted build artifacts with SHA-256 digests, compares working and failing build snapshots and rechecks archived artifact identities against an explicitly selected build. Reports distinguish **matching, changed, missing and unknown** rather than inventing root causes.

**Reproducible investigation sharing:** A sanitised offline evidence bundle preserves a deterministic graph and integrity manifest. Bounded inspection checks archive structure and content hashes without extraction, while excluding raw ELF files, source, arbitrary configuration strings and logs. Hash matching detects changes; **it is not digital signing or producer authentication**.

**Update delivery safeguards:** The repository's publication pipeline is designed to validate packaged Eclipse update artifacts, pin published content to an immutable commit and check manifest file digests. Build/publisher checks are separate from the offline Evidence CLI and from physical-board validation.

> **Critical scope:** These are implemented safeguards with different levels of test coverage, not a claim of cryptographic secure boot, complete build-server attestation, guaranteed target firmware identity or production certification. Check [debug metadata handling](docs/DEBUG_REGISTERS_AND_THREADS.md), [Evidence CLI contract](docs/CLI_REFERENCE.md), [update publication design](docs/PRIVATE_UPDATE_PUBLISH.md), and [release gate status](RELEASE_STATUS.md).

### Why this is a meaningful market distinction

The existing **IDE for Zephyr**, **Ac6 Workbench for Zephyr** and **Nordic nRF Connect for VS Code** document tool management, build/flash/debug, graphical configuration and, in some cases, memory/static analysis and SBOM features. We **do not** claim that those tools lack integrity checking altogether. Our positioning is a deliberate **hardware + generated-configuration + build-provenance + debug-preflight + offline-investigation** combination, with explicit unknown states and traceable evidence.

**Primary value:** *Know what was requested. Inspect what was built. Check what the debugger can trust. Preserve the evidence.*

## What makes this Workbench different?

### 1. Visual Hardware Studio — understand the chip, not just the DTS text

Explore the selected board's generated Devicetree and its hardware relationships through **peripheral and pin-oriented views**, including the **Chip Pin Canvas**. Navigate GPIO, pinctrl, ADC and other discovered nodes in context, instead of manually tracing source includes and overlay files. Device/package geometry and pin assignments require authoritative metadata; incomplete evidence must not be presented as confirmed wiring.

**Why it matters:** a beginner can start with *which device and pin does this application refer to?*; an experienced engineer can investigate board configuration without losing the link to generated inputs.

### 2. Configuration Studio + Insights — distinguish requests from reality

A line in `prj.conf` or a Devicetree overlay is **not proof** that the generated firmware uses it. Configuration diagnosis compares requested settings with **the selected build's generated `.config` and `zephyr.dts`**, identifies differences, missing or disabled nodes and stale snapshots, and labels unknowns instead of guessing. For deeper offline configuration checking, the opt-in Evidence CLI can interrogate Kconfiglib for the selected Zephyr tree.

**Why it matters:** investigate *why is my ADC/GPIO/device unavailable?* with observed evidence, not misleading green checks or assumed pin mappings. Diagnosis does not prove runtime peripheral initialisation or physical electrical behaviour.

### 3. SoC Register Inspector — connect debugging to named peripherals

Use a matching **CMSIS-SVD** to browse peripheral registers, bitfields, reset values, address information and watch snapshots while a compatible target is suspended. The native view reads through the existing CDT/GDB session; it does not silently scan or modify hardware.

**Why it matters:** inspect hardware state beside C source and debug frames rather than translating every address by hand. **Exact-device SVD matching, live probe behaviour and automatic vendor-SVD acquisition still require verification**; the current workflow supports manually loading a matching SVD.

### 4. RTOS Analysis + Fault Investigation — investigate behaviour beyond breakpoints

Bring together supported **CTF trace capture, Trace Compass workflows, thread-oriented diagnostics, CPU register state, fault-log analysis and ELF-symbol context**. Offline investigation utilities and evidence bundles provide repeatable artifacts for debugging discussions.

**Why it matters:** a build passing does not explain a stalled task, unexpected exception or scheduling issue. These diagnostics aim to connect observed firmware behaviour to actionable engineering questions. Comprehensive CPU-load, stack, trace-loss and on-device fault validation remain in progress.

### 5. Cross-vendor engineering without a vendor-specific project silo

Use **Zephyr's board catalogue, West, CMake, standard build outputs and supported runners** across ecosystems such as STM32, ESP32, Nordic nRF and TI MSPM0. Project-specific profiles and an Eclipse-native workflow help keep board selection, build, flash and debug consistent.

**Why it matters:** the workflow follows standard Zephyr project structure instead of hiding the underlying build system. Hardware support remains dependent on actual board, toolchain, runner and probe compatibility.

### The central idea: an evidence-connected workflow

```text
Selected board / SoC / application
                |
    Kconfig + Devicetree inputs
                |
     Generated build evidence
      (.config / zephyr.dts)
                |
   Hardware Studio + Insights
                |
        Firmware / ELF
                |
   Flash + CDT/GDB debugging
                |
 SVD registers / RTOS trace / faults
                |
       Investigation evidence
```

**The differentiator is the connection between these views, not a claim that every individual editor, debugger or visualisation is unique.** Some connections are implemented; others are partial or pending target-specific verification. See [release evidence](RELEASE_STATUS.md) for the exact boundaries.

## How this compares to VS Code Zephyr workflows

Modern VS Code tooling already provides project creation, SDK installation, Kconfig, Devicetree editing, flashing and debugging. Nordic nRF Connect also offers a visual Devicetree editor and RTOS-aware debugging. **Workbench is positioned around a more integrated hardware-to-evidence investigation experience within Eclipse**, rather than presenting those established capabilities as inventions.

| Engineering question | Workbench's focus |
| --- | --- |
| Which pin, node and controller does this build refer to? | Visual Hardware Studio and generated Devicetree inspection |
| Did my requested configuration actually take effect? | Configuration Studio diagnosis and generated `.config` evidence |
| What is the peripheral register state while halted? | SVD-backed SoC register snapshots in the debug session |
| What happened around a fault or thread issue? | Trace, fault/ELF context and offline investigation artifacts |
| Can I apply the workflow across chip vendors? | Standard Zephyr target discovery, per-project builds and supported runners |

This is a **product design distinction**, not a benchmarked claim of exclusivity or superiority. Independent competitor comparisons and physical-device acceptance testing remain outstanding.

## Features at a glance

| Capability | What it provides |
| --- | --- |
| **Project wizard and import** | Create a Zephyr application or discover existing applications in folders and workspaces. |
| **Managed tools and setup** | Guided installation and checks for Python, West, CMake, Ninja, Zephyr sources and required toolchains. |
| **Board and SoC explorer** | Select exact Zephyr board targets and qualifiers across supported vendor ecosystems. |
| **Firmware build profiles** | Separate Debug/Release outputs, pristine rebuild and access to actual ELF/BIN/HEX artifacts. |
| **Kconfig Studio** | Inspect and edit application configuration with organized navigation and diagnostics. |
| **Devicetree and hardware views** | Explore hardware nodes, GPIO/pinctrl and peripheral properties using available build metadata. |
| **Flash and native debug** | Launch supported West runners, OpenOCD and Eclipse CDT/GDB sessions for compatible hardware. |
| **CPU and SoC registers** | View CPU register state and inspect matching CMSIS-SVD peripheral registers and fields while halted. |
| **RTOS trace and investigation** | Support for trace capture/analysis workflows and offline diagnostic utilities where configured. |
| **Build Evidence Engine** | SHA-256 artifact snapshots, working/failing build comparisons, snapshot freshness verification and sanitised investigation bundles. |
| **Debug-server integrity preflight** | Compare selected ELF Zephyr thread metadata with target reads before supported GDB attachment; stop on mismatches. |
| **Evidence and diagnostics** | Inspect observed configuration/output, preserve unknowns and export bounded offline investigation data. |
| **Update management** | Install workbench updates through the published Eclipse update-channel workflow. |

**Compatibility note:** These are capabilities present in the project at different maturity levels, **not** a guarantee that every feature works with every board. In particular, SoC-register access requires a correct vendor SVD and compatible halted debug session; automatic SVD download/discovery and universal physical debugging are not guaranteed. The evidence CLI is limited and read-only. See [register guide](docs/DEBUG_REGISTERS_AND_THREADS.md) and [current validation status](RELEASE_STATUS.md).

## Quick start — Windows

1. Download and extract **Eclipse IDE for Embedded C/C++ Developers** from the [official Eclipse packages](https://www.eclipse.org/downloads/packages/).
2. Go to [Releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases), read the release's **START_HERE** instructions and download its **`_p2.zip`** asset. **Source code ZIP is not the Eclipse installer.**
3. In Eclipse choose **Help → Install New Software → Add → Archive**, select the `_p2.zip`, install the Workbench feature and restart.
4. Choose **Zephyr → Set Up Zephyr** and complete guided tool setup.
5. Use **File → New → Other → Zephyr → Zephyr Project**, or **File → Import → Zephyr → Existing Zephyr Application**.
6. Select the exact board/core under **Project → Properties → Zephyr**; then **Build**, **Flash** or **Debug** as supported by your hardware.

For required JDK versions, probe/OpenOCD selection, updates, unsigned-plugin trust prompts and optional Trace Compass support, follow the **[complete fresh-install guide](docs/FRESH_INSTALL.md)**. **Do not assume flashing a physical board happens during setup.**

## Configuration and hardware inspection

**Kconfig and `prj.conf`** — navigate application options and review generated configuration rather than treating a requested option as proof it was applied.

**Devicetree** — inspect nodes, compatible properties, pins and peripheral configuration derived from the selected Zephyr build. Hardware conclusions depend on the selected board, overlays and generated data.

**Zephyr Hardware Register Explorer** — open **Window → Show View → Other → Debug → Zephyr Hardware Register Explorer**, load a **matching vendor CMSIS-SVD**, suspend a compatible target and refresh selected registers or watches. This is a read-only snapshot workflow, not background register polling.

**RTOS and faults** — integrate supported CTF/Trace Compass workflows, CPU state inspection and offline evidence utilities. Complete target coverage and end-to-end fault-analysis validation remain ongoing.

## Supported platforms and hardware

The Workbench uses **Zephyr's own board catalogue and build infrastructure**, rather than maintaining a claim of universal support. Example ecosystems include **Texas Instruments (TI MSPM0), STMicroelectronics STM32, Espressif ESP32/ESP32-S3, Nordic Semiconductor nRF**, and other boards available in the selected Zephyr workspace. Discovery and compilation do **not** automatically establish verified flashing, hardware debugging, SVD or tracing for a particular device.

- **Host:** Windows x64 is the documented installation path; see release notes for other host availability.
- **Build:** Zephyr, West, CMake, Ninja and target-specific toolchains.
- **Debug:** Eclipse CDT/GDB with compatible runner and probe software; OpenOCD is the documented managed native backend.
- **Trace:** Optional Trace Compass; not bundled with the Workbench.

## Documentation

| Guide | Purpose |
| --- | --- |
| [Fresh installation](docs/FRESH_INSTALL.md) | Set up Eclipse, install the plugin and prepare tools. |
| [Beginner setup](docs/BEGINNER_SETUP.md) | First workspace and firmware build. |
| [One-click project workflow](docs/ONE_CLICK_WORKFLOW.md) | Create, build, find firmware, flash and debug. |
| [Existing application import](docs/IMPORT_EXISTING_APPLICATIONS.md) | Bring an existing Zephyr app into Eclipse. |
| [Tool manager](docs/TOOLS_MANAGER.md) | Detect and manage development tools. |
| [SoC registers and debug](docs/DEBUG_REGISTERS_AND_THREADS.md) | SVD registers, GDB and thread diagnostics. |
| [Update channel](docs/UPDATE_CHANNEL.md) | Install and manage Workbench updates. |
| [Current release evidence](RELEASE_STATUS.md) | Verified capabilities, limitations and release gates. |

## Open source and contributions

Zephyr Eclipse Workbench is an independent project, not an official Zephyr Project or Eclipse Foundation distribution. Original project code is available under **[Apache License 2.0](LICENSE)** unless otherwise stated. Dependencies and vendor tools retain their individual licenses; check [licensing guidance](docs/LICENSING.md).

Contributions, hardware validation reports, bug reports and feature proposals are welcome via [Issues](https://github.com/niwantha33/zephyr-eclipse-workbench/issues) and [Pull Requests](https://github.com/niwantha33/zephyr-eclipse-workbench/pulls). See [CONTRIBUTING.md](CONTRIBUTING.md).

## Search keywords

Zephyr build artifact integrity · Zephyr debug server integrity · ELF thread metadata validation · Zephyr build provenance · firmware evidence graph · SHA-256 artifact verification · reproducible build comparison · offline diagnostic bundle · Zephyr hardware visualization · Zephyr Visual Hardware Studio · chip pinout viewer · Zephyr Configuration Studio · configuration evidence engine · Zephyr diagnostics IDE · RTOS fault investigation ·  Zephyr Eclipse IDE · Eclipse Zephyr plugin · Zephyr firmware development · embedded C/C++ IDE · RTOS development environment · West build · Zephyr CMake · Kconfig editor · Devicetree editor · Devicetree overlay · pin configuration · GPIO configuration · SoC configuration · CMSIS-SVD · SVD register viewer · MCU peripheral registers · ARM Cortex-M debug · GDB · OpenOCD · embedded debugging · RTOS thread analysis · CTF tracing · Trace Compass · Zephyr fault analysis · STM32 Zephyr · ESP32 Zephyr · Nordic nRF Zephyr · TI MSPM0 Zephyr · cross-vendor embedded development
