# Cross-vendor dependency repair — 0.1.20

Selecting a target must prepare its CPU compiler and pinned SoC modules before CMake configuration. Missing TI pinctrl headers and missing ESP32-S3 compilers exposed shared weaknesses in target resolution and module activation.

## Changes

- Read board qualifiers, SoC declarations, architecture and exact target defaults from the selected Zephyr checkout. Mixed-core chips and standalone SoC declarations no longer rely on a vendor-wide CPU guess.
- Select SDK compiler assets using architecture and toolchain metadata. Resolve Xtensa toolchain names explicitly; distinguish ARM64 and ARCv3 targets.
- Resolve HAL modules from the selected SoC hierarchy and pinned manifest, including nested vendor directories, CMSIS and explicit module references.
- Correct West configuration argument parsing: negative project filters are passed after the option separator, so HAL activation actually changes configuration rather than printing help.
- Download modules into sibling staging directories, verify the pinned commit and publish only completed checkouts. Preserve existing conflicting checkouts.
- Wait cancellably when another project is preparing the shared environment.
- Check generated Ninja commands, included rule files and referenced variables for missing build-host tools. Prepare reviewed imgtool/esptool providers and reconfigure once.
- Retain 0.1.19's isolated MCUboot runtime and migration of existing Python path mappings.
- Add selected DTC and SDK host-tool directories to the build environment.

These changes use the native Eclipse project Properties/build workflow. No device-specific data panel or additional top-level device menu is introduced.

## Validation and boundaries

The clean Tycho reactor passed, including plug-in compilation and p2 packaging. The regression run passed 85 Python tests (one existing environment-dependent skip), plus Java core, update, setup, project, editor and build-host checks. A real West workspace test verifies module activation and preserves unrelated exclusions.

Sixteen target metadata plans were checked against Zephyr v4.4.0: TI, ST, Nordic, Espressif, NXP, Renesas, Realtek, x86, AArch64 and ARC variants, including ESP32-S3's two cores and Nordic ARM/RISC-V cores. Invalid qualifiers and ambiguous compiler declarations are rejected.

These are metadata and integration checks, not firmware compilation or hardware execution for every vendor. Native Windows builds and physical flashing/debugging were not executed for this release. Unrecognized build-host dependencies fail with a diagnostic instead of installing arbitrary Python packages.
