<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Eclipse Workbench

**See your hardware. Understand your configuration. Investigate your firmware.**

An open-source, Eclipse-based workbench for **Zephyr RTOS** firmware development.
Connect the selected board, generated Kconfig and Devicetree, build artifacts,
native debugging and offline evidence in one embedded C/C++ workflow.

**Designed for hardware-first development:** explore a real board's generated
Devicetree as linked blocks, inspect YAML and effective configuration, switch
isolated firmware build profiles, and navigate from source-backed hardware
views to a native Eclipse debugging workspace.

> **Development-preview status:** This README describes source-backed features
> on the current development branch, **not a guarantee that every feature is
> in the latest published installer or qualified on physical hardware**.
> Release gate **G0 is open**. Check [release status](RELEASE_STATUS.md),
> the [trace capability truth table](docs/TRACE_ANALYSIS_CAPABILITIES.md),
> and the [release assets](https://github.com/niwantha33/zephyr-eclipse-workbench/releases)
> before relying on a particular workflow.

[![PR build](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/workflows/build.yml/badge.svg?event=pull_request)](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/workflows/build.yml)
[![GitHub release](https://img.shields.io/github/v/release/niwantha33/zephyr-eclipse-workbench?include_prereleases&label=latest%20release)](https://github.com/niwantha33/zephyr-eclipse-workbench/releases)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/niwantha33/zephyr-eclipse-workbench)](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)

**🌐 [Explore the website & feature catalogue](https://niwantha33.github.io/zephyr-eclipse-workbench/)** · **[Download releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases) · [Install on Windows](docs/FRESH_INSTALL.md) · [Beginner guide](docs/BEGINNER_SETUP.md) · [Report an issue](https://github.com/niwantha33/zephyr-eclipse-workbench/issues)**

## Installation direction — one HTTPS link

**Release requirement (not yet available for first-time installation):**
new users should install the Workbench into a compatible Eclipse IDE using
**Help → Install New Software**, paste a public HTTPS p2 update-site URL,
select **Zephyr Eclipse Workbench**, and complete Eclipse's normal licence/trust
review. No manual ZIP/archive download, GitHub login, token, Maven build or
repository checkout should be required for this path.

The proposed public site location is
`https://niwantha33.github.io/zephyr-eclipse-workbench/updates/`.
**Do not treat this as a working installer URL yet.** The current `updates`
branch contains versioned p2 artifacts for existing Workbench updates, but an
anonymously accessible Eclipse-installable public site at this URL has **not
been verified**. The [development-preview installation guide](docs/FRESH_INSTALL.md)
documents the temporary ZIP fallback until URL installation is published and
qualified.

Before making URL installation the default, the release pipeline must publish
a valid p2 repository at a stable HTTPS endpoint and pass a clean-Eclipse
install, restart, feature/plug-in verification, upgrade and recovery smoke test
on supported hosts. Never label an update manifest or successful package build
as a proven first-time network installation. Keep Eclipse's normal trust and
licence prompts; do not silently modify users' pinned Zephyr/SDK environments.

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

### Devicetree Graph Studio

Open **Configuration Studio → Hardware → DTS Graph Studio — connected blocks**
for the selected, configured board/profile. The native Eclipse graph shows
observed parent/child nodes and binding-resolved Devicetree references,
with zoom, local focus, node inspection and source-line navigation. Links
to GPIO, interrupts, clocks, resets and pinctrl are shown **only when resolved
from the generated DTS and available Zephyr binding evidence**. The graph is
read-only, limits the visible neighborhood, and is not a physical wiring or
runtime-state diagram.
[DTS Graph Studio guide](docs/DTS_GRAPH_STUDIO.md).

### Connected-block YAML visualizer

Inspect an application YAML file or a generated build YAML such as
`build_info.yml` or `zephyr/runners.yaml` using **Zephyr YAML Structure
(Read-only) → Connected blocks**. Pan, zoom, inspect map/list/scalar cards
and navigate back to verified source lines. Edges represent YAML syntax
nesting, **not** real Zephyr dependencies or connected hardware.
[YAML visualizer guide](docs/YAML_CONNECTED_GRAPH.md).

### Configuration Studio and Insights

Inspect requested `prj.conf` or overlay settings alongside the generated
`.config` and `zephyr.dts` for a specific build, board and profile.
Follow available Kconfig, Devicetree and driver evidence to investigate
why a setting or device differs from the request.
[Configuration Studio guide](docs/CONFIGURATION_STUDIO.md).

### Board discovery and build profiles

Browse the selected Zephyr checkout's **offline HWMv2 board catalogue**
by manufacturer, silicon vendor, SoC and exact target qualifier without
silently changing the active project. Create **named firmware profiles**
with Kconfig fragments, compiler definitions and independent build folders.
Use the Eclipse **Profile** toolbar selector beside Build and Flash; an
explicit build verifies requested settings against generated output before
they can be treated as effective. Merely choosing a profile does not
rebuild or flash firmware.
[Board discovery](docs/EMBEDDEDHUB_BOARD_DISCOVERY.md) ·
[Profile Studio](docs/ZEPHYR_PROFILE_STUDIO.md).

### Hardware Debug workspace, registers and target memory

The **Zephyr Hardware Debug** perspective brings together Eclipse Debug,
CPU Registers, Variables, Expressions, Console and the Workbench's SVD
Explorer. The **Open Target Memory Browser** command opens the installed
CDT Memory Browser or Eclipse Memory view using the **existing** suspended
GDB context. Actual memory access depends on the selected live debugger,
board, permissions and address; opening a view performs no read or write.
Use **Pin Selection / Follow Selection** to keep supported hardware views
aligned to the same project/build without guessing a DTS-to-SVD register
mapping.
[Smart view binding](docs/SMART_VIEW_BINDING.md) ·
[Target memory guide](docs/TARGET_MEMORY_BROWSER.md).

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

### Debug Trace Console and RTOS investigation

The **Zephyr Debug Trace Console** inspects effective build configuration
and offers explicit saved RAM CTF snapshot capture, UART/USB serial text
capture and an opt-in passive localhost RTT text receiver. It does not
implement an ITM/SWO receiver, semihosting receiver or live CTF streaming.
[Debug Trace Console guide](docs/DEBUG_TRACE_CONSOLE.md).

For **saved CTF evidence**, the native offline views include Thread
Timeline, Zephyr CPU Load, Contention Report, Memory Profile, and custom
tracepoint observations. CPU and waiting-time results are derived only
where event boundaries allow; absent data remains unknown. Trace Compass
handoff and offline fault/ELF investigation provide additional context.
These analyses are **Partial**, not live CPU meters or broadly validated
hardware profilers.
[RTOS investigation guide](docs/RTOS_INVESTIGATION_GUIDE.md) ·
[Trace capability truth table](docs/TRACE_ANALYSIS_CAPABILITIES.md) ·
[Memory Profile guide](docs/MEMORY_PROFILE.md).

## Scope & limitations

- Current releases are **development previews**; release gate **G0 remains open**.
- Feature coverage and validation differ by board, SoC, toolchain, runner and probe.
- A merged source feature is not necessarily present in the latest published p2 update; use a release containing that change.
- Generated `.config` and `zephyr.dts` describe the selected build, not runtime peripheral state or physical wiring.
- Devicetree Graph Studio links describe observed DTS hierarchy and decoded references, not verified electrical nets. The YAML connected-block graph describes YAML **syntax**, not dependencies. Large graphs are bounded previews.
- Chip Pin Canvas routes are logical unless supported by authoritative package/pin metadata; electrical limits and pin ownership require device documentation.
- SVD inspection requires an exact matching vendor definition and a compatible suspended debug session; automatic vendor-SVD acquisition is not established.
- Target Memory Browser is a shortcut to native Eclipse debug-memory UI, not a custom memory reader, dump/diff tool or guarantee of accessible addresses. Debugger memory editing can change a running system.
- RTT text capture is a passive local receiver; ITM/SWO and semihosting reception are not implemented. Saved RAM CTF capture is not live CTF streaming.
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
 Kconfig + Devicetree + profile inputs
                |
     Generated build evidence
   (.config / zephyr.dts / YAML)
                |
  Configuration + Hardware Studios
       DTS/YAML graph views
                |
       Firmware / ELF
                |
    Flash + CDT/GDB debug
                |
 SVD registers / target memory
                |
 Saved CTF / RTOS / fault analysis
                |
     Evidence CLI / bundle
```

## Capability catalogue — development preview

The status labels below describe **repository implementation scope**, not
release certification or universal board coverage. **Preview** means a
source-backed workflow with environment-specific qualification still required;
**Limited** means constrained inputs, evidence, or integration; **Partial**
uses the authoritative [trace-analysis status](docs/TRACE_ANALYSIS_CAPABILITIES.md).
For the larger searchable catalogue, see the
[feature website](https://niwantha33.github.io/zephyr-eclipse-workbench/)
([publication setup](docs/MARKETING_SITE.md)).

| Capability | What the workbench provides | Scope | Guide |
| --- | --- | --- | --- |
| Managed setup and projects | Guided West/Zephyr environment, project wizard and existing-application import | Preview | [Start here](docs/BEGINNER_SETUP.md) |
| Offline board catalogue | Exact HWMv2 board/SoC/revision/qualifier discovery in selected checkout | Limited | [Board Discovery](docs/EMBEDDEDHUB_BOARD_DISCOVERY.md) |
| Native build workflow | West/CMake configure, compile and project-linked `.zew-build` outputs | Preview | [Project workflow](docs/ONE_CLICK_WORKFLOW.md) |
| Named build profiles | Kconfig fragments, C/C++ macros, isolated board/profile builds and Build/Flash toolbar selection | Preview | [Profile Studio](docs/ZEPHYR_PROFILE_STUDIO.md) |
| Configuration Studio | Requested vs effective Kconfig, explanation/dependencies, editable reviewed overrides and build evidence | Preview | [Configuration Studio](docs/CONFIGURATION_STUDIO.md) |
| Hardware Studio | Build-backed detected nodes, GPIO/controller views, pin routes and overlay source navigation | Limited | [Hardware Studio](docs/VISUAL_HARDWARE_STUDIO.md) |
| DTS Graph Studio | Native linked-node graph with resolved DTS references, focus/zoom and source navigation | Limited | [DTS graph](docs/DTS_GRAPH_STUDIO.md) |
| YAML Structure graph | Connected-block structure/outline of owned or generated YAML, with read-only generated output | Limited | [YAML graph](docs/YAML_CONNECTED_GRAPH.md) |
| Smart selection | Same-project/build node context across Hardware and SVD views; explicit register/map navigation | Limited | [View binding](docs/SMART_VIEW_BINDING.md) |
| Flash and debug | Supported runners, OpenOCD and Eclipse CDT/DSF-GDB with bounded metadata preflight | Limited | [Debug guide](docs/DEBUG_REGISTERS_AND_THREADS.md) |
| Hardware Debug perspective | Native CPU registers, variables, expressions, consoles and SVD tool arrangement | Preview | [Workspace guide](docs/SMART_VIEW_BINDING.md) |
| SVD Register Explorer | Exact-device vendor SVD and explicit suspended-session read-only register snapshots | Limited | [Registers](docs/DEBUG_REGISTERS_AND_THREADS.md) |
| Target Memory Browser | Open CDT/Platform memory UI in the selected GDB debug context | Limited | [Target memory](docs/TARGET_MEMORY_BROWSER.md) |
| Debug Trace Console | Saved RAM CTF capture, UART/USB text and passive local RTT text capture | Limited | [Trace console](docs/DEBUG_TRACE_CONSOLE.md) |
| Offline RTOS analysis | Saved-CTF thread timeline, CPU time, contention, tracepoints and memory allocations | Partial | [Trace capabilities](docs/TRACE_ANALYSIS_CAPABILITIES.md) |
| Fault and trace investigation | Offline ELF/fault context, bounded evidence reports and optional Trace Compass handoff | Partial | [Investigation](docs/RTOS_INVESTIGATION_GUIDE.md) |
| Evidence CLI | Read-only snapshot/compare/verify, guarded provenance and bounded sanitised bundles | Limited | [CLI reference](docs/CLI_REFERENCE.md) |
| Updates and publishing | Eclipse p2 updates and separate package integrity/publication checks | Preview | [Publication design](docs/PRIVATE_UPDATE_PUBLISH.md) |

## Quick start — Windows (development preview)

**Current status:** direct public URL installation is a release requirement,
**not a verified installation method today**. The following archive steps are
a temporary workaround for development previews; they must not be presented
as the final user experience.

1. Install [Eclipse IDE for Embedded C/C++ Developers](https://www.eclipse.org/downloads/packages/) on Windows x64, with its required Java runtime.
2. **Temporary preview path only:** open [GitHub Releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases), read that release's `START_HERE` instructions and download the asset ending in `_p2.zip` (not GitHub's source-code ZIP).
3. In Eclipse choose **Help → Install New Software → Add → Archive**, select that `_p2.zip` without extracting it, finish the licence/trust review and restart Eclipse.
4. Choose **Zephyr → Set Up Zephyr** to prepare the managed toolchain and first workspace.
5. Create a project with **File → New → Other → Zephyr → Zephyr Project**, or import one with **File → Import → Zephyr → Existing Zephyr Application**.
6. Set the exact board/core under **Project → Properties → Zephyr**, select a built-in or named profile and **Build**. The quick **Profile** toolbar menu can also switch profiles; switching alone does not build or flash.
7. Configure the runner/probe before using **Flash** or **Debug** on hardware.

**Target first-time installation after qualification:** in Eclipse use
**Help → Install New Software → Add**, enter the public Workbench HTTPS p2
update-site URL (without **Archive**), select the Workbench feature, review
the proposed installation and restart. Eclipse should subsequently check the
same public channel for compatible updates, with user approval for
installation and restart as required. The final verified URL will replace the
temporary archive instructions above.

**URL-install acceptance:** the site must be publicly readable without
credentials; include correct p2 metadata and dependencies; be published only
from verified artifacts; survive a clean Windows/Eclipse first installation,
restart, update to a newer version and recovery from a failed update; and
never require an archive, local source build or token. A working website or
a populated `updates` Git branch alone is insufficient evidence.

Follow the [full installation and update guide](docs/FRESH_INSTALL.md) for Eclipse/JDK
versions, OpenOCD setup, release updates and trust prompts.
For the first workspace and build, see [Beginner setup](docs/BEGINNER_SETUP.md)
and the [one-click project workflow](docs/ONE_CLICK_WORKFLOW.md).

## Working with your hardware

**Configuration:** Open **Zephyr → Configuration Studio** for the current
project/target to review effective Kconfig and Devicetree inputs.
[Configuration CLI commands](docs/CONFIGURATION_STUDIO_CLI.md).

**DTS graph:** Open **Zephyr → Configuration Studio → Hardware** and select
**DTS Graph Studio — connected blocks** after configuring the exact board and
profile. [Graph guide](docs/DTS_GRAPH_STUDIO.md).

**YAML:** Select a saved project or generated YAML file and use
**Window → Show View → Other → Zephyr YAML Structure (Read-only)** for the
connected-block graph. [YAML guide](docs/YAML_CONNECTED_GRAPH.md).

**Registers:** Open **Window → Show View → Other → Debug → Zephyr Hardware
Register Explorer**, load the exact vendor SVD and refresh after suspending
a supported target. [Register guide](docs/DEBUG_REGISTERS_AND_THREADS.md).

**Memory:** Suspend a target, select its frame in Debug, then choose
**Zephyr → Run & Debug → Open Target Memory Browser**. The command opens
Eclipse's own memory tooling, not a second debug connection.
[Memory guide](docs/TARGET_MEMORY_BROWSER.md).

**Trace:** Use **Zephyr → Capture & Analyse → Debug Trace Console...**
for explicit saved RAM CTF, serial text and passive RTT reception; inspect
saved CTF in the offline analysis views.
[Console guide](docs/DEBUG_TRACE_CONSOLE.md).

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
| Inspect hardware and configuration | [Visual Hardware Studio](docs/VISUAL_HARDWARE_STUDIO.md) · [Configuration Studio](docs/CONFIGURATION_STUDIO.md) |
| Explore linked DTS nodes | [DTS Graph Studio](docs/DTS_GRAPH_STUDIO.md) |
| Visualise YAML as connected blocks | [YAML Connected Graph](docs/YAML_CONNECTED_GRAPH.md) |
| Discover local Zephyr targets | [EmbeddedHub Board Discovery](docs/EMBEDDEDHUB_BOARD_DISCOVERY.md) |
| Create firmware build variants | [Profile Studio](docs/ZEPHYR_PROFILE_STUDIO.md) |
| Debug and inspect peripheral registers | [Registers and threads](docs/DEBUG_REGISTERS_AND_THREADS.md) |
| Open native target memory inspection | [Target Memory Browser](docs/TARGET_MEMORY_BROWSER.md) |
| Understand cross-view navigation | [Smart view binding](docs/SMART_VIEW_BINDING.md) |
| Capture debug text or saved trace | [Debug Trace Console](docs/DEBUG_TRACE_CONSOLE.md) |
| Inspect offline RTOS CPU, waiting and memory | [Trace capability truth table](docs/TRACE_ANALYSIS_CAPABILITIES.md) |
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

## Not supported

- No automatic runtime pin/wire verification, universal board compatibility, vendor-approved package pinout or one-click peripheral driver activation.
- No automatic exact SVD download, proven DTS-to-MMIO register address matching, memory snapshot diff or automatic memory edits.
- No built-in ITM/SWO or semihosting receiver, universal live CPU-load meter or verified leak diagnosis from incomplete stock CTF events.
- Experimental Theia/CDT.cloud service work is not a shipping replacement for the current Eclipse RCP workbench.
- **Live CTF streaming into the IDE is not supported.** The **Zephyr → Capture & Analyse → Start Live Trace (not yet available)** menu entry is an informational stub only; it starts no transport, parser or background worker. See [Live CTF streaming design (proposal only)](docs/LIVE_TRACE_STREAMING_DESIGN.md). Existing offline CTF snapshots and independently configured text console receivers do not make live binary CTF streaming available.
