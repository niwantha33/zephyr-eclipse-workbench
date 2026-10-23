# Architecture

Status: proposed architecture, 2026-10-23. Implementation has not started.

## Product decision

Build an installable Eclipse plug-in, using Eclipse CDT for C/C++ tooling and existing Zephyr tools for firmware operations. Reusable scripts may assist development but are not the final user interface.

Do not fork Zephyr, rewrite its build system, or maintain an independent hard-coded catalogue of all vendor chips.

## Components

| Component | Responsibility |
|---|---|
| Core service | Workspace identity, settings, environment discovery, typed operation requests, and result models |
| Eclipse UI | Project wizard, board selector, build configurations, progress, console, diagnostics, and help |
| Tool process service | Explicit executable paths and argument lists, process environment, working directory, cancellation, exit status, and logs |
| Hardware discovery adapter | Read hardware metadata through supported facilities of the selected Zephyr version; include boards, revisions, qualifiers, and external roots |
| Build adapter | West build invocation, separate output directories, pristine rebuilds, sysbuild selection, generated artefact discovery |
| Configuration adapter | Launch menuconfig/guiconfig where supported, inspect resolved settings, and persist reviewed application fragments |
| CDT adapter | Consume compilation database and generated settings using a verified CDT integration mechanism |
| Execution adapter | Board runner discovery and flash, debug-server, and attach capabilities; vendor-specific adapters only for missing workflows |
| Update service | Component ownership/version inventory, tested compatibility plans, p2 scheduling/staging, idle coordination, restart notification, environment migration and recovery |
| Validation metadata | Store discovery, build, flash, and debug evidence independently |

Initial bundle boundary proposal: core and Eclipse UI bundles, an installable feature, and a p2 repository. Choose exact Eclipse extension points, CDT APIs, Java baseline, target platform, and Tycho versions in an implementation spike before fixing the source layout.

## Settings and portability

- Machine preferences hold local Python, SDK, workspace, and debug-tool locations.
- Project settings hold portable application paths, board target, configuration fragments, overlays, and build profile names.
- Never commit user-specific absolute paths, Eclipse workspace metadata, credentials, or downloaded SDKs.
- Recognise existing workspace layouts; do not assume Zephyr is directly below the workspace root.
- Resolve Zephyr through West/project metadata and explicit user configuration.
- Cache discovered hardware per checkout identity and version; invalidate after checkout or dependency changes.
- Keep output directories separate by board, qualifiers, and profile. Never clean arbitrary paths.
- Run long operations as cancellable background jobs; surface failures rather than reporting a successful launch as a successful build.
- Serialize mutations to a workspace and operations that share a build directory.
- Invoke processes with argument arrays, not shell-interpolated command strings.

## Configuration boundaries

Kconfig controls software configuration. Devicetree describes hardware. The UI must retain that distinction.

Start with upstream configuration interfaces and generated-output inspection. A native graphical Kconfig editor is a later enhancement requiring dependency semantics, help, value provenance, and persistent-fragment handling; it is not part of the first preview.

Do not copy every resolved .config value into prj.conf automatically. Present intended changes and use version-appropriate upstream configuration-saving facilities where available.

## Flash and debugging

Use configured runner metadata and West commands where supported. Detect missing capabilities explicitly. Do not assume every board supports west flash or west debug.

Debugging requires the matching architecture GDB, server executable, transport, probe, target image, and port settings. Integrate with Eclipse debug facilities after an Arm and Xtensa proof of concept. RTOS thread awareness is conditional on server and firmware support.

Multi-image projects require explicit domain/image selection. Flash/run on hardware and emulation are separate launch modes.

## Dependency and release strategy

Pin the tested Zephyr baseline and respect its manifest revisions. Support importing other versions without calling them validated. Fetch only required dependency sets where feasible. Apply the open-source/commercial-use policy before installing any dependency; closed vendor downloads are excluded from the compliant workflow.

Develop a reproducible p2 update-site build first. Consider a bundled Eclipse distribution only after plug-in installation and upgrades are reliable.

## Quality and maintenance requirements

The quality ambition is measurable superiority on specific Zephyr workflows, not an unverified claim about every IDE feature. See [quality targets](QUALITY.md).

Use Eclipse p2 for installed Eclipse features and a separate versioned environment manager for product-owned tools. Keep external installation ownership explicit. Workbench maintenance must not silently migrate application source, Zephyr manifests, SDK/compiler selection or vendor firmware.

Automatic checks/downloads and eligible idle patch installation are product requirements. They are not implemented. Restart, migration, trust and recovery rules are specified in [updates and recovery](UPDATES.md). Do not describe p2 configuration history as a complete backup of application files or external toolchains.

## Open-source dependency boundary

Follow [LICENSING.md](LICENSING.md) and [DEPENDENCIES.md](DEPENDENCIES.md). Original workbench material is Apache-2.0. All dependency mechanisms, including West, p2, Maven, Python packages and Git submodules, require exact-version licence/provenance records.

Use upstream dependency mechanisms first. Git submodules are allowed for justified source dependencies, pinned to exact commits and retaining all upstream notices. Submodule separation does not alter licence obligations.

Represent open-source eligibility per board operation and firmware feature. A closed radio library or proprietary-only runner must not be silently downloaded or advertised as part of the compliant workflow.
