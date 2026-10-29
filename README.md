# Zephyr Eclipse Workbench

An Eclipse-based development environment for Zephyr applications across semiconductor vendors.

## Install from the beginning

Install **Eclipse IDE for Embedded C/C++ Developers** first. Then install the
Workbench `_p2.zip` from [GitHub Releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases)
through **Help > Install New Software > Add > Archive**, restart Eclipse, and
select **Zephyr > Set Up Zephyr**. The Source code ZIP is not the plug-in installer.

Follow the [fresh-install guide](docs/FRESH_INSTALL.md) for the exact Windows
file, setup order, updates, probe tools and optional Trace Compass installation.

## Project status

0.1.21 includes shared target dependency repair, native flash/debug integration,
separate Debug/Release build profiles and an OpenOCD CTF RAM snapshot preview.
See the [build and trace guide](docs/BUILD_CONFIGURATIONS_AND_TRACE_PREVIEW.md)
and the [RTOS analysis roadmap](docs/RTOS_ANALYSIS_ROADMAP.md).
The proposed [observability specification](docs/RTOS_OBSERVABILITY_SPEC.md)
covers application logs, CPU charts, register analysis and fault/core-dump
investigation; these expanded analysers are not yet implemented.

Use the native project Properties > Zephyr page to select an exact board/core, then build through Eclipse. Missing supported compiler, HAL and build-host dependencies are prepared automatically. Each project keeps its independent build output.

This is a development preview. Eclipse/p2 packaging and native Windows regression
tests pass, along with six Windows firmware build cases across TI, STM32,
Nordic and Espressif, including MCUboot. MSPM0 snapshot capture and timeline
viewing were observed on physical hardware. These results do not establish
all-vendor hardware support or completion of the clean-machine desktop tests.

## Scope

- Eclipse IDE for Embedded C/C++ Developers with an installable plug-in.
- Hardware discovery from the selected Zephyr checkout, including external board roots.
- Common build and configuration controls across TI, STMicroelectronics, Espressif, Nordic, Realtek, and other Zephyr-supported vendors.
- Existing West, CMake, Ninja, Kconfig, Devicetree, compiler, and runner infrastructure.
- Open-source dependencies permitting commercial use without mandatory software licence/subscription/royalty fees; per-component licence obligations still apply.
- Optional pinned Git submodules where upstream packaging/West/p2 integration is unsuitable.
- Separate evidence for discovery, compilation, flashing, and debugging.
- Integrated update management for the workbench, compatible Eclipse components and managed tools, with staged recovery and pinned project dependencies.
- Measurable quality targets for a simpler Zephyr workflow than competing IDE configurations; comparative results are not yet available.

Vendor presence does not imply support for every chip or peripheral. Physical execution requires a compatible board, connection, probe, and vendor tools where applicable.

## Historical initial baseline

| Item | Baseline | Evidence |
|---|---|---|
| Host | Windows first; Linux planned | User-reported Windows environment |
| Eclipse | Embedded C/C++ package 2026-09 | Candidate; plug-in not yet tested |
| Zephyr | v4.4.0 | User-reported successful ESP32-S3 Hello World build |
| West | 1.5.0 | User-reported terminal output |
| Initial board target | esp32s3_devkitc/esp32s3/procpu | Compilation reported; exact physical board unconfirmed |
| Java, Python, SDK | Record exact versions during implementation | Not yet established |

The reported command-line build is not evidence that this plug-in works.

## Preview 0.1.11 — project workflow

The review branch adds native **File > New > Other > Zephyr > Zephyr Project**
and **File > Import > Zephyr > Existing Zephyr Application**, independently
bound to the verified managed Windows x64 environment. New source projects are
portable and protected against overwrite. Each application has an isolated
build directory, and the existing HelloZephyr build cache is retained.

This section describes the historical 0.1.11 baseline. Current dependency preparation and validation are described in the [0.1.20 repair notes](docs/CROSS_VENDOR_REPAIR_0.1.20.md).

**Updates:** A source merge is separate from an Eclipse installation update. Use the Workbench update command and restart Eclipse after installation. The update channel points to an immutable, verified p2 release.

## Start here

- [One-click project setup, build, firmware output, Flash and Debug workflow](docs/ONE_CLICK_WORKFLOW.md)
- [Import applications from a parent folder with project selection](docs/IMPORT_EXISTING_APPLICATIONS.md)
- [Global, cross-vendor OpenOCD tool manager](docs/SHARED_OPENOCD_TOOLS.md)
- [Build, install locally and use the development slice](docs/DEVELOPMENT.md)
- [Set up workbench updates once](docs/UPDATE_CHANNEL.md)
- [Detect and reuse tools with Tools Manager](docs/TOOLS_MANAGER.md)
- [Beginner setup and verified first build](docs/BEGINNER_SETUP.md)
- [First build evidence and limitations](docs/BUILD_REPORT.md)
- [Guided tools/project/update workflow](docs/GUIDED_WORKFLOW.md)
- [Step-by-step roadmap](docs/ROADMAP.md)
- [Architecture and boundaries](docs/ARCHITECTURE.md)
- [Hardware research and compatibility policy](docs/COMPATIBILITY.md)
- [Validation and release criteria](docs/VALIDATION.md)
- [Automatic updates and recovery](docs/UPDATES.md)
- [Quality targets and comparison method](docs/QUALITY.md)
- [Open-source and commercial-use policy](docs/LICENSING.md)
- [Dependency register and submodules](docs/DEPENDENCIES.md)
- [Contributing](CONTRIBUTING.md)

## Licensing and publication

Original workbench material is licensed under [Apache-2.0](LICENSE), unless a more specific notice applies. Third-party components retain their own licences and obligations.
The core workflow must use open-source software permitting commercial use without mandatory licence fees. Proprietary blobs and closed utilities are excluded from that workflow; some board features may therefore be unavailable. Exact dependency and firmware-feature audits are pending. See [the policy](docs/LICENSING.md).

This is an independent integration project. Upstream sources: [Zephyr](https://github.com/zephyrproject-rtos/zephyr) and [Eclipse Embedded CDT](https://eclipse-embed-cdt.github.io/).



Native Eclipse Run/Debug controls, CCS/other-IDE adapter boundaries and the editor improvement roadmap are described in [IDE integration](docs/IDE_INTEGRATION.md).
