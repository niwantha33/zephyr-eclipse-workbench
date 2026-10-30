<!-- SPDX-License-Identifier: Apache-2.0 -->
# Source-to-object-to-GNU-linker-map observations (Phase 0)

**Status:** feasibility implementation, G0 OPEN. Not a linker validator,
post-link ELF parser, attestation, source freshness check or hardware test.

The upstream inputs remain ordinary generated files: CMake's
`compile_commands.json` with an **explicit** `output` field, an existing
output object file, and a GNU ld style `zephyr/zephyr.map`. The tool never
invokes compilers, linkers, GDB, Zephyr or target equipment.

```sh
python zew.py link --build ./build --source drivers/adc.c --source-root ./application

# Existing SPDX why-source explanation plus explicit compiler and map observations:
python zew.py why --source drivers/adc.c --source-root ./application \
  --link-evidence --build ./build

# For explicit sysbuild image roots:
python zew.py link --build ./sysbuild --image app=app \
  --image boot=mcuboot --source drivers/adc.c --source-root ./application
```

An exact local source file is matched against compile database `file`
records. An exact, explicit `output` field must identify a **single**
object under the selected build image. If no output is declared, or two
distinct output files are declared, the result is **unknown**, and the
tool never invents the output filename or chooses arbitrarily.

For a single output, the tool observes the object's current bytes and
hashes them, then compares `compile_commands.json` and `zephyr/zephyr.map`
against the existing Evidence Graph's exact per-image artifact hashes.
Only known GNU ld text-map records are counted: `LOAD` directives and
nonzero input-section sizes **after** the `Linker script and memory map`
boundary. Discarded input sections, GNU archive members such as
`libfoo.a(member.o)`, zero-size records and other map formats are **not**
treated as direct object contributions.

The reported evidence levels are distinct:
- **Compilation instruction recorded:** does not establish the compiler ran.
- **Object bytes currently exist:** does not establish the object came from
  that instruction or a fresh build.
- **Map LOAD recorded:** does not establish any allocated output section.
- **Nonzero input sections recorded in GNU map:** indicates textual linker
  layout evidence for that exact object only. It is not an independent ELF
  inclusion proof; the map may be stale, nonfinal or forged.
- **Flashed and running:** not observed at all.

No source paths, compiler arguments, object paths, sensitive flags, linker
symbols or map content are exported. A missing or unsupported document
means missing evidence, **never** proof of exclusion. Actual ELF verification,
archives' extracted members, GC/discarded analysis, LLD/vendor map formats,
live target validation and a complete causal graph need separate
engineering and real-firmware fixtures before G1 review.

Upstream formats:
- [CMake CMAKE_EXPORT_COMPILE_COMMANDS](https://cmake.org/cmake/help/latest/variable/CMAKE_EXPORT_COMPILE_COMMANDS.html)
- [GNU ld --print-map](https://www.sourceware.org/binutils/docs/ld/Options.html)
