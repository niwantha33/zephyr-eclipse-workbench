# Cross-vendor build capability preflight (Workbench 0.1.18)

## Design

The original failure was observed for `esp32s3_devkitc/esp32s3/appcpu`:
C compilation and ELF linking succeeded but Ninja attempted to execute
`IMGTOOL-NOTFOUND` when creating `zephyr.signed.bin`.

The correct requirement is **not** "ESP32 implies imgtool", nor is
`IMGTOOL-NOTFOUND` in `CMakeCache.txt` necessarily a problem: Zephyr
discovers optional host executables at configure time. The project may
never execute an optional tool. The authoritative requirement is the
set of commands produced by the **actual selected board's** CMake
configuration.

## Native Eclipse pipeline

1. Resolve the exact board/SoC architecture, pinned compiler, HAL,
   CPU support and explicit MCUboot configuration through the existing
   dependency helper.
2. Run the same `west build` configuration as the normal Eclipse Build
   with `--cmake-only` (Ninja backend).
3. Parse generated `build.ninja` for unresolved `XXX-NOTFOUND`
   executables, not the list of *optional* failed CMake discovery
   probes.
4. Resolve `IMGTOOL` through the manifest-pinned `mcuboot` project
   and its separately pinned Windows Python dependencies. The same
   provider is used for **every board**, independent of vendor.
   Download only locked assets and preserve old installations.
5. On newly activated MCUboot/host dependencies, regenerate **only the
   selected independent build directory** with `--pristine=always
   --cmake-only`; this discards outdated `IMGTOOL-NOTFOUND`
   CMakeCache entries. It does not delete source files, Zephyr,
   unrelated build directories or other SDK toolchains.
6. Inspect generated Ninja commands again. Fail early and explicitly
   if an unsupported required host executable remains unresolved.
7. Start native `west build` only after successful preflight. A
   successful compile still requires firmware outputs to exist.

The host-tool provider set is intentionally **closed** and reviewed:
`IMGTOOL -> pinned mcuboot`. Do not attempt an arbitrary unpinned
`pip install`, PATH search outside the managed environment, or
`west update --all` for unrecognized generated tools. Extend this
registry with verified providers and tests when other tool requirements
are discovered in real board builds.

## Validation

- Run `scripts/test_core.py` including generated-command scanning
  regression tests.
- Validate Eclipse/Tycho p2 feature and bundle metadata.
- Rebuild previously failing Windows `esp32s3_devkitc/esp32s3/appcpu`
  without clearing application files or installing anything manually.
  Required artifacts: `zephyr.elf`, `zephyr.bin`,
  `zephyr.signed.bin`.
- Rebuild `lp_mspm0g3507/mspm0g3507`, STM32 Cortex-M and a
  Nordic Cortex-M using the same configure-only preflight.
  Confirm optional `IMGTOOL-NOTFOUND` in CMakeCache is ignored if
  generated commands do not use the tool.
- Enable MCUboot for an independent vendor, and verify the same
  locked module and signing requirements are applied.
- Confirm Eclipse source indexing, regression build reuse and actual
  device boot/flash separately.

CI/package passing is not equivalent to native Windows multi-board
acceptance. Missing vendor-SDK features, flashing tools, hardware
configurations, and security key management remain distinct release
gates.
