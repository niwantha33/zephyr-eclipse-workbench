<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Eclipse Workbench

### Develop, configure, build, flash and debug Zephyr RTOS applications in Eclipse.

[![GitHub release](https://img.shields.io/github/v/release/niwantha33/zephyr-eclipse-workbench?include_prereleases&label=latest%20release)](https://github.com/niwantha33/zephyr-eclipse-workbench/releases)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)

**Zephyr Eclipse Workbench** is an open-source, Eclipse-based IDE integration for **Zephyr RTOS firmware development**. It brings project setup, board selection, West/CMake builds, Kconfig and Devicetree configuration, flashing, GDB debugging and hardware inspection into a familiar embedded C/C++ workflow.

**[Download releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases) · [Installation guide](docs/FRESH_INSTALL.md) · [Getting started](docs/BEGINNER_SETUP.md) · [Report an issue](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)**

> **Release availability:** Current published packages are **preview releases**, not a certified production release. Feature availability and physical-board validation vary by target. See [release status](RELEASE_STATUS.md) for verified coverage and open acceptance gates.

## Why Zephyr Eclipse Workbench?

- **An Eclipse home for Zephyr:** create or import Zephyr applications without leaving the embedded C/C++ IDE.
- **Guided environment setup:** provision and reuse managed build dependencies instead of manually assembling every tool path.
- **Cross-vendor board discovery:** explore targets from your Zephyr checkout, including vendor, SoC, core and board qualifiers.
- **Configuration in context:** work with Kconfig, `prj.conf`, Devicetree and hardware-oriented configuration views.
- **Build to debug workflow:** use per-project builds, generated firmware artifacts, Zephyr flash runners and CDT/GDB debugging.
- **Hardware investigation:** inspect CPU registers, SVD-defined peripherals and supported diagnostic evidence without replacing Zephyr's standard toolchain.

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
| **Evidence and diagnostics** | Inspect build artifacts, compare observed configuration/output and export bounded offline investigation data. |
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

**SoC Registers** — open **Window → Show View → Other → Debug → SoC Registers**, load a **matching vendor CMSIS-SVD**, suspend a compatible target and refresh selected registers or watches. This is a read-only snapshot workflow, not background register polling.

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

Zephyr RTOS IDE · Zephyr Eclipse IDE · Eclipse Zephyr plugin · Zephyr firmware development · embedded C/C++ IDE · RTOS development environment · West build · Zephyr CMake · Kconfig editor · Devicetree editor · Devicetree overlay · pin configuration · GPIO configuration · SoC configuration · CMSIS-SVD · SVD register viewer · MCU peripheral registers · ARM Cortex-M debug · GDB · OpenOCD · embedded debugging · RTOS thread analysis · CTF tracing · Trace Compass · Zephyr fault analysis · STM32 Zephyr · ESP32 Zephyr · Nordic nRF Zephyr · TI MSPM0 Zephyr · cross-vendor embedded development
