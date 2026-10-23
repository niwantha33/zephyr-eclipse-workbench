# Guided Eclipse workflow requirements

Status: agreed product direction recorded 2026-10-23. Workbench updates/version details and Tools Manager are implemented. Release 0.1.5 adds a Windows x64 beginner bootstrap, pinned packages/SDK, generated Hello World and native project builder for the qemu_x86/atom first-build profile. Generic board/vendor selection, full CDT indexing, import and hardware run/debug remain pending. See BEGINNER_SETUP.md and TOOLS_MANAGER.md for the exact boundaries.
This specification defines the normal user workflow and takes priority over the manual global-configuration prototype.

## User-visible workflow

| Order | Eclipse entry point | User action | Required result |
|---:|---|---|---|
| 1 | Zephyr > Install / Manage Zephyr Tools... | Review detected tools and install selected missing components | Dependency table, compatible versions, progress and working environment |
| 2 | File > New > Zephyr Project... | Choose New application or Existing application | Guided project setup with no requirement to edit global application paths |
| 3 | Hardware page | Select vendor, family, SoC, board, revision and core/variant as applicable | Valid exact Zephyr target selected automatically from metadata; no manual qualifier typing |
| 4 | New-application page | Choose location, tool environment and starter template | Independent source project appears in Project Explorer with CDT editing and build bindings |
| 5 | Existing-application page (also File > Import > Zephyr > Existing Application) | Choose source folder and resolve detected settings | Existing source preserved; project gains Zephyr/CDT integration |
| 6 | Standard Eclipse controls | Build/Clean, Run, Debug and configuration changes | Actions apply to the selected project's active build profile |
| 7 | Update notification / Help > Check for Updates | Click Install Update for a compatible workbench release | Download, verified installation, restart prompt and recovery path |

The priority experience is guided installation, native project creation/import, normal Eclipse editing/build/debug, and click-to-install workbench updates. A console remains available for diagnostics rather than being the primary setup interface.

## 1. Zephyr Tools Manager

Use "tools" or "development tools" in the interface. "Dependencies" can appear as explanatory detail.

Detect first; reuse compatible existing installations. Present a table with component, required version/range, installed version, status, install location and selected action. Statuses include Ready, Missing, Incompatible, Downloading, Installing, Verifying, Failed and Cancelled.

Inventory includes the compatible Python interpreter, isolated virtual environment, West and Python requirements, Git, CMake, Ninja, DTC/other required host utilities, Zephyr source/modules, SDK architecture toolchains and the selected board's open-source flash/debug utilities. Java/CDT/PDE requirements for the host or developer environment are reported separately; end users do not need PDE to use the plug-in.

Install only the required architecture toolchain by default, with optional additional architectures. Decide exact versions from the selected tested Zephyr environment and dependency policy; do not assume a vendor maps to only one CPU architecture.

Show aggregate progress plus per-component phase, bytes where available and logs. Use background jobs with retry and cancellation. Interrupted operations must not mark a component ready. Verify downloaded provenance/checksums, applicable licences, installation and a tool version check. Preserve working installations and stage replacements separately.

Never rely on an unrelated tool being accidentally first on the global PATH. Bind the chosen environment explicitly. Allow approved offline packages and local SDK selection. No mandatory proprietary components, trials or commercial-only activation. Show feature limitations when a board requires excluded vendor binaries.

The implementation and exact package licence/provenance audit are prerequisites; this is not an installer already delivered in the first prototype.

## 2. Project wizard and hardware selection

The wizard opens from File > New and has mode choices New application / Existing application. Provide a matching Import entry point for existing applications.

Discover hardware from the selected Zephyr checkout, including external roots. Distinguish chip vendor from board manufacturer: a third-party board using an Espressif chip should be findable under Espressif.

Filter vendor -> family/series -> SoC -> supported board -> revision -> core/variant where relevant. Also allow direct board search. Skip redundant levels when only one option exists. Show only valid combinations and the resolved exact target in the final summary.

For ESP32-S3, selecting the main CPU must resolve to esp32s3_devkitc/esp32s3/procpu rather than persisting only esp32s3_devkitc. Test appcpu and other multi-core targets independently.

A bare SoC selection is insufficient to invent board wiring, flash layout or debug settings. If no supported board matches, offer an existing custom board root or a separate future board-porting workflow. Do not advertise automatic support for arbitrary chips.

## 3. New project contents

| Path | Purpose | Required |
|---|---|---|
| CMakeLists.txt | Standard Zephyr application build and source list | Yes |
| prj.conf | Application Kconfig requests | Yes; may start minimal |
| src/main.c | Simple Hello World/main-thread starter | Yes for C starter |
| .zew/project.json | Portable workbench project schema, exact target and build-profile definitions | Yes; no machine-specific paths |
| .gitignore | Exclude .zew-build, generated output and local-only files | Yes |
| README.md | Project purpose, selected target and terminal/IDE build instructions | Yes |
| .project and CDT configuration | Eclipse project identity, Zephyr nature/builder and C/C++ integration | Generated/managed; portable portions only |
| boards/ | Explicitly selected Devicetree overlays and board-specific application settings | Only when needed |
| include/, tests/, additional sources | Added for selected templates/features | Optional |

Choose the application's licence explicitly if generating a licence file; do not assume every user's firmware must use the workbench's own licence. Preserve required notices for reused template material.

Create the project outside the upstream Zephyr tree. Do not overwrite an existing nonempty folder without resolving it as an import. Open src/main.c on completion. Application structure stays buildable through West independently of Eclipse.

Tool installations and absolute local paths stay in environment mappings/preferences. Project metadata stores portable references and build settings. Use separate build output per project/profile/exact target.

## 4. Existing project detection

Read, in order where present, our portable project metadata, explicitly selected existing Zephyr build metadata (CMakeCache, generated configuration and runner information), application CMake/preset/manifest settings, and user-selected external board roots.

Do not execute arbitrary project scripts merely to inspect a folder. Do not treat a guessed board name as authoritative. Device/board information is not necessarily stored in source projects.

Show detected target, Zephyr environment, configurations, overlays, sysbuild/domain settings and their provenance. If information is absent or conflicting, ask for selection once in the wizard and persist the resulting project profile. Validate exact qualifiers against the selected checkout.

Import in place by default, preserve source/prj.conf/overlays, and show any generated metadata changes. Do not copy a stale build cache into a different machine environment.

## 5. Native Eclipse/CDT integration

Use a Zephyr project nature/builder and active project/profile, rather than one global application/board setting. Project > Build Project, Build All and Clean route to the correct West builds; never run an unrelated generic Make builder alongside West.

Populate CDT settings from the actual compilation database/generated headers. Provide navigation, completion and Problems markers for compiler, CMake, Kconfig and Devicetree diagnostics. Refresh source/build settings after configuration changes.

Run on hardware means building/programming/resetting through the board's supported workflow. Emulated execution is a distinct launch mode only for supported emulation targets. Debug uses a per-project Eclipse launch configuration with the matching GDB, server, probe/port and image/domain.

Reuse Eclipse Embedded/CDT facilities where compatible with the architecture. Do not assume Arm-specific launch support covers Xtensa and RISC-V; implement and validate adapters as necessary. Missing runners/tools are visible and unsupported actions are disabled with a concrete reason.

Preserve user-modified launch options and allow advanced overrides. Flash/run/debug capability and licence eligibility are tracked per board/probe/operation.

## 6. Click-to-install workbench updates

Automatically check the configured release channel asynchronously at startup and on schedule while Eclipse is running. Notify once per new compatible release; show current/available version and release notes. The normal action is Install Update, followed by progress, standard trust/licence handling and restart when needed. Postpone and manual Check for Updates remain available.

Use versioned p2 content and the same feature identity across releases. A ZIP-only installation has no live update channel until a repository is configured. A new ZIP in GitHub is not an automatic update mechanism.

While the repository/product is private, use an authenticated private distribution mechanism or local development channel. Do not enable public hosting. Store required credentials securely, never in project files, repository URLs or logs. Validate the private-update distribution approach in a dedicated spike before promising it works.

Keep the signing key outside Git. Self-signing and fingerprint verification improve integrity but do not independently validate identity or eliminate initial trust prompts. Do not auto-trust all content.

Default installation is initiated by clicking the notification. Unattended patch installation is a possible later explicit opt-in, not a condition of this workflow. Updating the workbench does not silently upgrade pinned Zephyr, project source, SDK/compiler or hardware firmware. Host Eclipse updates are separate compatible plans.

## Implementation sequence and exit checks

| Slice | Deliverable | Exit check |
|---|---|---|
| A | Tools Manager: detect and reuse | Correct inventory/version/status on existing Windows setup and actionable missing-tool cases |
| B | Tools Manager: install/repair with progress | Clean approved environment reaches verified Ready; retry/cancel and interrupted downloads preserve prior tools |
| C | Vendor/SoC/board/qualifier selector | Exact valid targets across ESP32-S3, STM32, Nordic, TI and Realtek; no manual CPU qualifier typing |
| D | Native new-project wizard | File > New generates an independent project, opens source, resolves includes and builds through Project > Build |
| E | Existing-project import | Correctly detects available metadata; resolves absent/conflicting hardware without overwriting source |
| F | Native run/debug | Project-specific launch configurations work on explicitly verified boards/probes and architectures |
| G | Update notification and installation | Private N-to-N+1 install/restart/recovery test; failed/offline checks do not block development |

Develop update-channel metadata/signing and project-schema contracts alongside A-D, rather than deferring compatibility design until G.

## Current observation

The user has shown the menu installed on Eclipse Embedded 2026-12 M1 on Windows, successful environment diagnostics (Python 3.13.14, West 1.5.0, Zephyr 4.4.0), a missing-qualifier failure, and reports that building proceeds after setting the full procpu target. This is a partial desktop smoke observation, not a recorded completed firmware/hardware validation.

The first archive prototype has been followed by an authenticated private update channel. This specification describes the intended complete product; implementation status is recorded above and in the release-specific guides.
