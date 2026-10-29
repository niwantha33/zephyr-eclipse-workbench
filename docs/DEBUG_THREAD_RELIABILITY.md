# Zephyr thread discovery and debugger console reliability

The supplied screenshot shows OpenOCD reading registers for several distinct
Zephyr thread addresses, while Eclipse displays `Thread #0`, `Suspended:
Container` and blank stack rows. The INFO messages are normal polling output;
they are not evidence of a programming failure. The screenshot alone cannot
establish every cause of a blank stack view.

## Thread discovery

The previous launch left CDT's `UPDATE_THREADLIST_ON_SUSPEND` option at its
default `false`. That allows cached thread information to survive pauses when
the target does not supply thread creation events. An RTOS can create threads
after connection/reset without the notification flow expected from a desktop
GDB server.

Zephyr launches now set this option to `true` and retain all-stop debugging.
Every launch applies the setting to existing saved configurations too. CDT's
normal process, thread, stack, register and source services continue to own
the model. No synthetic thread labels, fake frames, or fabricated registers
are injected, and RTOS awareness remains enabled.

## Console output

The managed server console has a 1 MiB high watermark and 256 KiB low watermark.
Within each one-second burst, only OpenOCD's exact INFO
`Getting thread <number> reg list` records are condensed. The first sample and
number of omitted polling messages remain visible. A warning, error or any
different output flushes the count and is printed unchanged. Server readiness
and diagnostic processing still receive the original lines before this display
filter. This limits UI/document work during repetitive register polling without
discarding a failure message.

## Regression coverage

The installed Eclipse test uses actual GNU GDB and gdbserver with a small host
program. It caches the initial thread list, creates two workers after connection,
halts again, verifies native thread identities and nonblank stack frame functions,
then resumes and pauses a second time. A test-only relay withholds MI thread
creation notifications and records them so the test can prove they did not reach
CDT. The test uses the stdio MI transport used on Windows, with asynchronous
remote control enabled. It also applies the real production settings to an old
saved launch that explicitly disabled refresh.

This validates debugger integration with a live host process, not Cortex-M0+
register stacking, XDS110 behavior or physical MSPM0 execution. Hardware-specific
thread-unwind failures still require the target's GDB/error output to diagnose.

Core tests additionally exercise high-volume polling, count flushing, unchanged
errors/warnings/readiness lines and idempotent final flushing.

The real native import fixture also exposed a separate inherited Core Build
scanner fallback to desktop GCC. The West Core Build configuration now reads
only the owned, persisted Zephyr language settings, including per-file C/C++
macros, forced includes and macro files. Without generated settings it returns
an empty scanner description and does not launch a host compiler. The normal
build editor refresh supplies those settings. The index regression continues
to require actual header and C++ symbol navigation.

Primary references:

- [CDT thread cache invalidation on suspend](https://github.com/eclipse-cdt/cdt/blob/main/dsf-gdb/org.eclipse.cdt.dsf.gdb/src/org/eclipse/cdt/dsf/gdb/service/GDBProcesses_7_0.java)
- [Zephyr's documented OpenOCD thread output](https://docs.zephyrproject.org/latest/samples/philosophers/README.html)
