# RTOS observability: tasks, logs, CPU, registers and faults

Status: proposed product requirements, not implemented functionality. This extends
the [analysis roadmap](RTOS_ANALYSIS_ROADMAP.md). The current release has an
OpenOCD RAM snapshot and prototype scheduling timeline; it does not yet implement
the unified workspace described here.

## Product outcome

One Eclipse Analysis perspective should answer: what ran, why it waited, what
the application said, what the CPU/peripherals contained at a captured point,
and what evidence explains a crash. Selecting an event or time interval should
synchronise all compatible views and offer source/disassembly navigation.

The target experience includes UIA-style execution graphs, application event
logs, CPU/task load, duration measurements and numeric plots. Going beyond that
baseline requires measured accuracy, usable fault investigation, simple setup
across supported targets and reproducible reports. No comparative superiority
is claimed by this specification.

## Required views and behaviour

| View | Required result |
|---|---|
| Task execution | Thread names, priorities, running/ready/blocked/sleeping states where proven, preemptions, object waits and gaps for unknown intervals. |
| CPU charts | Total busy/idle and per-core, per-thread and ISR breakdowns over a selected interval. Keep interrupt time separate from thread-exclusive execution. Show window size, clock basis, gaps and loss warnings. |
| Application messages | Display supported `printf`, `printk` and Zephyr log output live and from saved sessions; search/filter by severity, module, thread and core when supplied. |
| Application events | Named markers, start/end duration events, counters and numeric value plots linked to scheduler activity. Ordinary text is not automatically a structured numeric event. |
| Registers and peripherals | Core registers, SVD-backed peripheral/bitfield decoding, watches, explicit snapshots, value differences and sampled history with read-time/state labels. |
| Faults and exceptions | Exception reason/evidence, current and saved fault context, PC/SP/LR or architecture equivalents, stack/backtrace quality, relevant status fields, source/disassembly and nearby trace/log evidence. |
| Synchronisation and memory | Mutex/semaphore/queue contention, ownership, priority-inversion evidence, stack high-water marks and heap lifetime/fragmentation metrics where the input supports them. |
| Saved investigations | A versioned session bundle that reopens offline with matching ELF, configuration, metadata and provenance; baseline comparisons and CSV/HTML reports. |

## Console and printf contract

The user must be able to keep familiar firmware calls such as:

```c
printf("Counter=%u\n", count);
printk("Worker started\n");
LOG_INF("Received %u bytes", length);
```

These need their normal headers/module registration and a supported configured
output path. A host-side view cannot display a message that firmware never
routes to a console/log backend. Discover the effective configuration and offer
simple guidance for UART/USB serial, supported RTT, or another available backend.
Do not promise every transport on every CPU/probe.

First deliver plain UART/serial reception and Zephyr logging integration, then
supported RTT adapters. Separate the application console from OpenOCD/GDB tool
diagnostics. Reuse transport ownership; do not start a competing probe client or
open an already-owned COM port. Reconnect visibly and bound log storage/queues.

Keep raw text as well as decoded messages. Plain printf output can lack target
timestamps, severity and thread identity: label those fields unknown. Host receive
time is not the time the MCU emitted a message. Correlate with the timeline only
when the input contains a common target clock or a documented synchronisation
mapping, with uncertainty stated. Retain boot/session identifiers across reset.

Never mix unframed CTF binary bytes and console text on the same stream. Use
separate channels or an explicitly supported framing protocol. Dictionary log
decoding must use metadata from the matching firmware. Detect drops, truncation,
invalid encoding and incomplete crash dumps. Logging overhead and deferred
delivery must be visible in performance validation.

## Register analysis contract

Use the existing Eclipse CDT/DSF-GDB session for core registers and memory when
debugging. A viewer must not open a second connection to a single-client server
or independently reset/halt/resume the board. Read capabilities, address width,
register descriptions and byte order from the target/debug adapter; do not assume
all chips are 32-bit little-endian or expose identical register numbers.

Keep core registers separate from memory-mapped peripherals. SVD files describe
peripherals, not a portable complete definition of the core's execution context.
Resolve device descriptions for the exact chip and revision and record their
source/version/checksum. Respect the licences of device metadata and optional
viewer components. Independently implement the public core; copying a private or
proprietary viewer into it is outside this specification.

Default peripheral analysis is read-only and selective. Honour read-side-effect
metadata, access widths and inaccessible/power-gated regions; skip unsafe or
unknown reads with an explanation. Never scan all peripheral addresses blindly.
Any future writes need explicit actions and device-specific handling of reserved,
read-only and write-one-to-clear fields.

Show whether values were read while halted, while running, from a core dump, or
from an instrumented record. Polling history is sampled history; it cannot show
every intervening transition. Running-target reads may be unsupported or
non-atomic. Do not halt a real-time application automatically to refresh a chart.
Historical PC/SP values exist only if captured; a saved timeline alone cannot
reconstruct arbitrary earlier CPU registers.

An optional external register UI should consume a versioned snapshot/transport
interface. Its IDE-specific presentation remains separate. The native Eclipse
Registers/Memory/Disassembly views remain available as the initial integration
point while richer peripheral and fault views are built.

## Fault and exception investigation

Support two explicit acquisition paths: a halted live debug session and a saved
Zephyr core dump. Use the selected checkout's core-dump format/tools and verify
available architecture support. Match the ELF/build identity before symbolising;
show mismatch or missing-memory errors rather than misleading source locations.

For Cortex-M, distinguish the current handler's PC/SP from the exception frame's
saved PC/SP/LR/xPSR. Select MSP/PSP and validate exception-return/frame information,
stack bounds and memory readability. Account for alignment, extended floating
point frames and security state when applicable. Do not apply one fixed stack
offset formula to all Cortex-M variants or to a corrupt frame.

Decode CFSR/HFSR/MMFAR/BFAR only when implemented by that core and valid for the
captured fault. Cortex-M0/M0+ lacks the richer fault-status/address registers
available on Cortex-M3/M4/M7; MSPM0 must still show its available exception context
and saved registers without inventing those fields. Mark unsupported information
as unavailable. RISC-V traps and Xtensa exceptions need their own decoders and
register maps; do not label every architecture's exception as an Arm HardFault.

Present raw values beside explanations. Separate confirmed flags, source
locations and stack evidence from possible causes such as overflow or an invalid
pointer. An imprecise fault or damaged stack may not identify the faulting
instruction. Annotate partial/unreliable backtraces. A source line alone does
not prove the cause.

Preserve crash evidence before offering reset. Capture acquisition must not clear
status registers, resume, reset or reflash automatically. Break-on-fatal support
is an explicit debug configuration capability. Core dump/logging backends can
fail during a fault: do not assume deferred logs or a lock-dependent transport
will finish. Detect incomplete records and retain the raw input for inspection.

## Shared data model and integration

| Record | Minimum provenance |
|---|---|
| Session | Exact target/core, architecture, boot/session ID, runner/probe/transport, firmware and ELF identity status, Zephyr version, configuration and metadata hashes. |
| Timed event | Raw target timestamp and clock mapping, host receive time if known, thread/core if supplied, event type/payload, origin stream, sequence/loss information and quality flags. |
| Register snapshot | Target/core, capture interval, halted/running/dump state, register IDs/widths/order, raw values or explicit read errors, relevant memory map and device-description identity. |
| Fault record | Exception type, live context and saved frame with separate provenance, validity/unsupported fields, evidence links and symbolisation/unwind quality. |
| Analysis result | Input records, selected interval, calculation/version, completeness and units; explicit unknown intervals instead of fabricated zeros. |

Separate acquisition, architecture/format decoders, normalised session storage,
analysis and presentation. The capture layer uses target/probe/runner capabilities;
shared analysis has no vendor-name branches. One session coordinator owns probe,
serial and debugger lifecycles and publishes cancellation/disconnect/reset events.
All view updates carry session/target identity so late replies cannot update a
different board. Offline bundles must not require the device to be attached.

CPU charts should reconcile thread-exclusive execution + ISR + idle + unknown
against wall time for each core, within the declared clock tolerance. Define
whether an interrupt that wakes an idle CPU counts as busy. For SMP, define both
per-core utilisation and aggregate capacity; do not silently sum percentages
from multiple cores into a single 0-100% chart. CPU sampling/profiling is a
separate metric from scheduler-derived utilisation.

UIA-style views are requirements for the Zephyr event model. Importing existing
TI-RTOS UIA recordings would be a separate, versioned format/RTOS adapter after
format and licence review; CTF support does not itself decode UIA packets.

## Delivery slices and release gates

1. Automatic capture import/opening, explicit capability guidance and acquisition
   provenance. Keep the existing successful RAM snapshot path as a regression.
2. Application console with UART reception, saved logs, reset/reconnect handling
   and clear target-time versus receive-time labels. Add structured Zephyr log
   decoding and correlated markers without requiring replacement of printf.
3. Read-only core register snapshots from the existing debugger plus a fault
   investigation view, initially Cortex-M0+ and Cortex-M4. Add offline Zephyr
   core-dump import for supported target/version combinations.
4. Correct CPU/task/ISR charts and state/latency analysis, then duration/value
   plots, peripheral SVD snapshots/diffs and architecture/transport expansion.
5. Object/heap/stack analysis, long captures, baseline comparisons and regression
   reports. Finish independent hardware and comparative performance validation
   before making stable or superiority claims.

The reference test application needs deterministic worker/preemption behaviour,
named messages/counters, a known interrupt workload and a controlled fault case
in a separate, explicitly selected test firmware. Never insert deliberate faults
into an existing user's application. Check the correct saved PC/source/stack,
validity flags, missing registers on M0+, invalid frames, ELF mismatch and dump
truncation. Extend with at least one RISC-V/Xtensa exception case before claiming
architecture-neutral fault support.

Test UART/RTT ownership, unplug/reconnect, flood/drop handling and timestamp drift;
test simultaneous debug/console use, changing targets and cancellation. CPU
fixtures need idle-only, known busy ratios, nested ISR, ready/sleep/timeout,
capture boundaries/loss and SMP. Register fixtures need byte order, widths,
read-side effects, failed reads and late responses. Benchmark setup time,
measurement error, instrumentation cost, storage and UI responsiveness against
the same hardware/workload in the comparison tools.

## Primary references

- [Zephyr core dumps](https://docs.zephyrproject.org/latest/services/debugging/coredump.html)
- [Zephyr logging, printk integration and backend behaviour](https://docs.zephyrproject.org/latest/services/logging/index.html)
- [Zephyr tracing hooks, formats and transports](https://docs.zephyrproject.org/latest/services/tracing/index.html)
- [Arm CMSIS core register mapping and core differences](https://arm-software.github.io/CMSIS_6/latest/Core/regMap_pg.html)
- [TI UIA LoggingSetup: load, execution and application event collection](https://downloads.ti.com/dsps/dsps_public_sw/sdo_sb/targetcontent/uia/2_00_01_34/exports/docs/docs/cdoc/ti/uia/sysbios/LoggingSetup.html)

These references describe available mechanisms, not proof that every capability
exists in the project's pinned Zephyr checkout. Implementation must inspect and
test that exact version before offering configuration or claiming support.
