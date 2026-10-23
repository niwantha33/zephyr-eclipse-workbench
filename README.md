# Zephyr Eclipse Workbench

An Eclipse-based development environment for Zephyr applications across semiconductor vendors.

## Project status

First development slice implemented: two Eclipse plug-ins, an installable feature and a local p2 update-site build. Core checks and packaging have passed on Linux. Desktop installation, Windows execution and hardware validation remain pending; this is not the completed v0.1 milestone or a stable release.
The repository remains private during development; public publication is a separate future action.

The required workflow is: Install / Manage Zephyr Tools, File > New > Zephyr Project (new or existing), vendor/SoC/board/core selection, native Eclipse editing/build/run/debug, and click-to-install update notifications. See [the guided workflow specification](docs/GUIDED_WORKFLOW.md); these capabilities are not all implemented in the current prototype.

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

## Initial baseline

| Item | Baseline | Evidence |
|---|---|---|
| Host | Windows first; Linux planned | User-reported Windows environment |
| Eclipse | Embedded C/C++ package 2026-09 | Candidate; plug-in not yet tested |
| Zephyr | v4.4.0 | User-reported successful ESP32-S3 Hello World build |
| West | 1.5.0 | User-reported terminal output |
| Initial board target | esp32s3_devkitc/esp32s3/procpu | Compilation reported; exact physical board unconfirmed |
| Java, Python, SDK | Record exact versions during implementation | Not yet established |

The reported command-line build is not evidence that this plug-in works.

## Start here

- [Build, install locally and use the development slice](docs/DEVELOPMENT.md)
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
