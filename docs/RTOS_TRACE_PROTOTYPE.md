# RTOS trace snapshot prototype

This is the first, deliberately incomplete part of RTOS Analysis. It captures a
Zephyr CTF RAM trace from an existing local OpenOCD GDB server using the same ELF
and metadata layout as the manually validated MSPM0 experiment. It creates a
fresh capture directory containing `channel0_0`, matching-checkout `metadata`,
the prototype Trace Compass XML and a provenance receipt.

The helper is not yet integrated into the Eclipse UI. The caller must build and
flash the same firmware, start an OpenOCD server for the intended target, and
provide the architecture-correct GDB and Zephyr checkout. It resets the target,
runs it briefly, and leaves it halted. It does not detect which probe is in use,
verify flashed firmware against the ELF, or determine whether the RAM buffer
has filled. Do not interpret its output as a complete recording. A lost
connection can leave the target running; stop the owned debug server and inspect
the target. This prototype requires GDB with Python support and OpenOCD monitor
commands. It has not been tested with hardware or on Windows in this change.

An example for a prepared MSPM0 build and an existing server on localhost:3335:

```
python capture_snapshot.py --gdb <arm-gdb> --build <build-directory> \
  --zephyr <workspace-zephyr-directory> --output <capture-parent> --port 3335
```

The selected build must enable `CONFIG_TRACING`, `CONFIG_TRACING_CTF`, and
`CONFIG_TRACING_BACKEND_RAM`. The metadata must come from the same checkout that
built the firmware. Trace Compass can import the capture folder as Generic CTF;
import `Zephyr_RTOS_Timeline.xml` using Manage XML Analyses.

The initial timeline shows scheduled/not-scheduled thread intervals and a
non-nested ISR lane. Scheduled time includes interrupt time. It does not infer
ready/sleeping/blocked states, exclusive CPU use, or nested interrupt activity.
The metadata timestamp clock and event-loss behaviour must be validated per
Zephyr version and hardware. Observed maxima are not a WCET guarantee.

Outstanding work before release: provide a single UI workflow for an isolated
trace build, tool and probe selection, explicit flashing, server lifecycle,
cancellation and cleanup, firmware/ELF matching, fullness and trace-loss
reporting, metadata discovery, automated viewer import, richer scheduling and
interrupt analysis, Windows integration tests and real MSPM0/ESP32 hardware tests.
