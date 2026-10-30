<!-- SPDX-License-Identifier: Apache-2.0 -->
# ELF output-section byte observations (Phase-0 feasibility)

This is a **read-only** consistency check between existing GNU ld text-map
output-section headers and existing ELF section headers. It does not run a
compiler, linker, debugger or flash tool. G0 remains OPEN.

```sh
python zew.py section-audit --build ./build
python zew.py section-audit --build ./sysbuild --image app=app --image boot=mcuboot
python zew.py link-audit --build ./build --section-evidence
```

## Evidence observed

- The selected image's `zephyr/zephyr.map` and `zephyr/zephyr.elf`
  are both checked against the SHA-256 identifiers already recorded in
  the **same** Evidence Graph.
- The GNU ld reader only accepts a bounded, explicit *single-line*
  output-section declaration such as `.text 0x4000 0x20`. The section
  must be after the map's `Linker script and memory map` boundary.
  Input-section references to objects and archives and discarded sections
  cannot masquerade as output-section metadata.
- The independent ELF32/ELF64 little/big endian reader validates the
  section-header table and `.shstrtab` boundaries and decodes exact
  allocated section identities. It identifies duplicate, missing and
  ambiguous section names rather than guessing.
- Exact name, virtual address and byte size agreement is counted. For
  matching file-backed ELF sections of bounded size, SHA-256 hashes the
  **actual section bytes present in the ELF**. NOBITS/zero-fill has no
  bytes to hash. PT_LOAD coverage is observed separately.
- No memory-map address, source filename, section symbol, section name,
  object path, raw command or host path is exported. Output comprises
  counts, bounded ELF-byte hashes and source artifact digests.

## What this does not prove

**A map contains no section bytes**, so it cannot verify the origin of
ELF bytes. An exact name/address/size match is metadata agreement, and a
section SHA-256 is a fingerprint of the current ELF file; it is not a
comparison with object or archive bytes. Nor does it prove the build is
fresh, final, signed, executed or flashed.

ELF section names/headers may be stripped; extended section numbering,
non-GNU maps, multi-line map section records and vendor scatter-loading
remain UNKNOWN. Virtual addresses are not load addresses and must not
be treated as proof of device flash layout. A mismatch can identify
contradictory evidence but cannot establish which artifact is correct.

Validation uses synthetic ELF32/ELF64 binary section headers in both
endiannesses, exact bytes, NOBITS, duplicate labels, corrupted headers,
stale graph captures, source confidentiality and multi-image isolation.
Real Zephyr firmware diversity, independent elfutils/readelf comparisons
and physical target testing remain outstanding.
