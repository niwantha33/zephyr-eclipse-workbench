# User-defined Zephyr CTF tracepoints (offline preview)

The **Zephyr Tracepoint Plot** Eclipse view reads application samples from a *saved*
Zephyr CTF capture. It never connects to a live target or changes application
source files. It uses the existing RAM CTF capture workflow in the workbench.

## 1. Declare tracepoints in your Zephyr application project

Create **zephyr-tracepoints.properties** in the application project root,
alongside its `CMakeLists.txt` / `prj.conf`:

```properties
# One NAME = Display label | type entry per line
motor_rpm = Motor speed (RPM) | integer
motor_temp = Motor temperature (C) | float
```

* Names are case-sensitive ASCII identifiers, **at most 19 characters**, starting
  with a letter. The Zephyr CTF `named_event` payload has a 20-byte name field,
  including its terminating zero. Longer names would be silently truncated by
  Zephyr, so the workbench rejects them.
* Types must be exactly `integer` (signed int32) or `float` (IEEE-754 float32).
  Labels are for display only. Each non-comment line defines exactly one entry.
* If the file does not exist, the feature is **inactive**. If it is malformed,
  the view shows the **line-numbered error** and does not attempt to interpret
  application samples.
* Names are not inferred. Only explicitly declared names become `VALUE_SAMPLE`.

## 2. Emit the samples in firmware (you add these calls yourself)

Zephyr already supplies `sys_trace_named_event(name, arg0, arg1)`. The first
parameter is an application name, and the arguments are **32-bit values**.
With Zephyr's CTF format, the wire event is always **`named_event`**, whose
`name` payload contains `motor_rpm` or `motor_temp`. It is **not** a distinct
CTF event type called `motor_rpm`.

```c
#include <stdint.h>
#include <string.h>
#include <zephyr/tracing/tracing.h>

static void trace_motor(int32_t rpm, float temperature_c)
{
    /* arg0: signed int32 bits; arg1: reserved, zero. */
    sys_trace_named_event("motor_rpm", (uint32_t)rpm, 0U);

    /* arg0: IEEE-754 float32 bits, not a numeric cast to uint32. */
    _Static_assert(sizeof(float) == sizeof(uint32_t), "float must be 32-bit");
    uint32_t float_bits;
    memcpy(&float_bits, &temperature_c, sizeof(float_bits));
    sys_trace_named_event("motor_temp", float_bits, 0U);
}
```

Call `trace_motor()` from the application at the points you want sampled.
This is **documentation only**; the workbench does not inject the calls or
modify source files. Both sample types use the `arg0` bits; this preview does
not interpret `arg1`.

Enable a supported binary CTF capture backend in `prj.conf`, for example:

```ini
CONFIG_TRACING=y
CONFIG_TRACING_CTF=y
CONFIG_TRACING_BACKEND_RAM=y
CONFIG_RAM_TRACING_BUFFER_SIZE=4096
```

Check the **generated** `.config` to verify that these settings are active.
Capture CTF using the workbench's existing **Capture RTOS Trace** action and
retain the `ctf/metadata` and `ctf/channel0_0` files together. The metadata
must correspond to the firmware that generated the saved stream.

## 3. Inspect a saved trace in Eclipse

Select the imported application project in Project Explorer. Open
**Zephyr → Capture & Analyse → Zephyr Tracepoint Plot…**, then choose the saved
`ctf` folder (or its parent capture folder). The workbench loads the project
configuration and the saved CTF **only when you request it**.

The drop-down lists tracepoints **with samples**. The view currently uses a
**scrollable timestamp/value table**, not a rendered chart, with Min / Max /
Mean above it. Tracepoints declared in the properties file but absent from
the captured CTF appear as **"declared but not found in trace"**.

An absent sample is a diagnostic clue, **not proof** that the firmware code was
not compiled: a call may not have executed, tracing may have been disabled,
the trace buffer may have lost events, or the saved trace may be incomplete.
Check generated configuration, build output, and buffer-loss evidence.

## Supported Zephyr wire convention

The Zephyr CTF metadata defines a `named_event` event with fields:
`name[20]`, `arg0: uint32_t`, `arg1: uint32_t`. The shared normalizer
matches the **embedded payload name** against the configuration, then decodes
`arg0` as signed 32-bit integer or IEEE-754 float32. Unknown / malformed
numeric payloads are **preserved as UNKNOWN**, not silently ignored.

References: [Zephyr tracing guide](https://docs.zephyrproject.org/latest/services/tracing/index.html),
[Zephyr named-event API](https://docs.zephyrproject.org/latest/doxygen/html/group__subsys__tracing__apis__named.html),
[Zephyr CTF metadata](https://github.com/zephyrproject-rtos/zephyr/blob/main/subsys/tracing/ctf/tsdl/metadata).
