# Build configurations and RTOS trace preview

Project Properties > Zephyr offers **Application settings** plus all five native
Zephyr compiler-optimisation Kconfig choices. These are Workbench selections,
not CDT native managed-build configurations. Existing `default`, `debug` and
`release` IDs retain their meaning and independent build paths.

| Workbench label | ID | Zephyr Kconfig | Typical flag |
| --- | --- | --- | --- |
| Application settings | `default` | Application/board choice | Not forced |
| No optimisation (-O0) | `debug-o0` | `CONFIG_NO_OPTIMIZATIONS` | `-O0` |
| Debug (-Og) | `debug` | `CONFIG_DEBUG_OPTIMIZATIONS` | `-Og` |
| Optimise for size (-Os) | `size-os` | `CONFIG_SIZE_OPTIMIZATIONS` | `-Os` |
| Optimise for speed (-O2) | `release` | `CONFIG_SPEED_OPTIMIZATIONS` | `-O2` |
| Aggressive size (-Oz) | `size-oz` | `CONFIG_SIZE_OPTIMIZATIONS_AGGRESSIVE` | `-Oz` |

These are **optimisation policies, not logging/debug verbosity levels**.
Each non-default selection writes to a separate board-specific build folder,
passes the matching Zephyr `CONFIG_*` choice at CMake configuration and
verifies the effective generated `zephyr/.config`. On rejected/unsupported
Kconfig the build fails closed rather than reporting a false active mode.

Aggressive size (`-Oz`) can depend on the selected compiler/toolchain.
No optimisation (`-O0`) can increase code size and stack use. Select the
appropriate Zephyr checkout and toolchain; compiled output, runtime resource
usage and hardware behaviour still need separate verification.
There is no `-O3` entry: it is **not** a native choice in Zephyr's
`COMPILER_OPTIMIZATIONS` Kconfig menu.

Source: Zephyr upstream `Kconfig.zephyr` compiler-optimisation choice
and `CMakeLists.txt` optimisation dispatch.
The build verifies the effective generated `.config`. Zephyr choices and target
dependencies can override these options; in that case the build stops with a
message rather than labeling the output incorrectly. Project source files are
not modified. Select Build and Show Firmware to inspect the selected build and
`.config`. This is not a complete production release-mode security policy.

Flash continues to perform one explicit project build, and its run launch
suppresses Eclipse's separate workspace pre-launch build. Zephyr's CMake
configure-only preflight and compile are distinct steps of that same build.
If auto-build is enabled, source saving may also trigger an independent Eclipse
build. Changes to board or configuration during flash preparation cancel flash.

The experimental Zephyr > Capture RTOS Trace command starts a managed OpenOCD
server, uses the build's GDB, and invokes the bundled CTF RAM snapshot helper.
Build and explicitly flash the selected configuration first; capture resets the
board and leaves it halted. It stores the trace under `.zew-traces` in a new
folder containing a `ctf` directory and the prototype timeline XML. Import the
`ctf` directory and the XML analysis into Trace Compass. Closing an owned server
and temporary tools is best-effort; another IDE may still own the probe.

This preview does not verify target firmware/ELF identity,
and does not auto-flash, auto-configure, auto-import the trace, provide rich
state analysis or support every debug runner. It only supports board-declared
OpenOCD and a GDB with MI support (embedded Python is not required).
Non-OpenOCD runners receive guidance, not a fabricated claim of support. Probe, memory and transport support varies
by target; Windows and physical target validation remain outstanding.

## Capture integrity and measured scheduling intervals

Each capture now includes a `capture.json` receipt with trace/ELF/metadata SHA-256
hashes, exact bytes read, buffer capacity, and halted-target `buffer_full` evidence
when that backend symbol is available. A full backend can stop accepting output
*before* the byte position equals capacity: the next record may not fit. The
receipt never estimates an event-loss count from unused buffer space. A false
full flag does not prove that no events were dropped elsewhere in tracing.
Unavailable symbols are recorded as unknown, not as a successful check.

The helper optionally uses Babeltrace 2's `bt2` module in the **selected Python
interpreter** to decode the CTF. Missing `bt2` is reported as **not checked**;
decoder errors, including malformed/truncated event data, are reported as
**invalid** and no timing totals are emitted. Raw capture data is retained for
investigation. The decoder is not installed automatically. Trace Compass is
still usable independently when Python's decoder is unavailable.

For a decoded single stream with monotonic clock timestamps, the receipt lists
thread names and the sum/count of complete `thread_switched_in` /
`thread_switched_out` pairs. These are **observed scheduled intervals including
interrupt time**, not exclusive CPU time, CPU utilisation, deadlines, stack
usage, or proof of complete execution history. Boundary/inconsistent pairs are
excluded with warnings. Missing/reordered timestamps, multiple streams, or
Babeltrace-reported event/packet loss withhold all timing totals. Loss accounting
covers only information supplied by the decoder; absence of loss messages does
not prove no loss.

To recheck an existing snapshot with an interpreter containing `bt2`, run:

```sh
python plugins/io.github.niwantha33.zew.ui/tools/rtos_analysis/analyze_trace.py /path/to/capture/ctf
```

Automated tests exercise real fake-MI subprocess capture failures/full flags,
record pairing, loss and invalid/absent decoder handling. Physical capture and
Babeltrace-version/platform validation must be recorded separately; these tests
are not a hardware or complete CTF interoperability claim.
