# Build configurations and RTOS trace preview

Project Properties > Zephyr now offers Application settings, Debug (-Og) and
Release (-O2). These are Workbench selections, not CDT native managed-build
configurations. The default retains the old build path and application Kconfig.
Debug and Release have separate target-specific build folders and pass Zephyr's
`CONFIG_DEBUG_OPTIMIZATIONS` and `CONFIG_SPEED_OPTIMIZATIONS` respectively.
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

This preview does not verify target firmware/ELF identity or buffer fullness,
and does not auto-flash, auto-configure, auto-import the trace, provide rich
state analysis or support every debug runner. It only supports board-declared
OpenOCD and a GDB with MI support (embedded Python is not required).
Non-OpenOCD runners receive guidance, not a fabricated claim of support. Probe, memory and transport support varies
by target; Windows and physical target validation remain outstanding.
