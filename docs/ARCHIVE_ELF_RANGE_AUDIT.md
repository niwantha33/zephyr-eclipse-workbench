<!-- SPDX-License-Identifier: Apache-2.0 -->
# GNU archive and ELF virtual-range cross-check (Phase 0)

**Status:** pre-G0 feasibility. Read-only and non-executing. The evidence
comes from existing `zephyr/zephyr.map`, `zephyr/zephyr.elf`, and local
GNU `ar` regular archives referenced in the map. No firmware is modified.

```sh
# Whole-image / sysbuild observation (does not require source identity):
python zew.py link-audit --build ./build
python zew.py link-audit --build ./sysbuild --image app=app --image boot=mcuboot

# Optional independent report beside source-to-object observations:
python zew.py link --build ./build --source drivers/adc.c \
  --source-root ./application --range-audit

# Optional enrichment of upstream SPDX why-source:
python zew.py why --source drivers/adc.c --source-root ./application \
  --link-evidence --range-audit --build ./build
```

The auditor checks **the exact per-image SHA-256 hashes** previously
recorded by Evidence Graph for both map and ELF. GNU ld map records after
the `Linker script and memory map` marker are treated as text observations.
Only non-zero input-section rows naming `.o`, `.obj` or an archive
`library.a(member.o)` count. Discarded sections before that marker
cannot be treated as final layout entries.

An independent, bounded ELF32/ELF64 reader (both endiannesses) examines
**PT_LOAD virtual addresses**: file-backed range, zero-fill range, mixed
range, uncovered or ambiguous. A section reported as covered only means
its address interval is within an ELF load segment. There is **no byte
comparison**, no proof of which archive/member supplied those bytes, and
no resolution of LMA/p_paddr, overlays, relocation, unallocated sections
or vendor scatter-loading scripts. A covered VMA never proves a source
file contributed to the final image.

GNU regular archives can be observed by exact, full member identity,
including GNU long-name tables and BSD extended names. Each unique
member is read and SHA-256 hashed **without extraction**. Duplicate
names are ambiguous; missing archives and thin archives remain unknown.
Only archives physically inside the selected image build root are read;
external paths are not trusted. Member identity is **never guessed from
the basename of a compilation output**. A map archive reference does
not prove the archive bytes were linked.

Results contain bounded counts and hashes, but no raw section names,
local paths, source file names, symbols or private compiler arguments.
Malformed, oversized, changed, ambiguous and unsupported formats do not
produce a positive verification claim. A map and ELF may agree while both
are stale or forged; no provenance is attributed to any physical device.

The tests use binary ELF32/ELF64 fixtures of both byte orders, real GNU
ar header layouts (regular/long/BSD), and positive/negative map cases.
Independent real Zephyr build, toolchain and device acceptance remain
**outstanding**. G0 is OPEN; G1/G2/G3/G4 are not approved.

For reference, the ELF PT_LOAD mapping model is described in the
[ELF specification](https://refspecs.linuxfoundation.org/elf/elf.pdf).
