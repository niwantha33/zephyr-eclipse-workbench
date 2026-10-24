# Project workflow and editor integration — 0.1.12 preview

Use **Zephyr > Check for Workbench Updates**, install the offered release, and restart Eclipse. The existing private channel and secure-storage credentials are reused. End users do not run Python or publication scripts.

## Implemented

- File > New > Other > Zephyr > Zephyr Project, and File > Import > Zephyr > Existing Zephyr Application. Import defaults to existing mode.
- Hardware discovery uses the selected Zephyr checkout's own `list_boards` and `list_hardware` resolvers, including CPU clusters, variants and revisions. Chip vendor and board manufacturer are distinct. Filter vendor and SoC, or search board/core/target text. Select one resolved target; no hand-written CPU qualifier is required.
- New applications are independent of the upstream source. Nonempty destinations are rejected. Import adds metadata without replacing application CMake, Kconfig, overlays or source. Existing Eclipse metadata is preserved by directing those projects to Eclipse's Existing Projects importer.
- Portable `.zew/project.json` records schema 1, exact board and profile `first`; machine paths remain in Eclipse's project properties. This small schema currently has a canonical field order. The prior `.zew/project.properties` format remains supported for existing 0.1.11 applications and the starter.
- Native Build/Clean uses each project's environment and target. Output directories are isolated by a SHA-256 target key, preserving the starter's configured `first` output. Legacy no-module first-build applications retain that setting. New qemu_x86/atom applications also retain it; other targets use upstream module discovery.
- Compilation databases feed CDT application-source settings, generated configuration macros, includes and navigation. Refresh follows successful builds. Marker-only changes do not trigger endless rebuilds. Missing CDT leaves the firmware builder available.
- Compiler errors and warnings map to owned Problems markers. Other diagnostics remain in the build console.
- Project-scoped Run in Emulator, Flash Application and Start Debug Server commands use West, not a shell. Build receipt checks reject stale or unbuilt targets. Flash requests confirmation at use. Cancel long-running processes in Eclipse's Progress view. Update installation waits for runner and editor jobs.

## Use

1. Keep the completed Set Up Zephyr environment.
2. Open New or Import, choose its setup folder and application location.
3. Load hardware; filter and select the exact target.
4. Finish, select the project, and choose Project > Build Project.
5. After the build, CDT refreshes automatically; explicit Refresh C/C++ Editor Settings is also available.
6. For a supported QEMU target with QEMU installed, choose Run in Emulator. For a physical target with its compiler, modules, runner and probe installed, use Flash or Start Debug Server.

## Boundaries still requiring implementation or validation

The beginner installer still installs only the x86 compiler, and its minimal SDK does not guarantee QEMU is installed. Hardware discovery is not evidence that another target can compile, flash or debug in that environment. ESP32-S3, Arm and RISC-V need their matching compilers and modules; automatic installation of these additional profiles remains pending. No proprietary firmware blobs are fetched by the selector.

Debug Server starts the upstream server; native Eclipse breakpoints, variables and stepping integration is pending. Serial console/reconnect, configurable runner/probe selection, sysbuild/domains, external-root selection in the UI, Kconfig/Devicetree editors and memory reports are also pending. The resolver helper supports explicit external roots but the wizard does not yet expose them.

Windows GUI, physical-device execution and complete dependency/distribution audits remain required before a stable release. This release is a private development preview.

## Recorded checks for 0.1.12.202610241658

48 Python tests; 32 Java core, 27 update, 11 bootstrap, 15 existing-project, 11 editor-protocol and 15 new-profile checks passed (159 checks total). Tycho Java compilation and p2 archive verification passed. A fresh Eclipse 4.41 installation accepted 0.1.11.202610241206 and upgraded to 0.1.12.202610241658; the old feature root was replaced.

A resolver fixture uses unmodified upstream Zephyr 4.4 board/SoC resolver scripts and the real Espressif ESP32-S3 and Intel QEMU definitions. It resolves separate `esp32s3_devkitc/esp32s3/procpu` and `esp32s3_devkitc/esp32s3/appcpu` targets, plus the QEMU variants. This verifies discovery, not compilation or execution for those targets.

The packaged product also passed 30 assertions through actual CDT APIs across initial import and a separate Eclipse restart without reimporting settings. The fixture checks C/C++ macros, C++17 and exception/RTTI flags, forced headers, indexed navigation, AST bindings, persisted settings and preservation of the West builder. A test-only bundle is restored before each process because p2 can rewrite its bootstrap configuration on shutdown. These are headless CDT checks, not Windows desktop interaction tests.
