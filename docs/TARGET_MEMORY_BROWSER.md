<!-- SPDX-License-Identifier: Apache-2.0 -->
# Target Memory Browser — native Eclipse debug integration

Status: integration source implemented; packaged SWT smoke and physical-target
validation must pass before the feature is marked verified.

## Open

1. Start **Zephyr → Run & Debug → Debug Application** and suspend at a breakpoint.
2. In the **Debug** view, select the intended GDB thread/stack frame. The native
   memory UI uses that active debug context; selecting the wrong launch can
   inspect the wrong process.
3. Select **Zephyr → Run & Debug → Open Target Memory Browser**.
   When Eclipse CDT's enhanced **Memory Browser** is installed, it opens with
   its address bar. Otherwise the standard **Memory** view opens; click
   **Add Memory Monitor** (+) to supply an address or expression.
4. Enter a verified address such as `0x20000000` **only if it is valid for the
   selected board**, or a C address expression such as `&my_variable`.
5. View as hexadecimal/ASCII using the installed renderings. Refreshing or
   editing bytes is performed solely by Eclipse and the active DSF-GDB adapter,
   not by Zephyr Workbench.

The **Zephyr Hardware Debug** perspective includes the standard native Memory
view, even when the optional CDT enhanced browser is absent. Standard memory
inspections depend on a debug adapter that supports memory retrieval and an
appropriate suspended target/context; the presence of the view proves neither.
Memory cannot be inspected from a linker map, generated Devicetree or offline
CTF trace alone.

## Safety boundaries

- This is **target memory**, distinct from the Zephyr SoC Memory Map,
  Zephyr Memory Profile (saved CTF) and Visual Memory & MPU Profiler.
- The menu action only opens an existing Eclipse view. It does **not** start
  GDB/OpenOCD, flash, halt, resume, poll, read or write target memory.
- Eclipse's native views may support editing; accidental writes to memory or
  memory-mapped registers can alter firmware behaviour or harm peripherals.
  Treat memory as read-only unless a deliberate target-side change is needed.
- Flash, protected ranges, inaccessible MMIO, running targets and boards
  without a suitable memory service may show retrieval errors. Never label
  unavailable bytes as zero or claim freshness while running.
- Export, diff snapshots, explicit address jump from DTS/SVD and write
  confirmation are **not** provided by this integration.

## Verification

- Source check: `ZephyrHardwareDebugPerspective` includes the Platform Memory
  view; the command prefers an installed CDT Memory Browser and falls back to
  Platform Memory when unavailable.
- Packaged SWT smoke: switching to the Zephyr Hardware Debug perspective must
  expose and successfully instantiate the Platform Memory view.
- Hardware acceptance (not yet evidenced): test on MSPM0G3507 and ESP32-S3
  with a real suspended Zephyr DSF-GDB launch. Read a known RAM variable,
  confirm bytes against the GDB expression, verify the address is not mixed
  between launches and check errors on disconnect and target resume.
  Do not perform writes to live MMIO merely to test this feature.
