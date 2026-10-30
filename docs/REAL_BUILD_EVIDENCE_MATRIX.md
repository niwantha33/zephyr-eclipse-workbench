<!-- SPDX-License-Identifier: Apache-2.0 -->
# Real-build evidence acceptance matrix (Phase 0)

This increment adds **actual build-artifact evidence testing** to existing
cross-vendor CI. It is not a release gate promotion and makes no hardware,
runtime or flashed-device claim. G0 remains OPEN.

## Windows — real Zephyr firmware targets

The existing managed Windows firmware matrix continues to build these
exact Zephyr targets with the pinned workspace, SDK and tool dependencies:

| Scenario | Board | Coverage |
| --- | --- | --- |
| TI | lp_mspm0g3507 | Real Cortex-M0+ build, failure/recovery |
| STM32 | nucleo_f446re | Real Cortex-M4 build |
| Nordic | nrf52840dk | Real Cortex-M4 build |
| ADC Nordic | nrf52840dk | Bundled ADC diagnostic |
| Xtensa | esp32s3_devkitc/procpu | Real Espressif Xtensa build |
| RISC-V | esp32c3_devkitm | Real Espressif RISC-V build |
| MCUboot | nrf52840dk | Signed MCUboot-format output prerequisite |

The new `scripts/evidence_firmware_probe.py` runs **after** each real
`west build` and normal recovery/rebuild checks. It reads generated
`compile_commands.json`, `.config`, `zephyr.map`, and `zephyr.elf`.
It runs actual `zew_evidence.compilation`, `link_audit`, and
`section_audit` against that same existing build. Hashes from the
Evidence Graph must agree with the actual files, and two consecutive
read-only inspections must produce identical JSON. Generated files must
remain byte-identical. The existing matrix's result JSON carries an
`evidence_engine` field for each vendor; private source paths and
compiler arguments are not exported.

Recorded `compile-command-observed` is **an instruction in the CMake
database**, not proof the compiler ran, even though this CI separately
runs a real `west build`. GNU map status may be inconsistent, unsupported
or incomplete, and results must be labelled accordingly rather than
automatically called correct. No programming or probe connection occurs.

## Linux — genuine host GNU and LLVM output formats

`scripts/evidence_native_linker_smoke.py` compiles real C objects and
a real GNU `ar` archive, then links two native host ELF executables:
`gcc` with GNU ld, and `clang -fuse-ld=lld` with LLVM lld.
The resulting linker maps and ELF files are passed through the very same
headless parsers and CLI. This detects differences between generated GNU
and LLVM map formats without mislabelling LLD output as a supported GNU
linker map. It is a **host toolchain format test**, not an embedded Zephyr
build, a device check or evidence of embedded LLVM toolchain support.
The compiler database in this host test is a controlled metadata fixture,
not a claim that CMake generated it.

Both programs are required in the Linux development CI runner; missing
toolchains fail the dedicated test visibly rather than silently skipping
LLVM coverage.

## Acceptance and remaining gaps

- CI must pass on the exact PR head on Linux and Windows. No weakening
  assertions or silently converting an unknown to a confirmed fact.
- Do not claim physical flash/debug, OTA upgrade correctness, ABI/safety
  certification, final source-to-binary causality or security applicability.
- A layout mismatch in one vendor is a **finding to investigate**, not
  a proof that the linker or Zephyr generated incorrect firmware.
- Cross-version Zephyr 4.5, LLVM embedded Zephyr links, sysbuild
  multi-image chains and physical-board measurements remain unverified.
- This is Phase-0 feasibility; independent G0 review remains required.
