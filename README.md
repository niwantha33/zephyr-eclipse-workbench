<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Eclipse Workbench

**See your hardware. Understand your configuration. Investigate your firmware.**

An open-source, Eclipse-based workbench for **Zephyr RTOS** firmware development.
Connect the selected board, generated Kconfig and Devicetree, build artifacts,
native debugging and offline evidence in one embedded C/C++ workflow.

[![GitHub release](https://img.shields.io/github/v/release/niwantha33/zephyr-eclipse-workbench?include_prereleases&label=latest%20release)](https://github.com/niwantha33/zephyr-eclipse-workbench/releases)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)

**[Download releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases) · [Install on Windows](docs/FRESH_INSTALL.md) · [Beginner guide](docs/BEGINNER_SETUP.md) · [Report an issue](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)**

## Screenshots

The following slots are reserved for **real screenshots of the installed Eclipse UI**.
They are text placeholders until maintainers add verified captures.

| View | Screenshot slot | Show |
| --- | --- | --- |
| Chip Pin Canvas | `docs/images/pin-canvas.png` | Selected board and its evidence-backed pin or peripheral view |
| Configuration Studio | `docs/images/config-studio.png` | Requested settings beside generated build evidence |
| Zephyr Hardware Register Explorer | `docs/images/register-inspector.png` | Matching SVD registers in a suspended debug session |

To add screenshots, follow [the capture and privacy instructions in CONTRIBUTING](CONTRIBUTING.md#readme-screenshots).
Do not link empty paths or use mock-ups as evidence of implemented behaviour.

## Flagship features

### Visual Hardware Studio

Navigate hardware nodes from the selected build's generated `zephyr.dts`.
The Chip Pin Canvas and peripheral views connect controllers, properties and
logical pin routes with their underlying Devicetree declarations.
[Hardware Studio guide](docs/VISUAL_HARDWARE_STUDIO.md).

### Configuration Studio and Insights

Inspect requested `prj.conf` or overlay settings alongside the generated
`.config` and `zephyr.dts` for a specific build, board and profile.
Follow available Kconfig, Devicetree and driver evidence to investigate
why a setting or device differs from the request.
[Configuration Studio guide](docs/CONFIGURATION_STUDIO.md).

### SoC registers in the debug session

Load a matching vendor **CMSIS-SVD** and browse peripherals, registers and
bitfields. Refresh read-only register snapshots through the existing Eclipse
CDT/GDB session while the target is suspended.
[Register and thread guide](docs/DEBUG_REGISTERS_AND_THREADS.md).

### Build Evidence Engine and offline CLI

Use the read-only `zew.py` CLI to collect allowlisted artifacts and SHA-256
digests, compare build snapshots with **matching / changed / missing / unknown**
outcomes, and create an explicitly requested sanitised investigation bundle.
Verify a bundle's manifest and file hashes without extracting it.
[CLI reference](docs/CLI_REFERENCE.md).

### Debug preflight and update integrity

For supported native OpenOCD launches, compare bounded target thread metadata
against the selected ELF before continuing to GDB.
The update publication process checks packaged p2 artifacts and recorded
digests separately from the offline Evidence CLI.
[Debug guide](docs/DEBUG_REGISTERS_AND_THREADS.md) · [Publication design](docs/PRIVATE_UPDATE_PUBLISH.md).

### RTOS and fault investigation

Use thread diagnostics, CPU register state, supported CTF/Trace Compass
workflows and offline fault/ELF context to investigate firmware behaviour.
[RTOS investigation guide](docs/RTOS_INVESTIGATION_GUIDE.md).

## Scope & limitations

- Current releases are **development previews**; release gate **G0 remains open**.
- Feature coverage and validation differ by board, SoC, toolchain, runner and probe.
- Generated `.config` and `zephyr.dts` describe the selected build, not runtime peripheral state or physical wiring.
- Chip Pin Canvas routes are logical unless supported by authoritative package/pin metadata; electrical limits and pin ownership require device documentation.
- SVD inspection requires an exact matching vendor definition and a compatible suspended debug session; automatic vendor-SVD acquisition is not established.
- Debug preflight checks bounded Zephyr thread metadata, not the full flashed image or secure boot state.
- SHA-256 hashes and bundle manifests detect byte changes; they are not signatures, producer authentication or complete source-to-binary causality.
- Trace Compass is a separate optional tool; universal CPU-load, fault and physical-target coverage is not established.
- See [current release evidence and open gates](RELEASE_STATUS.md) for tested paths and unresolved work.

## Evidence-connected workflow

The Workbench connects configuration requests to observed build outputs,
native target inspection and portable investigation artifacts:

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
    Flash + CDT/GDB debug
                |
 SVD registers / RTOS trace / faults
                |
     Evidence CLI / bundle
```

## Features at a glance

| Area | Workbench workflow |
| --- | --- |
| Setup and projects | Guided managed tools, new-project wizard and existing-application import |
| Targets | Discover Zephyr board names, SoC/core qualifiers and build profiles |
| Builds | West/CMake build, separate Debug/Release directories and ELF/BIN/HEX outputs |
| Kconfig | Browse requests, effective values and available dependency explanations |
| Devicetree | Navigate generated nodes, controllers, GPIO/pinctrl and overlay inputs |
| Hardware views | Inspect build-backed peripherals, pin routes and properties |
| Flash and debug | Use supported runners, OpenOCD and Eclipse CDT/GDB |
| SVD registers | Read matching peripheral registers and fields while suspended |
| RTOS analysis | CTF/Trace Compass integration and thread/fault investigation |
| Build evidence | Snapshot, compare, verify freshness and inspect bounded offline bundles |
| Updates | Discover published Workbench updates from Eclipse |

## Quick start — Windows

1. Install [Eclipse IDE for Embedded C/C++ Developers](https://www.eclipse.org/downloads/packages/) on Windows x64, with its required Java runtime.
2. Open [GitHub Releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases), read that release's `START_HERE` instructions and download the asset ending in `_p2.zip`. GitHub's generated source ZIP is not the Eclipse plug-in archive.
3. In Eclipse, choose **Help → Install New Software → Add → Archive**, select the `_p2.zip` (without extracting it), finish installation and restart.
4. Choose **Zephyr → Set Up Zephyr** to prepare the managed toolchain and first workspace.
5. Create a project with **File → New → Other → Zephyr → Zephyr Project**, or import one with **File → Import → Zephyr → Existing Zephyr Application**.
6. Set the exact board/core under **Project → Properties → Zephyr**, select a profile and build.
7. Configure the runner/probe before using **Flash** or **Debug** on hardware.

Follow the [full installation and update guide](docs/FRESH_INSTALL.md) for Eclipse/JDK
versions, OpenOCD setup, release updates and trust prompts.
For the first workspace and build, see [Beginner setup](docs/BEGINNER_SETUP.md)
and the [one-click project workflow](docs/ONE_CLICK_WORKFLOW.md).

## Working with your hardware

**Configuration:** Open **Zephyr → Configuration Studio** for the current
project/target to review effective Kconfig and Devicetree inputs.
[Configuration CLI commands](docs/CONFIGURATION_STUDIO_CLI.md).

**Registers:** Open **Window → Show View → Other → Debug → Zephyr Hardware
Register Explorer**, load the exact vendor SVD and refresh after suspending
a supported target. [Register guide](docs/DEBUG_REGISTERS_AND_THREADS.md).

**Cross-vendor targets:** Project selection follows Zephyr's board catalogue.
Example ecosystems include **TI MSPM0, STM32, Nordic nRF and ESP32**.
[Compatibility notes](docs/COMPATIBILITY.md).

## How it complements other Zephyr development tools

| Engineering task | Workbench emphasis |
| --- | --- |
| Board and peripheral exploration | Link hardware navigation to the generated Devicetree |
| Configuration diagnosis | Compare requested Kconfig/DTS against selected-build evidence |
| Debug investigation | Pair native GDB with SVD register and RTOS context |
| Build comparison and handoff | Preserve explicit unknowns, artifact hashes and offline bundles |

These are areas of emphasis, not an exclusivity or competitor benchmark.
[Market evidence](docs/MARKET_EVIDENCE.md).

## Documentation

| Start here | Guide |
| --- | --- |
| Install Eclipse and Workbench | [Fresh installation](docs/FRESH_INSTALL.md) |
| Prepare the first firmware build | [Beginner setup](docs/BEGINNER_SETUP.md) |
| Create, build, flash and debug | [Project workflow](docs/ONE_CLICK_WORKFLOW.md) |
| Import an existing application | [Application import](docs/IMPORT_EXISTING_APPLICATIONS.md) |
| Inspect hardware and configuration | [Visual Hardware Studio](docs/VISUAL_HARDWARE_STUDIO.md) |
| Debug and inspect peripheral registers | [Registers and threads](docs/DEBUG_REGISTERS_AND_THREADS.md) |
| Investigate with the Evidence CLI | [CLI reference](docs/CLI_REFERENCE.md) |
| Understand the evidence program | [Evidence Engine Program v2](docs/EVIDENCE_ENGINE_PROGRAM_V2.md) |
| Read release and test boundaries | [Release status](RELEASE_STATUS.md) |

## Open source and contributing

Zephyr Eclipse Workbench is an independent project. Original project code is
licensed under [Apache-2.0](LICENSE); dependencies and vendor tools have their
own terms. See [licensing guidance](docs/LICENSING.md).

Bug reports, documentation, hardware evidence and focused pull requests are
welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and use
[Issues](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)
or [Pull Requests](https://github.com/niwantha33/zephyr-eclipse-workbench/pulls).
