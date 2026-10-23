# Zephyr Eclipse Workbench

An Eclipse-based development environment for Zephyr applications across semiconductor vendors.

## Project status

Planning foundation only. No executable Eclipse plug-in or installer is available yet.
The repository remains private during development; public publication is a separate future action.

The intended workflow is: import or create a Zephyr workspace, select a board, create an application, build, configure, flash, and debug from Eclipse.

## Scope

- Eclipse IDE for Embedded C/C++ Developers with an installable plug-in.
- Hardware discovery from the selected Zephyr checkout, including external board roots.
- Common build and configuration controls across TI, STMicroelectronics, Espressif, Nordic, Realtek, and other Zephyr-supported vendors.
- Existing West, CMake, Ninja, Kconfig, Devicetree, compiler, and runner infrastructure.
- Separate evidence for discovery, compilation, flashing, and debugging.

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

- [Step-by-step roadmap](docs/ROADMAP.md)
- [Architecture and boundaries](docs/ARCHITECTURE.md)
- [Hardware research and compatibility policy](docs/COMPATIBILITY.md)
- [Validation and release criteria](docs/VALIDATION.md)
- [Contributing](CONTRIBUTING.md)

## Licensing and publication

A distribution licence has not yet been selected. Select an explicit licence and review third-party notices before inviting external contributions or publishing releases.
Do not bundle toolchains, proprietary probes, or binary blobs without checking their distribution terms.

This is an independent integration project. Upstream sources: [Zephyr](https://github.com/zephyrproject-rtos/zephyr) and [Eclipse Embedded CDT](https://eclipse-embed-cdt.github.io/).
