<!-- SPDX-License-Identifier: Apache-2.0 -->
# Trace Compass + Zephyr CTF: verified snapshot workflow

This is a **standalone Trace Compass handoff**, not an installed Eclipse extension,
bundled Trace Compass distribution, automatic XML registration, live profiler, or WCET proof.

## Capture from Workbench

1. Select the exact Zephyr project, qualified board/core, and build profile.
2. Enable CONFIG_TRACING=y, CONFIG_TRACING_CTF=y,
   CONFIG_TRACING_BACKEND_RAM=y, and set CONFIG_RAM_TRACING_BUFFER_SIZE to
   an appropriate value. CONFIG_THREAD_NAME=y helps identify task names.
   Verify the effective generated .config after building. Trace instrumentation
   changes timing and consumes RAM, so measure the overhead on real hardware.
3. Build and **explicitly flash** this exact build. Workbench still cannot
   independently authenticate the image actually running on the MCU.
4. Choose **Zephyr > Capture RTOS Trace (Preview)**. It starts a supported
   OpenOCD debug server, resets, runs briefly, halts, and reads the bounded
   Zephyr RAM trace. Do not use this on a production running target.
5. Each .zew-traces/trace-* snapshot includes ctf/channel0_0, ctf/metadata,
   capture.json, diagnostics.json, report.html, and Zephyr_RTOS_Timeline.xml.
6. If standalone tracecompass is on PATH (or ZEW_TRACE_COMPASS points to its
   executable), Workbench requests: tracecompass --cli --open <ctf-folder>.
   Before the handoff, capture byte length, stream and metadata SHA-256, and
   memory-read completeness are verified. A launch request is not evidence
   that Trace Compass opened or decoded the trace.

## Manual fallback and timeline

If a standalone viewer is unavailable, use the official
[Eclipse Trace Compass download](https://eclipse.dev/tracecompass/download.html).
Import the saved ctf/ folder as Generic CTF in its Tracing perspective.
Alternatively install Trace Compass plug-ins into a compatible Eclipse.

Trace Compass supports [XML data-driven analysis](https://help.eclipse.org/latest/topic/org.eclipse.tracecompass.doc.user/doc/Data-driven-analysis.html).
Import Zephyr_RTOS_Timeline.xml via **Window > Preferences > Tracing > XML
Analyses**, then select **Zephyr scheduling > Zephyr RTOS timeline**.
The XML currently represents thread switched-in/switched-out intervals and
a non-nested ISR lane. It is *not* exclusive CPU utilization or proof of blocked,
ready, or sleeping thread states.

The bundled Python helper can run without the Eclipse UI:

~~~sh
python plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/trace_compass_bridge.py --capture PATH/TO/trace-001
# Add --open to request the viewer; --launcher PATH selects an explicit executable.
~~~

Exit 0: capture verified, viewer ready or launch requested; exit 2: standalone
viewer not found; exit 3: capture/launcher rejected. It does not execute a
shell, install anything, connect to the probe, or alter application source.
When Babeltrace Python bindings are unavailable, decoding stays *not checked*;
Trace Compass may independently validate the CTF. A decoder-invalid trace,
tampered capture, or incomplete memory read blocks the handoff.

## Build identity and evidence boundaries

Capture now records metadata_provenance and trace_build_options in capture.json.
When the generated CMakeCache.txt specifies ZEPHYR_BASE, its directory must
be the same checkout supplying the CTF metadata, **before any target reset**.
If the cache omits it, provenance is explicitly unverified. CTF data and metadata
hashes, ELF hash and effective .config hash identify files, not target flash
contents, actual timestamp accuracy, or firmware-to-ELF identity.

- RAM buffer fullness can occur before the last byte is used; do not calculate
  an invented loss count from free capacity.
- Missing/reordered timestamps, multiple streams, loss reports, and incomplete
  scheduler pairs may invalidate timing.
- CPU accounting separates observed nested ISR intervals, but an unknown gap
  is never automatically idle. A busy percentage requires explicit idle ID
  and sufficiently evidenced interval coverage.
- Maximum observed burst duration is *not* WCET. UART host timestamps are
  not synchronized to a target CTF timebase without separate evidence.
- Zephyr event records vary by revision. Save the exact build checkout's
  CTF TSDL metadata with every trace.

## Acceptance and outstanding validation

Headless tests verify failure-isolated GDB/MI capture, build-checkout
mismatch, effective config receipt fields, artifact tampering, symlink
rejection, and safe viewer argument construction.

The repository CI builds the Eclipse plug-in and runs Windows firmware
scenarios. It does **not** prove the installed viewer workflow or successful
hardware tracing across all SoCs. Outstanding: physical MSPM0 and ESP32
experiments; real Trace Compass import on Linux and Windows; overflow
experiments and loss visibility; flash identity readback; clock and overhead
calibration; nested interrupts and SMP synchronization; UI XML registration.

References: [Zephyr tracing](https://docs.zephyrproject.org/latest/services/tracing/index.html),
[Zephyr CTF sample](https://docs.zephyrproject.org/latest/samples/subsys/tracing/basic/README.html),
[Trace Compass standalone CLI](https://archive.eclipse.org/tracecompass/doc/stable/org.eclipse.tracecompass.rcp.doc.user/User-Guide.html).

For current feature status and hardware-validation claims, see the [single-source-of-truth trace analysis capabilities table](TRACE_ANALYSIS_CAPABILITIES.md). Implementation guidance here does not supersede its status.
