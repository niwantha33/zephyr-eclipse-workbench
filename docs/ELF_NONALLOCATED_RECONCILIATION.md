<!-- SPDX-License-Identifier: Apache-2.0 -->
# Reconcile nonallocated ELF output metadata — Phase-0 feasibility

**Status:** read-only evidence increment, pre-G0. No firmware build,
source change, flash or hardware access is performed by this analyzer.

## Verified seven-vendor finding

The passing PR #76 [exact-head CI run](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41097914018)
compiled real Windows Zephyr firmware for TI MSPM0, STM32, nRF52840,
Nordic ADC, ESP32-S3 Xtensa, ESP32-C3 RISC-V and MCUboot image mode.
Its independent GNU map-context observations found that **all 66 map
output-section names previously marked as "missing or unallocated"
have an exact name, address and byte length in nonallocated ELF
section-header metadata**: 9, 9, 9, 9, 12, 9 and 9 respectively.

These are actual pinned CI observations, **not a general rule for all
future Zephyr builds**. The evidence is retained at
[`docs/evidence/nonallocated_elf_observations_20261030.json`](evidence/nonallocated_elf_observations_20261030.json),
including source run, commit and map/ELF SHA-256.

## Implementation

```sh
python zew.py section-audit --build ./build
python zew.py link-audit --build ./build --section-evidence
```

The section auditor now reads allocated and nonallocated ELF section
**metadata** from the already bounded, SHA-256-checked ELF file. A
nonallocated map output is reconciled only when it has **one exact ELF
section name, virtual address and byte length**, with `SHF_ALLOC`
absent. Duplicate names, missing ELF names, differing addresses/sizes
and unsupported formats remain unknown or mismatched.

Backward compatibility: `comparison["missing-or-unallocated"]` remains
the number of map output sections **without a matching allocated ELF
section**. It includes exact nonallocated matches. The new additive
counters separate those meanings:

- `comparison["nonallocated-exact-metadata"]`: subset of the legacy
  count independently matched to ELF metadata only;
- `comparison["unexplained-map-outputs"]`: the legacy count minus
  exact nonallocated matches.

A section report has status `map-elf-output-layout-observed` when
every supported output-section record is accounted for and other
layout/coverage conflicts are absent. That is **metadata agreement**,
not proof the section contributes any file bytes to the ELF, is
allocated at runtime, exists in device flash, or came from a particular
source file.

The seven-vendor qualification gate also checks that the disaggregated
counts sum back to the old field, rejects any invented nonallocated
match, and reports both reconciled and unexplained counts alongside the
unchanged `PT_LOAD` input-range unknowns.

Important: all seven builds still have hundreds of GNU map **input**
rows outside ELF `PT_LOAD` VMAs in the PR #76 measurements. These are
not resolved by reconciling output-section metadata. The entire
firmware source-to-binary proof remains **NOT ESTABLISHED**.

## Acceptance boundaries

Tests cover ELF32/64, both endiannesses, SHF_ALLOC toggling,
nonallocated `SHT_PROGBITS`, address and size mismatches, no byte
hashing or PT_LOAD classification of nonallocated sections, stale
build hashes, multi-image isolation, deterministic qualification and
the seven pinned real-build observations. The existing native GNU/LLVM
smoke, Eclipse smoke and full seven-vendor Windows qualification
remain required.

**G0 is OPEN.** There is no confirmed production, physical board,
source inclusion, safety or flashed firmware assertion.
