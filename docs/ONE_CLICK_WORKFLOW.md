# Zephyr Eclipse Workbench: project-first, managed workflow

## Design rule

New users should never need to manually configure a global Python interpreter,
CMake, Ninja, West workspace, SDK path, board qualifier, or hashed build directory
to create and build a project. Use the pinned, isolated managed environment and
Zephyr's upstream commands. Hardware programming is an explicit, confirmed action.

## First-time user

1. **Zephyr > Start Here — Create Zephyr Project**.
2. If no verified managed environment is available, the existing Setup Wizard
   installs verified portable tooling and builds a Hello World for QEMU, after
   review of downloads and licenses. It does not flash physical hardware.
3. With an environment present, Start Here opens the project wizard. It
   automatically loads supported board/SoC/CPU metadata. The user chooses an
   exact target and where to create or import their application.
4. When the project wizard finishes, it automatically schedules its first build.
   The existing Eclipse Progress and Zephyr Console show download, configure,
   compile and verification output; cancellation remains available.

## Daily use

- **Zephyr > Build Application**: build the selected project, preparing its
  target-specific dependencies as needed. No global preference paths.
- **Zephyr > Pristine Rebuild**: configure from scratch for the selected
  project's exact board target, preserving the source and other board builds.
- **Zephyr > Build and Show Firmware**: build and verify the firmware; list
  real generated ELF, BIN, HEX, signed byproducts if required, and open the
  exact firmware directory (never guess a board-hash directory).
- **Zephyr > Flash Application**: show the selected board for user confirmation;
  prepare/build automatically, then flash using the selected Zephyr build's
  generated runner configuration and `west flash --no-rebuild`.
- **Zephyr > Debug Application**: prepare/build automatically, then start a
  real Eclipse CDT DSF-GDB session using the saved Debug Configuration. The initial managed native
  debug backend remains **OpenOCD only**; unsupported debug runners are rejected,
  not treated as universal.

## Build directory

Each project resolves its own `.zew-build/<board-id>`. Legacy managed
projects may use `.zew-build/first`. The current exact location is printed
in the Zephyr Console and made available through Build and Show Firmware.

The build is still standard `west build --build-dir ...`. West reads the
generated `zephyr/runners.yaml` to find supported runners for flash/debug.

### Seeing generated files in Eclipse Project Explorer

The **Build Outputs** entry under each managed Zephyr application is a
visible, ordinary **Eclipse linked folder**, pointing to the existing
`<application>/.zew-build/` directory using Eclipse's portable
`PROJECT_LOC/.zew-build` path variable. This is not a copy, and neither
the link nor its refresh creates or verifies firmware. The link is created
for imported/open projects even when no build exists yet (using Eclipse's
`ALLOW_MISSING_LOCAL` feature), so empty contents mean **no generated
files are currently present**, not a successful build.

After a Zephyr configure/build, expand:
`<application>/Build Outputs/<board-hash-and-profile>/zephyr/` to inspect
`runners.yaml`, `zephyr.dts`, and `.config` when actually produced.
The real build path remains `<application>/.zew-build/<board-hash-and-profile>/`;
the selected profile, not the folder name in Project Explorer, determines
where West writes. Other generated YAML (e.g. `build_info.yml` or sysbuild
`domains.yaml`) is conditional on the actual build.

The previous `.zew-build` entry may remain invisible under Eclipse's
dot-resource filters, but **Build Outputs** has no leading dot and is neither
Derived nor Hidden. If it still does not appear, check you have installed a
Workbench build containing this fix, press **F5** on the project, and check
Project Explorer → **Filters and Customization...** for linked-resource
filters. An Eclipse team provider may disallow linked folders; the error
log records this without turning a successful firmware build into a failure.
The Workbench never overrides a user's existing resource named
`Build Outputs` and never changes the existing build directory.
To verify that a build has really run, inspect the **Zephyr Console**
`independent output:` path and the actual firmware build result.

This link points to the *actual generated files*. Deleting its children
would delete the underlying build files, so inspect them and edit
`prj.conf` or `app.overlay` instead of generated `runners.yaml`,
`zephyr.dts`, or `.config`. Open **Zephyr YAML Structure (Read-only)**
via **Window → Show View → Other...** and select a saved YAML resource.
Parsing YAML never proves a valid hardware configuration or firmware build.

This Workbench does not copy source into a vendor-owned project or replace the
Zephyr build system.

## Safety and known limits

- No silent automatic physical flash, erase or debugger attach.
- The selected board is re-checked immediately after the build, before flash.
- The workbench verifies artifact existence, nonempty outputs and ELF header,
  but this is not a cryptographic signing or physical flash read-back check.
- Automatic software dependencies do **not** mean automatic probe discovery or
  support for every vendor-specific debugger. A compatible connected probe and
  its open-source runner dependencies are still required.
- No claim of physical debugging/flashing without real on-board validation.
- Unsupported source or incompatible toolchains should give a precise error,
  without silently rewriting the board profile or damaging firmware.

## CI acceptance

The native Eclipse project plugin and p2 site must package with all registered
actions. The Windows managed firmware matrix is required before release.
Hardware verification (probe inventory, wrong board, disconnect/reconnect,
attach/step/breakpoint, flash read-back) remains a separate release gate.
