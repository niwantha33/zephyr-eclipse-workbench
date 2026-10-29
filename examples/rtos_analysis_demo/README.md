# Zephyr RTOS Analysis Demo

A small test application for the Workbench/Trace Compass experiments. It targets
the Zephyr 4.4.0 API used by Workbench, with no hard-coded peripheral addresses.
Normal operation starts two threads, a timer interrupt and three kinds of text
output. Deliberate fault tests are disabled by default.

## 1. Import and build

1. Extract the ZIP into a new folder, for example
   `C:\projects\Zephyr_RTOS_Analysis_Demo`.
2. In your configured Workbench choose **File > Open Projects from File
   System...**, and select the folder containing `CMakeLists.txt`.
3. Open **Project Properties > Zephyr**. Select the exact board/core and
   **Debug (-Og)**. For your TI board: `lp_mspm0g3507/mspm0g3507`.
4. Build and check the generated `.config` and the memory usage report.
5. Flash the selected configuration using the correct probe tool.

The source uses portable Zephyr kernel APIs. The exact target still needs a
supported console/backend and enough RAM for the stacks, logging and trace
buffer. No board overlay is included. Other targets need their own build and
hardware validation; this archive is not a tested all-board firmware binary.

## 2. Observe messages

Open the board's configured console using a serial terminal and the actual
baud rate from its configuration (often 115200; check rather than assume).
For MSPM0/XDS110, select the board's application UART COM port, not an unrelated
CCS board. The board's pin routing/jumpers must connect that UART to the probe.

You should see recurring messages like:

```text
RTOS analysis demo: fault tests disabled
printf: A=... B=... IRQ=...
printk: mode=1 signal=...
<inf> rtos_demo: Counters A=... B=... IRQ=...
```

Formatting and ordering vary: printk may be redirected into deferred logging.
If no messages appear, check the effective console, UART/USB pin routing,
stdio and log backend configuration for that exact board. `STDOUT_CONSOLE`
depends on a console driver. Configure the appropriate backend before treating
missing text as a problem with the two threads.

The current Workbench RTOS preview does not yet provide the planned unified
UART/RTT application-message view. Its RTOS trace console mainly contains tool
output. Use your terminal for this test until that view exists. Plain printf
text has no built-in thread identity or precise target timestamp.

## 3. Inspect the timeline

Terminate any native debug session that owns the probe. Select
**Zephyr > Capture RTOS Trace (Preview)**. This resets the target and records a
short RAM snapshot. Import the newest capture's `ctf` directory and its XML
analysis into Trace Compass, then open **Zephyr RTOS timeline**.

Expected rows include `worker_A`, `worker_B`, `demo_main`, `idle`, and the logger
thread when present. Worker A has priority 2; B has priority 3. A periodically
wakes and can preempt B. The timer expires every 100 ms; system timer and other
interrupts may also appear. Named events `a_begin`, `a_end`, `b_begin`, `b_end`
and `counter_sample` are additional inputs in the event list; the current XML
does not turn these automatically into duration/value charts.

The 4096-byte trace is finite and may fill quickly. It is not a complete
recording of everything printed over a long run. The preview still lacks
automatic full/loss reporting, precise CPU charts, full thread-state decoding
and automatic timeline opening. Green currently means scheduled including ISR
time; grey means not scheduled.

## 4. Check counters and registers

Start native Debug, resume for about a second, then pause. In the connected
GDB console, inspect:

```gdb
p worker_a_count
p worker_b_count
p timer_irq_count
p sample_signal
p/x demo_magic
p &demo_magic
info registers
```

On Cortex-M, also use:

```gdb
info registers pc sp lr
```

The counters should increase when running. `demo_magic` starts at `0x13579bdf`.
These are ordinary RAM watch values, not MCU peripheral registers. Use the
native Registers, Memory and Disassembly views to inspect the halted context.
SP/PC names and memory ranges differ on non-Arm targets.

With the normal debug session halted, change the workload:

```gdb
set variable load_mode = 0
continue
```

Pause later and inspect counters, then repeat with `load_mode = 1` for a heavier
workload. This does not currently produce an automatic CPU percentage chart.
For two separate automatic captures, change `DEMO_INITIAL_LOAD_MODE` in
`src/demo_config.h`, rebuild and flash each variant: the capture command resets
the board, so a previous debugger-only edit is lost.

Low mode requests A busy 1 ms/sleep 99 ms and B busy 5 ms/sleep 195 ms. High mode
requests A busy 5 ms/sleep 45 ms and B busy 40 ms/sleep 60 ms. These provide a
contrast, not calibrated CPU percentages. Preemption, tick rounding, logging,
trace overhead and busy-wait timer behaviour affect measured intervals.

## 5. Optional deliberate exception test

This stops normal application execution. Use the test project on the selected
development board. No flash/EEPROM erase, peripheral writes or automatic fault
trigger are part of this example.

1. Change `DEMO_FAULT_TESTS_ENABLED` from `0` to `1` in `src/demo_config.h`.
2. Rebuild and flash, then start native Debug and resume.
3. Pause and enter ONE of the following requests in its connected GDB console.

Portable Zephyr panic (not a hardware HardFault):

```gdb
set variable fault_request = 1
continue
```

Cortex-M undefined-instruction exception:

```gdb
set variable fault_request = 2
continue
```

MSPM0's Cortex-M0+ delivers HardFault for the undefined instruction. Larger
Cortex-M targets may deliver UsageFault or HardFault depending on configuration.
On non-Cortex-M targets request 2 falls back to `k_panic()`; this is not an
architecture-specific fault decoder test for those CPUs.

Main processes the request on its next loop, normally within about a second.
Look for the `DELIBERATE TEST` message and Zephyr's fatal output. If the debugger
does not break automatically, pause it and inspect the registers and call stack.
The live PC may be inside the fault handler/halt loop; it is not necessarily the
saved exception PC. The planned fault pane is not implemented yet. Restore the
enable macro to `0`, rebuild and flash when finished.

## 6. Optional saved core dump

If the selected Zephyr target supports core dumps, append the three settings
from `coredump.conf` to `prj.conf`, rebuild and flash. Do not enable the entire
linker-RAM dump on a small board simply to run this test. Capture the console
through the entire fatal output; the logging backend may emit a record starting
with `#CD:BEGIN#`. Use the matching checkout's `scripts/coredump` tools and the
ELF from that exact firmware. Stop if the target does not support the requested
Kconfig options. Core-dump import in the proposed Workbench analysis view is
still future work.

A full RAM trace may already have stopped recording long before this manual
fault. Do not assume it contains the events immediately preceding the crash.
Trigger/ring-buffer and complete session correlation need further implementation.

## Test checklist

| Check | What this demo supplies | Current verification route |
|---|---|---|
| Two threads and preemption | Higher-priority A and lower-priority B | Current CTF timeline |
| Interrupt activity | Periodic timer callback and counter | Trace events / counter |
| printf, printk, LOG_INF | Three message sources | Configured application console |
| Core registers and RAM watches | Live context and named values | Native GDB/Registers/Memory |
| CPU-load contrast | Low/high workload modes | Inputs for future charts; no fixed percentage assertion |
| Fault context | Explicit panic or Cortex-M instruction exception | Zephyr fatal output and native debugger |
| Offline crash input | Optional minimal logging core dump | Existing Zephyr tools; future Workbench integration |

## Validation boundary

This sample targets Zephyr 4.4.0 (`684c9e8f32e4373a21098559f748f06915f950c9`).
Debug information is emitted by Zephyr's compiler flags; `CONFIG_DEBUG_INFO` is
not a valid option in this checkout and must not be added to `prj.conf`.
Select **Debug (-Og)** for debugger-friendly optimization.

The corrected source compiled and linked for `lp_mspm0g3507/mspm0g3507` with
Zephyr SDK 1.0.1 (GNU Arm compiler), against that pinned Zephyr revision:

| Build profile | Flash | RAM |
|---|---:|---:|
| Application settings | 26,356 bytes | 13,712 bytes |
| Debug (`CONFIG_DEBUG_OPTIMIZATIONS=y`) | 34,712 bytes | 13,728 bytes |

Both ELFs contain `.debug_info` and `.debug_line`; the Debug ELF also contains
`.debug_frame`. CTF RAM tracing and its 4096-byte buffer were enabled. No Kconfig
warnings were suppressed. These are Linux cross-build results, not physical
board execution. Probe access, console routing and runtime trace behaviour
still require validation on the board.

Original sample code is Apache-2.0; see LICENSE.
