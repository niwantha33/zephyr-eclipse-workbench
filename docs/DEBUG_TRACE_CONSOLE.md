# Zephyr Debug Trace Console (first integration slice)

Open **Zephyr > Capture & Analyse > Debug Trace Console...** during an
existing Zephyr Debug session or after selecting an imported Zephyr project.
The view now first follows an active native Zephyr debug-context project,
then an explicit single project selection, then a **unique** live Zephyr
launch. If two different running projects remain ambiguous it refuses to
guess and asks you to select a project/thread. The **Use selected project /
debug session** button also resolves the active Debug context. Changing to
another Zephyr debug context updates the project and effective build evidence.
Project discovery never accesses the MCU or starts a new debug session.

This view reads generated `zephyr/.config` for that project's selected
board/profile; it does **not** prove that this build was flashed. If the
project's active build/profile differs from the already-flashed debug ELF, the
displayed backend configuration is build intent, **not** verified live
firmware configuration. It does **not** read target RAM, attach to a probe,
flash or reset merely by opening the view.

**The console is not an automatic live streaming terminal.** To see text,
start **Capture UART/USB text** with a confirmed serial port/baud, or
explicitly connect to an already running local RTT TCP receiver. CTF is
binary capture evidence, not readable console text. On TI MSPM0G3507
(Cortex-M0+) the ITM/SWO channel is unavailable; this is a hardware
limitation, not a Workbench project-selection failure.

## Guided CTF → Timeline / CPU Load (Bug 6)

Both **Zephyr Thread Timeline** and the separate **Zephyr CPU Load** view
are **saved-CTF offline analysers**, not real-time monitors. Opening the view
alone never reads the target or starts a decoder. The empty tables mean
**No trace loaded**, not a broken CPU probe.

The new controls in each view are intentionally sequential:

1. **1. Capture new CTF...** — resolves the already selected imported
   Zephyr project or unique active Zephyr debug project, then invokes the
   *existing* confirmed RAM CTF capture flow. This is a **target-affecting**
   operation: first build and flash the matching RAM tracing configuration,
   terminate an active Debug session to release the probe, and review the
   confirmation. Capture resets the MCU, runs it for the configured short
   interval (default 1 second), and leaves it halted.
2. **2. Open latest capture** — reads only the most recent complete saved
   `.zew-traces/trace-*/ctf` in that project; no probe/flash/target access.
   Missing `metadata` or `channel0_0` files are rejected. If there is
   no saved CTF, the UI says so instead of leaving an unexplained empty table.
3. **Open saved CTF trace... / Load trace...** — select an older
   `.zew-traces/trace-*/ctf` folder for comparison. The two views continue
   to analyse only on explicit selection; they do not poll memory in Debug.

After a **successful explicitly requested capture**, any *already open*
Timeline and CPU Load views for the selected application are notified and load
the saved CTF. The capture still saves `capture.json`, `diagnostics.json`,
`report.html` and CTF bytes even when a viewer cannot decode them.
A missing/unsupported decoder, incomplete scheduler events or reported loss
must remain visible as unavailable/partial rather than fabricated CPU load.

For an MSPM0G3507 project, typical `prj.conf` configuration is:

```ini
CONFIG_TRACING=y
CONFIG_TRACING_CTF=y
CONFIG_TRACING_BACKEND_RAM=y
CONFIG_TRACING_SYNC=y
CONFIG_RAM_TRACING_BUFFER_SIZE=4096
CONFIG_THREAD_NAME=y
```

Check the **effective** `build/zephyr/.config` in the selected build
profile before capture. Build and deliberately flash the same ELF/image;
the tool cannot independently prove the flashed firmware equals that ELF.
The active debug server and the CTF capture command cannot both own the
XDS110 probe. If the capture fails, inspect the separate *RTOS trace*
Eclipse Console, not a blank CPU table. The offline decoder requires the
selected managed Python to provide Babeltrace 2 `bt2`, and CPU percentages
require complete scheduler evidence. RAM buffer overflow/fullness can
invalidate proportions; consider shorter captures or a supported larger
buffer after checking board RAM resources.

This update is **workflow integration**, not continuous live CPU profiling
and not a proof of probe/trace correctness on real hardware. The analyzer
retains its prior evidence-only output rules.

## Bug 11 — CTF files appear missing in Windows picker

Zephyr CTF is a **directory**, not one file ending in `.ctf`. A saved
snapshot has this layout:

```text
<application>/.zew-traces/trace-YYYYMMDD-HHMMSS-NNN/
  capture.json
  diagnostics.json
  report.html
  ctf/
    metadata       (extensionless TSDL)
    channel0_0     (binary CTF stream, extensionless)
```

The old Windows **Select Folder** dialog hid `metadata` and `channel0_0`
and displayed an apparently empty directory. In the Timeline, CPU Load,
Contention, Memory and Tracepoint views, **Open saved CTF trace** now opens
a native **file** chooser: select either `metadata` or `channel0_0` in
the `ctf` folder. The view receives the containing directory and verifies
both required files. **Open latest capture** is still the simpler option
for the selected application project. The separate Trace Compass handoff
selects the visible `capture.json` receipt in the parent `trace-*` folder,
not a hidden directory; it validates the receipt and both CTF files before
requesting handoff.

Importantly, a visible/saved stream does **not** mean it was decoded. A
`CTF decoder: not checked` receipt means the Python interpreter used by
Workbench did not validate it, commonly because the Babeltrace 2 `bt2`
module is unavailable. The capture itself may still be saved. The offline
viewers now reuse the safe isolated Python launch method from Bug 10 for
their bundled sibling helpers; this fixes module lookup under Windows
embedded `python313._pth`, but does **not** install Babeltrace or claim
decoding occurred. An actual `bt2` import and decoder validation or
Trace Compass inspection is still required.

If `capture_full: true`, independently increase the supported RAM trace
buffer (subject to available MCU memory) or shorten the capture and rebuild
and flash that configuration. Overflow compromises completeness; no CPU
percentage, timing certainty or event-loss count may be inferred from an
incomplete stream. All these operations stay offline except an explicit
new capture, which resets/runs/halts the target.

## Capture helper import failure (Bug 7)

A previous installed Workbench failed **after opening OpenOCD** with
`ModuleNotFoundError: No module named 'analyze_trace'` from the staged
`capture_snapshot.py`. The Python module `analyze_trace.py` belongs to
the Workbench plug-in, **not** the user's application or Zephyr `prj.conf`.
Installing `analyze_trace` using pip or changing the firmware is not the
correct remedy.

The repository has long listed the module among the copy resources; the
reported failure therefore does **not** establish whether the installed
plug-in was outdated or the temporary staging/runtime was incomplete.
The revised capture implementation:
- Uses one explicit capture-resource manifest and verifies the **installed**
  OSGi bundle supplies all files, including transitive Python modules.
- Copies those files to an isolated temporary capture directory and runs the
  bundled `capture_snapshot.py --help` import preflight using the **managed
  Python interpreter** before opening an OpenOCD process.
- Aborts cleanly if imports fail, without target reset or GDB connection.
  Success confirms package/import readiness, **not** physical trace validity.
- Keeps the original error in the Eclipse RTOS Trace Console and lets the
  Eclipse Job report a single failure rather than displaying two modal dialogs.
- Tests both the isolated Python staging and installed OSGi resources in CI.

To validate on an updated installation: terminate any old Debug/OpenOCD
session, inspect the **RTOS trace** Console for
`Verified bundled CTF capture Python imports before connecting to OpenOCD.`
This is a prerequisite for successful trace capture, **not** proof that the
running firmware matches the ELF, or that the trace decodes successfully.
If the failure repeats, report the installed Workbench bundle version,
the full RTOS trace console output and the Python interpreter configured
for that application; do not manually patch the temp directory.

## Trace Compass integration (Bug 8)

Use **Zephyr > Capture & Analyse > Trace Compass Integration...** or the
**Trace Compass...** button at the top of **Zephyr Debug Trace Console**.
This restores a visible pathway that was missing from the Zephyr menu.
The optional integration detects an installed Eclipse **Tracing** perspective
without adding a hard dependency on Trace Compass plug-ins. If the perspective
is absent, the UI links to the official installer and supports a **standalone**
Trace Compass executable instead. Both require **saved** CTF evidence;
there is no automatic live trace transport or probe operation in this view.
See [Trace Compass integration](TRACE_COMPASS_INTEGRATION.md) for the exact
install, saved-capture and XML analysis steps.

## What works now

| Output | Evidence check | Capture action |
| --- | --- | --- |
| Binary CTF in target RAM | `CONFIG_TRACING=y`, `CONFIG_TRACING_CTF=y`, `CONFIG_TRACING_BACKEND_RAM=y` | **Capture RAM CTF** reuses the existing OpenOCD/GDB snapshot capture, with its confirmation, locking, bounded timeout, trace report and optional Trace Compass handoff. It resets/runs/halts the target; the flashed image identity remains unverified. |
| Text via UART or USB serial | `CONFIG_UART_CONSOLE`, `CONFIG_LOG_BACKEND_UART`, or `CONFIG_SHELL_BACKEND_SERIAL` | **Capture UART/USB text** reuses the existing explicitly named-port, bounded PySerial capture. No GDB/probe operation. The named port must be free. Serial output is displayed as it arrives and saved as raw bytes and JSONL. |
| ITM/SWO | Presence of SWO/ITM log or console configuration and known Cortex-M0(+) exclusion | Build intent is displayed only. No probe-specific SWO receiver is implemented in this slice. |
| SEGGER RTT | RTT log, console or shell configuration | Opt-in passive localhost TCP receiver connects to an already configured RTT server. No target setup, memory search or probe commands. |
| Semihosting | Semihosting log or console configuration | Build intent is displayed only. No host receiver is implemented in this slice. |

**Configured** means the effective build enables an output path. It does not
establish that a probe supports the transport, that pins are connected, that the
clocks or stimulus ports are correct, that a log can actually be emitted, or
that the matching firmware was flashed. Missing `.config` is **unknown**, not
"all backends off."

The embedded text pane shows diagnostic messages from the RAM and UART capture
jobs. **Binary CTF is not text**; open the saved `.zew-traces` capture with its
report or Trace Compass. Both existing capture jobs continue to retain their
Eclipse Console output and their respective project-local evidence folders.
UART time labels are **host reception** times, not MCU event timestamps.

## Example firmware settings

For the existing RAM CTF capture, use the target Zephyr version's supported
settings, then rebuild and flash the exact configuration:

```ini
CONFIG_TRACING=y
CONFIG_TRACING_CTF=y
CONFIG_TRACING_BACKEND_RAM=y
CONFIG_RAM_TRACING_BUFFER_SIZE=4096
CONFIG_THREAD_NAME=y
```

For text logging, choose **one actual hardware backend** supported by the
board and toolchain, e.g. `CONFIG_LOG=y` plus
`CONFIG_LOG_BACKEND_UART=y`, `CONFIG_LOG_BACKEND_RTT=y`, or
`CONFIG_LOG_BACKEND_SWO=y`. If the goal is to route `printk()` through the
logger, evaluate `CONFIG_LOG_PRINTK=y` against that backend's constraints.
The generated `.config`, not edits to `prj.conf`, is the source of truth.

Cortex-M0/M0+ targets, including TI MSPM0G3507, do not provide ITM/SWO.
Do not attempt to configure SWO on them. Other Cortex-M targets require
hardware, accessible SWO wiring, a probe and matching clock settings;
this console does not infer those from `CONFIG_CPU_CORTEX_M4=y`.

## Next independent adapters

1. **RTT follow-up**: verify a compatible probe, configure control-block addressing
   without taking over an active GDB server, support dedicated channels and
   authenticate the local RTT server. The current receiver is passive TCP only.
2. **ITM/SWO**: verify ITM/DWT/TPIU presence, SWO pad, probe command support,
   core clock and SWO baud; decode stimulus port text separately from binary
   instrumentation records; distinguish target timestamps from host receive time.
3. **Semihosting and crash capture**: session-owned debugger output and
   bounded post-fault RAM snapshots without silently resuming the target.
4. **Saved evidence analysis**: search/filter/export, lost-byte counters,
   timestamp provenance and optional alignment with existing CTF investigation.

Keep these as separate, testable PRs. No backend should silently reconfigure
an active physical debug session or conflict with the user's terminal.

## Opt-in passive RTT over a preconfigured local TCP receiver (development)

For firmware with RTT logging enabled, configure a compatible RTT server
**outside** the workbench, using the correct channel/control block address and
probe-specific commands. In **Debug Trace Console**, enter its TCP port
(default 19021, configurable per Eclipse project), then explicitly click
**Connect RTT**. Workbench opens a connection to **127.0.0.1 only** for up
to 60 seconds and shows channel text in Eclipse Console and the trace pane.
The **Stop RTT capture** control cancels the job. Raw bytes are saved in a
uniquely named `.zew-rtt/rtt-*.bin` file, capped at 4 MiB. Console text is
decoded as UTF-8 on receive; split multibyte characters can appear as
replacements, so the raw capture is authoritative.

This is only a **receiver**. It does not search for the RTT control block,
start or reconfigure a debug server, take ownership of an existing GDB
connection, flash, halt, reset or write target memory. Port availability and
RTT channel configuration are not verified by `.config`. A local TCP connection
does not prove which firmware is running. Labels are host-reception observations,
not target-event timing. Hardware/probe coexistence testing is still pending.

UART/USB serial capture now remembers the last confirmed port and baud rate
separately for each Eclipse project. These are only dialog defaults; users
must reconfirm the port, baud, duration and capture each time. Existing UART
capture still uses its bounded process and Eclipse Console and does not share
RTT's TCP connection.

## Not supported

- **Live CTF streaming to the IDE is not supported**, whether over RTT,
  UART/USB or network. The menu item **Start Live Trace (not yet available)**
  only displays a message; it does not connect, sample, append to the offline
  CTF parser or create a background job. The existing UART/RTT **text**
  receivers and the RAM CTF **snapshot** action are different, already
  documented features. See [design proposal](LIVE_TRACE_STREAMING_DESIGN.md).

For current feature status and hardware-validation claims, see the [single-source-of-truth trace analysis capabilities table](TRACE_ANALYSIS_CAPABILITIES.md). Implementation guidance here does not supersede its status.
