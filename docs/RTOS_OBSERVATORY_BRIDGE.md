<!-- SPDX-License-Identifier: Apache-2.0 -->
# RTOS Observatory integration — Phase 1

This bridge is an **opt-in software integration** between
[Zephyr Eclipse Workbench](https://github.com/niwantha33/zephyr-eclipse-workbench)
and [RTOS Observatory](https://github.com/niwantha33/RTOS-Observatory).
It is **not** a verified live trace connection, a second debugger, a safe
memory snapshot service or evidence of hardware qualification.

## Ownership boundary

- **Eclipse retains:** project/workspace selection, Devicetree, Kconfig, build,
  flash, native CDT/DSF-GDB/OpenOCD debugging, SVD and the existing trace
  *capture* workflow. Existing installed features remain until replacement is
  technically qualified.
- **Observatory owns:** RTOS-independent event models, offline timeline/CPU,
  scheduling/lock/resource analysis, causal investigation, independent GUI,
  evidence reports and later pluggable RTOS adapters.
- **W14 bridge owns only:** passive lifecycle metadata from the exact selected
  existing DSF session. No GDB/MI command, memory/register read, probe socket,
  flash, halt, resume or reset is issued by the bridge.

## Configure on Windows

Build/install the standalone Observatory executable first using its own
documented build steps. Then set environment variables **before** starting
Eclipse (use paths on your machine):

```powershell
$env:RTOS_OBSERVATORY_EXE = "C:\My-Projects\RTOS-Observatory\build-windows-clang\rtos-observatory-desktop.exe"
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\RtosObservatoryBridge" | Out-Null
$env:RTOS_OBSERVATORY_BRIDGE_DIR = "$env:LOCALAPPDATA\RtosObservatoryBridge"
# Launch Eclipse from this same PowerShell session so it inherits the variables.
```

**Do not assume that example executable path exists on every CMake generator.**
The existing Observatory build may put the EXE under a `Release` subfolder.
The directory must be private to the user and not a symbolic link.

In Eclipse use **Zephyr → Advanced Tools → Tracing & RTOS**:

1. Start an existing **CDT DSF-GDB** debug session, then select its launch in
   the Eclipse Debug view. Choose **Toggle Passive Observatory Bridge**.
   The bridge writes `rtos-observatory-dsf-status.tsv` into the configured
   private directory, using the W14 versioned one-line protocol.
2. Choose **Open RTOS Observatory**. The native GUI starts as an independent
   process. If the bridge status file exists and is bounded, the GUI receives
   `--bridge-status <absolute-path>`. Otherwise it opens without live status.
3. In Observatory choose **Debugger lifecycle → Refresh local status** to read
   the latest *self-reported* metadata. A heartbeat is **not** proof of actual
   target liveness. Reopen Observatory if a status file is created only after
   its initial launch.
4. Toggle the bridge again to stop publishing. It also stops on debugger
   launch termination/removal. This does not alter the target.

The launch uses an explicit argument vector, never `cmd.exe` or shell
concatenation. The bridge status must be a regular, non-symlink file <=4096
bytes. Observatory independently validates the protocol and freshness.
A missing executable fails with an actionable error; it is **not** downloaded
silently and Eclipse does not start or own its lifetime.

## Next offload increments (not implemented here)

| Order | Work item | Required gate |
| --- | --- | --- |
| B2 | Saved CTF → loss-aware universal events adapter, inside Observatory | Golden fixture parity with current Zephyr captures; timestamp/clock domain and loss semantics preserved |
| B3 | Launch standalone timeline, CPU, contention and drift views from saved captures | Cross-tool identical findings where semantics overlap; no fabricated event precision |
| B4 | Explicit read-only suspended-state RAM/TCB/register snapshots through owning DSF (Observatory W15 proposal) | User consent, board-qualified RAM allowlist, core coherency, cancellation, bounded reads, installed PDE tests |
| B5 | Live event transport: producer-specific adapters (Zephyr, FreeRTOS, TI-RTOS/ThreadX where feasible) | Versioned IPC, backpressure/drop metrics, clock reconciliation, throughput benchmark, target overhead evidence |
| B6 | Deprecate duplicated Eclipse RTOS analysis only after parity and CI gates | Windows/Linux package gates, regression tests, migration/rollback support, hardware qualification separately |

**Migration rule:** no delete-first refactor. Introduce per-feature capability
flags (Workbench local / Observatory external) and migrate one proven consumer
at a time. Keep the CTF recorder until Observatory's replacement is qualified.
Do not port Zephyr-specific assumptions into Observatory's RTOS-neutral core.

## Verification status

- Java launch-plan tests are wired into `scripts/test_core.py`.
- Eclipse plugin class and menu registrations are included on the branch and
  must pass the Tycho/PDE packaging gate.
- Observatory's native W14 reader and UI already accept `--bridge-status`
  by documented contract. This integration has **not** performed a real
  Eclipse-DSF debugging run or board qualification.
- **No auto-merge** until CI and installed-Eclipse smoke evidence are reviewed.
