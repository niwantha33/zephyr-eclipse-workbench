# RTOS analysis: build order and acceptance criteria

Our goal is a free, open-source, commercially usable Zephyr workflow with less
manual setup and measurements engineers can trust. Competitive superiority is
an engineering target, not a result established by the current prototype.

The [expanded observability specification](RTOS_OBSERVABILITY_SPEC.md) adds
UIA-style application events and CPU/task charts, printf/log reception, register
snapshots and peripheral decoding, and architecture-aware fault/core-dump
investigation. It defines a shared session model, integration boundaries and
acceptance tests. These are proposed features, not capabilities of 0.1.21.

## Current evidence

CTF RAM snapshot capture and the custom Trace Compass scheduling timeline have
been observed on TI MSPM0. Capture uses an OpenOCD server and the selected build's
GDB, including SDK GDB builds without embedded Python. Six Windows firmware
build scenarios pass; those builds do not validate trace capture on six targets.

The current timeline has scheduled/not-scheduled thread intervals and a
non-nested ISR lane. It does not yet offer exclusive CPU usage, complete thread
states, nested interrupt accounting, capture-loss detection or automatic opening.

## Implementation order

| Order | Deliverable | Acceptance gate |
|---|---|---|
| 1 | One capture-to-view workflow | Select the new capture, register its XML analysis, open the trace and timeline automatically in the installed viewer. Missing Trace Compass gets a short installation guide; cancellation and probe cleanup work. |
| 2 | Capture quality and identity | Record build/ELF/metadata identity, target/probe/runner, byte count and capacity, clock and time range. Detect demonstrable full/truncated buffers and unsupported layouts. Report unknown or unverified facts explicitly. |
| 3 | Application messages and events | Receive supported printf/printk/Zephyr log output; add searchable messages, structured markers, durations and value plots. State when target timestamps or thread identity are missing. Keep console text and binary trace framing separate. |
| 4 | Registers and fault investigation | Reuse the Eclipse debug session for core PC/SP/LR or architecture equivalents, then selective SVD-backed peripherals. Add validated saved exception context, status decoding and matching-ELF core dumps. Respect CPU differences and read-side effects; never reset to collect crash evidence. |
| 5 | Thread state and CPU charts | Running, ready, blocked and sleeping where events prove them; unknown otherwise. Chart per-core busy/idle, thread-exclusive and ISR time over a stated window. Account for nested interrupts, trace boundaries, loss and thread-ID reuse. |
| 6 | Timing and deadline tools | Ready-to-run latency, preemption, execution bursts, period/jitter and user-specified deadlines. Link each metric to its events and selected interval. Label maxima as observed maxima, not proven WCET. |
| 7 | Synchronisation and memory | Mutex/semaphore/queue waits, object ownership and contention; priority-inversion evidence; stack high-water marks and allocation/free lifetimes where instrumentation supplies them. Missing events disable a metric with guidance. |
| 8 | Long captures and live views | Add supported RTT/other transports and streaming behind capability checks. Track drops, wrap, clock conversion and disconnects. Bound storage, decode cost and target overhead. Keep RAM snapshots for small targets. |
| 9 | Release regression reports | Save baseline captures, compare metrics, export CSV/HTML and apply user-defined CI thresholds. Run deterministic trace fixtures plus cross-vendor hardware checks before publishing supported capabilities. |

## Architecture shared by vendors

Keep four interfaces separate: capture transport, Zephyr/version event decoder,
analysis engine, and Eclipse/Trace Compass presentation. Target-specific adapters
discover memory layout and runner commands; analysis should not branch on vendor
names. Version the normalised events and result schema so saved captures remain
readable as views improve.

Build a capability table for each exact target/core, probe, runner and transport.
An Arm Cortex-M0+ target cannot be promised the same hardware tracing features
as a larger Cortex-M or Xtensa/RISC-V target. RAM access, UART, RTT, SWO and
instruction tracing are distinct capabilities and must be tested independently.
OpenOCD support does not imply J-Link, pyOCD or another GDB server accepts the
same monitor commands. Add adapters and tests before enabling each one.

Derive prerequisites from the selected Zephyr checkout and effective build
configuration. Offer a small configuration fragment with a clear RAM/overhead
estimate; do not silently rewrite the application's configuration. Preserve
existing tools, project source and firmware unless the user requests an action.

## Measurement contract

Every view should show capture duration, clock basis, known losses, supported
events and whether recording boundaries make a result partial. Do not infer
complete states from switch-in/switch-out events alone. Distinguish requested
sleep from observed sleep and ready time. Use explicit per-core and nested ISR
accounting. Missing starts/ends, timestamp wrap and corrupt packets must produce
visible gaps or invalid results rather than plausible-looking numbers.

Use synthetic traces with known answers for sleep/wake, higher-priority
preemption, time slicing, nested ISR, mutex contention, timeout, thread creation
and reuse, loss, wrap and SMP. Validate selected measurements against independent
hardware timing where available. Measure instrumentation overhead and the
on-target RAM/flash cost with tracing on and off.

## How to judge improvement against other tools

Use the same application, workload, board, clock and measurement interval.
Compare setup steps, time to a useful view, measurement error, loss reporting,
RAM/flash/CPU overhead, supported capture duration and reproducibility. Record
tool versions and licence scope. Publish limitations alongside results.

Tracealyzer's Zephyr offering already advertises connected task, CPU, memory and
timing views. Matching the number of charts is not enough: our first opportunity
is simple setup across supported vendors, transparent data quality, a reusable
open format and repeatable analysis without mandatory commercial tool fees.

References:

- [Zephyr tracing hooks, formats and backends](https://docs.zephyrproject.org/latest/services/tracing/index.html)
- [Trace Compass downloads and Eclipse plug-ins](https://eclipse.dev/tracecompass/download.html)
- [Trace Compass data-driven analysis](https://help.eclipse.org/latest/topic/org.eclipse.tracecompass.doc.user/doc/Data-driven-analysis.html)
- [Tracealyzer for Zephyr](https://percepio.com/tracealyzer/tracealyzer-for-zephyr/)
