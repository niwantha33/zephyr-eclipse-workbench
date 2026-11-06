# Zephyr Debug Trace Console (first integration slice)

Open **Zephyr > Capture & Analyse > Debug Trace Console...** after selecting an imported
Zephyr project. This view reads the generated `zephyr/.config` for the selected
board/profile. It does **not** read target RAM, attach to a probe, flash, reset, or
claim that the image on the device matches the build just by opening the view.

## What works now

| Output | Evidence check | Capture action |
| --- | --- | --- |
| Binary CTF in target RAM | `CONFIG_TRACING=y`, `CONFIG_TRACING_CTF=y`, `CONFIG_TRACING_BACKEND_RAM=y` | **Capture RAM CTF** reuses the existing OpenOCD/GDB snapshot capture, with its confirmation, locking, bounded timeout, trace report and optional Trace Compass handoff. It resets/runs/halts the target; the flashed image identity remains unverified. |
| Text via UART or USB serial | `CONFIG_UART_CONSOLE`, `CONFIG_LOG_BACKEND_UART`, or `CONFIG_SHELL_BACKEND_SERIAL` | **Capture UART/USB text** reuses the existing explicitly named-port, bounded PySerial capture. No GDB/probe operation. The named port must be free. Serial output is displayed as it arrives and saved as raw bytes and JSONL. |
| ITM/SWO | Presence of SWO/ITM log or console configuration and known Cortex-M0(+) exclusion | Build intent is displayed only. No probe-specific SWO receiver is implemented in this slice. |
| SEGGER RTT | RTT log, console or shell configuration | Build intent is displayed only. No probe-specific RTT receiver is implemented in this slice. |
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

1. **RTT**: discover/verify a compatible probe, negotiate a receiver session
   without taking over an active GDB server, support dedicated channels and
   bound memory, process lifetime and log size.
2. **ITM/SWO**: verify ITM/DWT/TPIU presence, SWO pad, probe command support,
   core clock and SWO baud; decode stimulus port text separately from binary
   instrumentation records; distinguish target timestamps from host receive time.
3. **Semihosting and crash capture**: session-owned debugger output and
   bounded post-fault RAM snapshots without silently resuming the target.
4. **Saved evidence analysis**: search/filter/export, lost-byte counters,
   timestamp provenance and optional alignment with existing CTF investigation.

Keep these as separate, testable PRs. No backend should silently reconfigure
an active physical debug session or conflict with the user's terminal.
