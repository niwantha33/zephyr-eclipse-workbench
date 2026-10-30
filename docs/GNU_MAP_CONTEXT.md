<!-- SPDX-License-Identifier: Apache-2.0 -->
# GNU linker-map parent/output context — Phase 0

**Status: bounded read-only diagnostic, G0 OPEN.** The existing
`zew link-audit` and `zew section-audit` statuses are deliberately
unchanged. This tool does not diagnose defective firmware, establish
source-to-binary inclusion or claim device flashing.

```sh
python zew.py map-context --build ./build
python zew.py map-context --build ./sysbuild --image app=app --image boot=mcuboot
```

## Problem measured in actual builds

PR #71's completed seven-target Windows Zephyr matrix recorded 209–1020
GNU map input rows outside ELF `PT_LOAD` VMA ranges per target and 9–12
map output section names without an exact allocated ELF match. Those are
**unresolved parser/evidence observations, not demonstrated build faults**.
See `docs/evidence/real_firmware_baseline_20261030.json`.

GNU ld can discard input sections, and section attributes determine
whether an output section occupies the memory image. Section headers
with `SHF_ALLOC` are not equivalent to nonallocated ELF metadata;
`SHT_NOBITS` may occupy memory without occupying ELF file bytes.

## What the new analysis actually checks

The analyzer verifies the **same-image** map and ELF SHA-256 against
the Evidence Graph; it never executes linkers or opens archive member
paths. It recognises only unindented, explicit GNU output headers after
`Linker script and memory map`, and well-formed indented input rows
with object/archive-member tokens. One-line and a bounded two-line
input format are supported. Unsupported formats stay unknown.

Output headers are matched **exactly by section name, VMA and byte
length** to ELF section headers. An opt-in ELF metadata view includes
nonallocated sections without hashing them or treating them as loaded.

The new report has counters for:

- allocated and nonallocated output metadata matches;
- output names absent from ELF, duplicate labels or layout mismatch;
- input rows fully within an allocated or nonallocated output range;
- input rows outside their declared parent bounds, without a recognised
  parent, or attached to unresolved output metadata.

These are **limited textual/metadata observations**, not byte identity
proof. The report intentionally contains no source file paths, raw
section labels, map text, addresses, symbols or compiler arguments.

Every real Windows CI build now includes the new diagnostic counters
beside its existing `link_range` and `elf_sections` reports. This
increment **does not rewrite prior range statuses or lower their
qualification thresholds**. The seven real vendor outcomes must be
reviewed before deciding which map grammar cases require more parser
coverage, and no false positive may be converted to a confirmed
observation simply to reduce an unknown count.

Linux CI also tests the parser against real GNU ld and LLVM lld host
links. An independent `readelf --wide --section-headers` comparison
checks the ELF `.text` address and size against the tool's own section
header reader. These are host toolchain checks, **not embedded LLVM
Zephyr** validation.

## Follow-up acceptance work

1. Review each vendor's redacted classification counters from the
   exact-head Windows CI artifact.
2. For unresolved grammar, add minimal, synthetic or appropriately
   sanitised **real toolchain-derived** map excerpts as fixtures; do
   not publish private source names, full paths or third-party code.
3. Review ELF section flags and PT_LOAD VMA against `readelf` for
   each embedded architecture. Keep LMA/physical flash interpretation
   separate.
4. Independently review G0 competitor and licensing evidence; no G1,
   product readiness or hardware performance claim is permitted.

Useful upstream semantics:
- [GNU ld output-section discarding](https://sourceware.org/binutils/docs/ld/Output-Section-Discarding.html)
- [ELF format and section flags](https://refspecs.linuxfoundation.org/elf/elf.pdf)
