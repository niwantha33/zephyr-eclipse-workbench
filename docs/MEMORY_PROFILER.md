<!-- SPDX-License-Identifier: Apache-2.0 -->
# Memory Profiler — PR 1, Steps 1–3

**Status:** read-only prototype; explicit JSON import only. Automatic post-build
refresh is **not implemented** (Step 4). The native UI has not yet passed an
installed Eclipse/SWT smoke test.

## Generate evidence

From the repository checkout:

```sh
python scripts/memory_profiler.py --map build/zephyr/zephyr.map --output memory-report.json
```

Open **Window > Show View > Other > Zephyr Visual Memory & MPU Profiler**,
choose **Open memory report JSON…**, then select the saved file. Reselect or
press **Reload selected report** after regenerating the file.

The scrollable SWT Canvas displays each *linker MEMORY region* on its **own**
relative scale. The Flash/RAM category is a **name-based hint**: no physical
silicon-memory identity or permissions are confirmed from a linker map.
The VMA track is GNU output-section address placement; the LMA track draws
**only explicit** load addresses. Neither represents measured RAM occupancy,
image flash usage, section bytes verified against ELF, or firmware currently
flashed to the device. Click or use Up/Down to inspect half-open byte ranges.

## MPU evidence contract

The Step 1 map parser correctly reports
`mpu.status = "not-determinable-from-linker-map"`,
`mpu.confidence = "unknown"` and `mpu.regions = []`. No MPU bands are
drawn for a normal Step 1 report. GNU linker attributes such as `xrw`
**are not** hardware MPU configuration.

The diagram supports an **optional, separately captured** MPU snapshot
inside the same JSON document only when the following fields are present:

```json
{
  "mpu": {
    "status": "recorded-debugger-snapshot",
    "evidence": {
      "method": "debugger-register-readback",
      "context": "halted-target",
      "map_sha256": "<same SHA-256 as provenance.sha256>",
      "capture_sha256": "<64 hexadecimal digits for the capture>",
      "elf_sha256": "<64 hexadecimal digits for the associated ELF>"
    },
    "regions": [
      {
        "name": "captured-region-0",
        "start": 536870912,
        "end_exclusive": 536870944,
        "permissions": "rw"
      }
    ]
  }
}
```

This is an *import format*, not a capture implementation or an instruction to
manually invent MPU data. The project does **not** currently produce this
snapshot automatically. An imported hash and labelled method do not
authenticate target identity, verify register decoding or prove the captured
MPU was enabled at firmware runtime. The UI labels these bands as imported
and **not independently authenticated**. Missing, contradictory or
map-mismatched MPU metadata is rejected instead of silently drawing a band.

When two imported MPU intervals overlap each other **within a displayed
linker range**, the shared span is coloured red; a single imported MPU
interval is purple. Half-open adjacent boundaries do not overlap.
Overlapping MPU regions are not necessarily a fault: priority and architecture
rules determine effective permissions. The drawing does not interpret such
rules, execute GDB, or assert effective runtime access rights.

## Testing

```sh
python -m unittest discover -s tests/tools -p 'test_memory_profiler*.py' -v
# The existing Java suite includes MemoryProfilerJsonTests and MemoryProfilerLayoutTests:
python scripts/test_core.py
```

The headless model tests cover VMA versus LMA, exact integer scaling, intervals,
gated snapshot acceptance, and false MPU overlays. SWT compilation,
installed-view activation, screenshots, multi-theme accessibility, and physical
MPU evidence are **not verified** by those tests.
