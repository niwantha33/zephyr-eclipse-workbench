<!-- SPDX-License-Identifier: Apache-2.0 -->
# Memory Profiler — PR 1, Steps 1–4

**Status:** read-only preview. The normal Eclipse/CDT West build automatically
captures the selected build's linker map **only after successful West execution,
required firmware-output verification, and configuration verification**.
The native UI still needs an installed Eclipse/SWT smoke test; generated reports
are not proof of hardware MPU state.

## Generate evidence

From the repository checkout:

```sh
python scripts/memory_profiler.py --map build/zephyr/zephyr.map --output memory-report.json
```

Open **Window > Show View > Other > Zephyr Visual Memory & MPU Profiler**.
Select an imported Zephyr project in Project Explorer and click
**Follow selected project builds**. The active board/profile's generated report
is stored at `<active-build>/.zew-memory-profile.json`.

Build using the normal **Zephyr > Build Application** or Eclipse/CDT Build action.
The common `ZephyrProjectBuilder` verifies the build, invokes the exact
bundled Python parser with the project's configured Python executable, checks
the generated JSON and source map SHA-256, then atomically publishes the report
and refreshes any **already-open** matching project profiler view.

**No view is forcibly opened on each build.** A view stays pinned to its
selected project, even if other projects are built. Click
**Follow selected project builds** to switch projects. Click **Open memory
report JSON…** to inspect a manually saved file instead; manual imports never
silently switch on a subsequent build. Existing generated reports are
re-checked against their local map SHA-256 when loaded. Stale/missing reports
show an error, not an apparently current diagram.

Manual standalone use is still supported: run the command above and import
the JSON. Click **Reload selected report** to reopen the file. Manual imports
have unverified current-build freshness.

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
# Headless Java suite includes MemoryProfilerJsonTests, MemoryProfilerLayoutTests
# and MemoryProfilerReportGuardTests:
python scripts/test_core.py
# With built p2 repository available:
python scripts/verify_site.py
```

The headless model tests cover VMA versus LMA, exact integer scaling, intervals,
gated snapshot acceptance, and false MPU overlays. SWT compilation,
installed-view activation, screenshots, multi-theme accessibility, and physical
MPU evidence are **not verified** by those tests.

## Build-lifecycle integrity and limitations

* A clean, failed, or cancelled build cannot publish a new profiler report.
  Auto-build with no source changes does not synthesize a successful new build.
* Profiling runs on the existing background builder thread. The Python parser
  is a bounded, read-only subprocess (25-second limit, 8 MiB JSON limit).
  A profiler parsing/capture failure is shown in the Zephyr build console and
  **does not turn an otherwise verified firmware build into a failure**.
* The generated report is stored in the **exact board/profile build folder**
  and contains map SHA-256 evidence; no global last-build pointer is used.
  Old files are not asserted current if a later profile changes or the map
  bytes differ. The UI checks the current map on read, not continuously.
* The installed p2 plugin ships a byte-identical Python parser. Site verification
  rejects missing/drifted parser resources and missing native UI classes.
* Build completion **does not** imply the firmware was flashed, deployed,
  measured in RAM, or executed on target. MPU values are still unknown unless
  imported from a separate capture with explicitly labelled provenance.
* Sysbuild can have several image maps. This first integration profiles the
  **selected build/zephyr/zephyr.map**, not every child image. Multi-image
  aggregation requires a separate reviewed extension.

## Manual acceptance checklist

1. Open two imported Zephyr projects with distinct board/profile build folders.
2. Open the profiler and select project A using **Follow selected project builds**.
3. Build A successfully; confirm a nonempty JSON snapshot with a map-matching SHA,
   and that A's open diagram refreshes without blocking the UI.
4. Build B successfully; confirm project A remains pinned and B's JSON is
   independently generated in B's build folder.
5. Corrupt A's map after capture; reload A and confirm a visible stale-error state,
   not an old chart presented as current.
6. Run a deliberately broken build, clean, and cancelled build; confirm no new
   profiler report is published as a successful snapshot.
7. Import a standalone JSON file and build A again; confirm manual-import mode
   does not switch reports.
8. Build and install the p2 feature, and confirm the bundled Python parser
   executes under the managed Windows Python and paths containing spaces.
