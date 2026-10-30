<!-- SPDX-License-Identifier: Apache-2.0 -->
# Exact compiler command evidence — Phase-0 feasibility

This is a read-only companion to upstream SPDX `zew why --source`.
It observes **records in** `compile_commands.json`. It does not execute
commands or establish that any file was compiled, linked or flashed.

```sh
# Existing generated CMake compile database, explicit source ownership:
python zew.py compile --build /path/to/build --source drivers/adc.c \
  --source-root /path/to/application

# Enrich a direct SPDX Build-input explanation with independent command evidence:
python zew.py why --source drivers/adc.c --source-root /path/to/application \
  --build /path/to/build

# Multi-image builds; the source root is shared, but observations stay per image:
python zew.py compile --build /path/to/sysbuild --image app=app \
  --image boot=mcuboot --source drivers/adc.c --source-root /path/to/application
```

The caller supplies a real local source root. The source must exist as a
regular file inside that root. For each image, the tool snapshots bounded
compiler-database bytes, confirms their SHA-256 against the **same**
Evidence Graph artifact node, and counts entries referencing the exact
resolved local source file. Relative `file` entries resolve against their
compiler-database `directory` field; foreign-host or unresolved directory
paths remain unknown. The tool never exports command strings, arguments,
host paths, compiler flags or source filenames.

**Evidence levels:** a matching database entry is a *recorded compilation
instruction*, not evidence of execution. No matching entry is not proof
that a source was excluded. A source can appear multiple times for different
variants. It is not safe to infer object-file existence or final ELF inclusion
from this database. Generated source files, path aliases, source freshness,
linker-map contribution, and actual board flashing require independent
observations. This is not G1 completion; G0 remains OPEN.

The new regressions cover duplicate JSON keys, malformed entries,
confidential command-line values, changed database hashes, missing source
or build evidence, host-unresolvable paths, Unicode/space filenames and
multi-image isolation.
