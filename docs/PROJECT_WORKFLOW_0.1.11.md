# New and existing Zephyr projects — 0.1.11 development slice

This slice implements native Eclipse **File > New > Other > Zephyr > Zephyr Project**
and **File > Import > Zephyr > Existing Zephyr Application**, using the
verified Windows x64 first-build environment from **Zephyr > Set Up Zephyr**.

## User walkthrough (no terminal or PATH editing)

1. In Eclipse, complete the existing managed Set Up Zephyr workflow and verify
   its Hello World build. Keep that installation and its projects.
2. Choose **File > New > Other > Zephyr > Zephyr Project**.
3. Select **Create new application** or **Import existing application**.
4. Choose the existing managed installer directory (the one containing
   `.zew-managed`, `.zew-ready` and `environment.properties`).
5. Enter an Eclipse project name and select the application folder. New
   projects default to `<installation>/projects/<project-name>`.
6. Finish. A new standalone Zephyr application is created and opened, or an
   existing `CMakeLists.txt` application is imported without overwriting its
   code. The project is registered with Eclipse's native builder.
7. In **Project Explorer**, select the application and choose
   **Project > Build Project** or **Project > Clean**. Auto-build responds to
   project source, configuration, board overlay and portable profile changes.

A generated app contains `CMakeLists.txt`, `prj.conf`, `src/main.c`,
`.gitignore`, `README.md`, and `.zew/project.properties`. The last file
stores only schema, Zephyr version, board target and the no-modules profile. No
machine-specific SDK, Eclipse or Python paths are added to the project.

Each application's default build directory is computed from the exact board
target under `.zew-build/`. The original HelloZephyr starter reuses its
existing `.zew-build/first` cache if it has been configured. The Eclipse
workspace holds each project's selected managed setup directory independently.

## Safety and capability limits

- New project creation refuses nonempty folders. It never overwrites an
  existing `main.c`, Kconfig, CMake file or other user content.
- Import requires an existing `CMakeLists.txt`; it adds a portable `.zew`
  marker only if one was absent. Incompatible existing metadata is rejected
  without rewriting it. Imported projects are otherwise left intact.
- The first managed installer has **only the x86 toolchain**, so the wizard
  deliberately selects `qemu_x86/atom`. An ESP32-S3 / Arm / RISC-V selection
  would be misleading until a matching SDK/compiler, Zephyr modules and build
  profile have been installed and verified.
- This is compilation integration; no claim of emulator execution, board flash,
  serial-console access, live debugger, CDT indexer or physical-device tests.
- Existing Zephyr installations can still be detected with **Tools Manager**,
  but this new wizard's managed-environment binding requires a completed
  Set Up Zephyr profile in this slice.

## Validation

The Java core test suite covers portable metadata, new CMake and source
generation, path/name safety, imported-source preservation, rejection of
incompatible profiles and completed setup recognition. Site verification checks
the packaged wizard and Eclipse New/Import extension registrations. The private
CI runs those checks and Tycho packaging on pull requests, but is **not** a
Windows/Eclipse GUI or real-board validation.

## Next work

1. Installer profiles with reviewed per-architecture SDK toolchains and
   required Zephyr modules, without touching unrelated PATH entries.
2. Board discovery backed by actual Zephyr metadata: vendor -> SoC -> board ->
   revision -> CPU/core qualifier, with exact target and capability gates.
3. Portable active profiles and source/build-path separation for other boards.
4. CDT indexing of generated config, Zephyr headers and compile commands.
5. Kconfig/Devicetree editing, image and flash reports.
6. Runner/probe detection, serial and native Eclipse run/debug launches with
   physical-board evidence and safe reconnect/cancellation.
7. Full Windows desktop smoke tests and distribution/dependency licence audits.

This release stays a private, reviewable preview. No vendor firmware, existing
setup data, Eclipse update channel, releases or user's files are modified.
