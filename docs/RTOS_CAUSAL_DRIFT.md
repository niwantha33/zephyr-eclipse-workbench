<!-- SPDX-License-Identifier: Apache-2.0 -->
# RTOS causal drift: first offline slice

**Status: saved-CTF offline CLI and native Eclipse table preview. Not a live view or a qualified production detector.**
No firmware is injected and no gates are instrumented. The existing live trace
menu stays disabled.

## Cycle boundaries are not context switches

A periodic task can be switched in multiple times during one logical cycle.
The analyzer therefore requires application-defined Zephyr CTF named events,
rather than interpreting switch-ins as new activations.

The supported Zephyr 4.4 named event has a bounded name and two uint32 words.
Record these exact marker names, arg0=application task tag, arg1=cycle sequence:

| Name | Meaning |
| --- | --- |
| zew_cycle_release | Periodic release was observed, e.g. in timer callback |
| zew_cycle_start | Application started work on that cycle |
| zew_cycle_finish | Application completed work on that cycle |

Example instrumentation sketch (not a complete compilable application):

~~~c
#include <zephyr/tracing/tracing.h>
#define ZEW_TASK_TAG 7U

/* At periodic release; hand off the same sequence to the worker: */
sys_trace_named_event("zew_cycle_release", ZEW_TASK_TAG, sequence);

/* In the worker thread: */
sys_trace_named_event("zew_cycle_start", ZEW_TASK_TAG, sequence);
/* one logical unit of work */
sys_trace_named_event("zew_cycle_finish", ZEW_TASK_TAG, sequence);
~~~

Release and start must refer to the same activation, with a race-free sequence
handoff. The release marker must represent the *actual* schedule release point.
A marker in a task after it wakes cannot prove it was released on time.

## Run on a matching saved trace

From the repository root with the managed Python interpreter:

~~~powershell
python plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/offline_trace_json.py --mode causal_drift --ctf .zew-traces/trace-example/ctf --tag 7 --period-us 10000 --thread-id 0x20001234 --anchor-ns 1000000000 --deadline-us 8000
~~~

Replace the sample trace path, tag, thread ID, period and anchor with the
actual measured values. The analyzer never reads a running board.

- --tag is the application-defined 32-bit marker tag, **not** a TCB address.
- --period-us is the intended interval, provided by the engineer.
- --thread-id is optional and must match an observed CTF scheduler thread ID
  to report interruptions and paired mutex blocks. It is not inferred
  from the application tag.
- --anchor-ns is an optional **expected** timestamp for sequence zero using
  the same CTF clock. Without an anchor, relative_phase_ns measures deviation
  from the *first observed start*, not absolute start lateness.
- --deadline-us is optional. A deadline miss is only reported with an observed
  finish and a release marker or explicit anchor. A release marker defines the
  deadline reference when it exists.

The JSON report includes bounded observed cycles, phase trend, observed switch
interruptions, intervening threads, release-to-start latency, and mutex
blocking durations only for matching explicit blocking/acquired pairs.
Scheduler interruptions are NOT proof of a higher-priority preemption;
the graph/priority attribution requires a later evidence-qualified phase.

A trace with loss, invalid ordering, duplicate phase, missing start or
cross-stream clocks does not produce fabricated drift numbers. Unpaired mutex
blocks are not timed. GateHwi, GateSwi, queue depth, ISR identity and automatic
root cause attribution are NOT available.

## Run tests

~~~sh
python -m unittest discover -s tests/tools -p test_causal_drift.py -v
~~~

## Remaining phases

1. Native Eclipse Task Drift view (tag, thread, period, deadline inputs).
2. Proven scheduler priority and ISR interference breakdown.
3. Opt-in gate/interrupt-lock instrumentation and causal dependency graph.
4. LP-MSPM0G3507 validation, trace loss receipts and tracing overhead study.
