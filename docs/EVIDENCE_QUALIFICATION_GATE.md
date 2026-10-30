<!-- SPDX-License-Identifier: Apache-2.0 -->
# Real-firmware evidence qualification gate — Phase 0

**Status:** acceptance prototype, G0 OPEN. This gate verifies a minimum
read-only analysis contract against seven actual Windows Zephyr firmware
builds. It does not prove full source-to-image causality, device programming,
physical measurements, correctness of all map formats or product readiness.

## Verified initial baseline

Source: [PR #71 exact-head CI run 41090520460](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41090520460),
SHA `85f5d5b6201bdb6cca39bbf47db3d435b0142527`, whose Linux package
job and **seven-target Windows firmware matrix** completed successfully.
Sanitised measurements are preserved as
[`docs/evidence/real_firmware_baseline_20261030.json`](evidence/real_firmware_baseline_20261030.json).
They are observations from one pinned environment, **not immutable numeric
thresholds or an indication of defective firmware**.

| Scenario | Recorded CMake command | Map input rows outside ELF VMA | Map output names without allocated ELF match | Exact ELF output layouts |
| --- | --- | ---: | ---: | ---: |
| TI MSPM0G3507 | observed | 209 | 9 | 1 |
| STM32F446 | observed | 456 | 9 | 2 |
| Nordic nRF52840 | observed | 241 | 9 | 1 |
| Nordic ADC example | observed | 245 | 9 | 2 |
| ESP32-S3 Xtensa | observed | 1020 | 12 | 11 |
| ESP32-C3 RISC-V | observed | 628 | 9 | 11 |
| Nordic MCUboot image option | observed | 455 | 9 | 1 |

**Interpretation:** all seven generated valid real build artifacts and
included *some* exact ELF output-section evidence; all seven retained
unresolved map-to-ELF observations. A GNU text map can describe discarded,
nonallocated, secondary and non-loadable content. A map row outside
`PT_LOAD` **is not automatically an ELF or firmware fault**. An output
label not matching an allocated ELF section is also not proof of
incorrect linking. Underlying reader limitations must be investigated
using independently verified generated files rather than guessed fixes.

## Implemented qualification (PR #72)

```sh
python scripts/qualify_firmware_matrix.py \
  --input windows-firmware-results.json \
  --output windows-firmware-qualification.json
```

This runs after the **real Windows `west build` matrix**, and fails the
Windows CI job unless:

- exactly seven unique scenarios and the original exact board/core
  identities exist and were independently built;
- the generated SHA-256 digests for `.config`, compilation database,
  GNU map and ELF are complete and well formed;
- each expected `src/main.c` has exactly one compile-database entry
  (an observed instruction, not proof of execution from that record);
- a recognised real map has nonzero input sections and archive references,
  an ELF has `PT_LOAD` records with at least one file-backed VMA record,
  and map-range accounting is internally consistent;
- a recognised ELF section audit has nonzero exact allocated output
  section agreements and at least one observed ELF section-byte hash;
- genuine map-to-ELF address, size or identity contradictions are not
  silently accepted; unsupported/invalid parser outputs cannot be
  presented as passing coverage;
- every uncovered, ambiguous, missing or unallocated observation
  remains explicitly **partial/unknown**, never `confirmed`.

Successful qualification means **minimum-real-build-evidence-contract-passed**,
not complete coverage. It always prints `full_evidence_coverage=false`
and `source_to_final_elf_causality=not-established`. Its output includes
only allowlisted scenario/target names, bounded counts and SHA-256
provenance. It deliberately excludes source paths, compiler arguments,
raw maps and symbols. A failed or interrupted Windows job may leave a
partial results artifact, but **never** passes qualification.

## Merge safety

GitHub Actions now includes a final **Evidence qualification gate**
job with `needs: [package, windows-firmware]` and `always()`. It
fails unless **both** the installed Eclipse UI/package job and the full
Windows matrix **including qualification** succeed. The PR workflow
runs for all pull requests, including documentation changes, so path
filters cannot silently suppress this gate.

This alone does **not prevent someone merging a red or running PR**;
a repository administrator must configure a branch rule requiring the
status check. See [branch protection instructions](BRANCH_PROTECTION.md).
Our connector could not read existing branch protection (GitHub returned
403), so its current enforcement must not be assumed.

## Outstanding engineering

1. Capture bounded and redacted **real GNU map grammar fixtures** for
   the identified section naming/layout patterns, with exact toolchain
   versions and artifact fingerprints. Review and explain the missing
   versus nonallocated cases per architecture before changing parsers.
2. Compare the parser against independent `readelf`/`llvm-readelf`
   output in CI, especially LMA vs VMA and section allocation flags.
3. Repeat the seven-target gate across pinned SDK/Zephyr upgrades,
   then add actual embedded LLVM builds as separate capability.
4. Complete G0 competitor hands-on tests, licensing review and ADRs.
   Do not promote to G1 solely on successful package or matrix CI.

No hardware test has been performed by this qualification gate.
