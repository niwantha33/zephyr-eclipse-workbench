# Cross-SoC pinned dependency and module discovery repair

## Reproduction (Windows, Zephyr v4.4.0, Workbench 0.1.15)

A TI `lp_mspm0g3507/mspm0g3507` project installed the ARM compiler and
`cmsis` / `hal_ti`, configured successfully, then compilation of
`arch/arm/core/offsets/offsets.c` failed with:

```text
include/zephyr/arch/arm/asm_inline_gcc.h:24:10:
fatal error: cmsis_core.h: No such file or directory
```

This was a **Workbench dependency-planning defect**, not a user application
header or a reason to change `prj.conf`. Zephyr 4.2+ uses **`cmsis_6`**
for Cortex-M CPU support. The existing planner had selected only legacy
`cmsis`. A second defect allowed installed modules to be inactive under
West project/group filters, making Zephyr's CMake module discovery miss them.

## Shared code repair

- Resolve dependencies from the **exact selected board, SoC and CPU** as
  before, but select `cmsis_6` and legacy `cmsis` for 32-bit Arm
  targets. The legacy module remains for older vendor HAL compatibility and
  Cortex-A/R; do not install either CMSIS module for Xtensa or RISC-V targets.
- Continue selecting the vendor HAL through **SoC chip-vendor metadata**:
  TI `hal_ti`, ST `hal_stm32`, Nordic `hal_nordic`,
  Espressif `hal_espressif`, and further manifest-backed vendors.
- Inspect `build.depends` declared in each installed `zephyr/module.yml`
  and recursively prepare those modules **only at the pinned West commit**.
  A missing required manifest project is an actionable error, not a
  silently ignored dependency.
- Use `west list` **without `--all`** after installation to verify
  that selected modules are active in ordinary Zephyr module discovery.
  If necessary, append exact project activations to the existing
  `manifest.project-filter` in the local managed workspace; preserve
  exclusions for unrelated SoCs and verify visibility again.
- Keep original application files, SDK version, pinned Zephyr checkout,
  other installed module revisions, and independent target build paths.
  The existing `west build -- <CMake args>` forces CMake to rediscover
  modules on the next build; successful builds refresh CDT include/macro data.
- The legacy project context must not carry `zew.modules.none=true`
  to non-`qemu_x86/atom` targets.

## Expected dependency examples

| Target family | Compiler | Minimum planned modules |
| --- | --- | --- |
| TI MSPM0 Cortex-M0+ | `arm-zephyr-eabi` | `cmsis_6`, `cmsis`, `hal_ti` |
| STM32 Cortex-M4 | `arm-zephyr-eabi` | `cmsis_6`, `cmsis`, `hal_stm32` |
| Nordic Cortex-M | `arm-zephyr-eabi` | `cmsis_6`, `cmsis`, `hal_nordic` |
| Nordic RISC-V core | `riscv64-zephyr-elf` | `hal_nordic` (plus declared dependencies) |
| Espressif ESP32-S3 Xtensa | `xtensa-espressif_esp32s3_zephyr-elf` | `hal_espressif` (plus declared dependencies) |
| Espressif ESP32-C3 RISC-V | `riscv64-zephyr-elf` | `hal_espressif` (plus declared dependencies) |

All examples are **planning assertions** and must be followed by actual
build/device validation. Compiler support for other CPU families remains
dependent on known Zephyr metadata and reviewed SDK asset locks.

## Verification gates before release/p2 publication

1. Run `python3 -m unittest discover -s tests/tools -p 'test_board_dependencies.py'`.
2. Run `python3 scripts/test_core.py`, `mvn --batch-mode --no-transfer-progress clean verify`,
   then `python3 scripts/verify_site.py`.
3. On a clean Windows managed workspace, compile TI MSPM0, STM32 and
   Nordic Cortex-M examples; confirm `west list` exposes `cmsis_6`,
   `cmsis` and each selected HAL, `zephyr/zephyr.elf` exists, and
   Eclipse CDT headers/macros refresh.
4. Test ESP32-S3 and an actual RISC-V board separately; ensure they do
   **not** accidentally require `cmsis_6` and retain correct toolchains.
5. Test a workspace with `manifest.project-filter=-hal_.*`: only the
   currently selected HAL should be activated; unrelated HALs stay excluded.
6. Verify repeat builds do not refetch pinned modules and a mismatched
   existing module is preserved with a clear failure.
7. Validate Eclipse update-site installation and rollback on Windows.
   A GitHub source commit or merged PR alone does not update Eclipse.

**Status:** Source repair branch only. Native Windows compilation, board
hardware flashing and update-site deployment are **not yet verified**.
