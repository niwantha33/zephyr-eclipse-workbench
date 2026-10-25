# ESP32-S3 native debugging: diagnosing localhost:3333 failures

## What the error means

A successful serial firmware flash does **not** establish that a JTAG
debug adapter exists. OpenOCD must connect to a supported JTAG transport
before Eclipse CDT DSF-GDB can attach to its TCP server (normally port 3333).
If West/OpenOCD exits before the endpoint is ready, no source-level session
can start.

For ESP32-S3, the SoC includes a built-in USB Serial/JTAG controller.
On a development board with a native USB/JTAG connector, connect that
connector. The UART-to-USB bridge connector may flash successfully but
does not itself give OpenOCD a JTAG interface. COM numbers are not JTAG
device identities. External JTAG probes are a separate valid configuration.

The installed Windows driver for the native JTAG interface must be
compatible with OpenOCD (Espressif's USB JTAG/WinUSB instructions).
**Do not blindly replace the serial-port driver.**

Zephyr's ESP32-S3 board documentation warns that the generic OpenOCD
from the Zephyr SDK may not support Espressif targets. Use an
Espressif-compatible OpenOCD and matching scripts, configured in the
*selected build* using CMake's OPENOCD and OPENOCD_DEFAULT_PATH.
This must be configured as a reviewed/pinned tool provider in the
managed installer before promising zero-configuration native Debug.

## New managed-tool discovery

When the chosen board identifies an ESP32 family SoC, Workbench now scans the
standard Espressif tools installation under
`%USERPROFILE%\\.espressif\\tools\\openocd-esp32\\<version>` and the
optional `IDF_TOOLS_PATH` root. It accepts a tool only if both the executable
and Espressif USB-JTAG/target scripts exist, then configures the exact project
build through `-DOPENOCD:FILEPATH=...` and `-DOPENOCD_DEFAULT_PATH:PATH=...`.
The resulting `zephyr/runners.yaml` binds West to the executable and scripts;
Eclipse does not need to modify global PATH or replace the system SDK.

Installing the Espressif Installation Manager GUI alone does not install the
OpenOCD executable. Finish an ESP-IDF tool installation in EIM with OpenOCD
selected (or use a supported pre-existing tool installation). If no complete
installation is found, native Debug fails **before spawning West**, with
instructions that explain the difference. Firmware builds and serial flashes
remain independent of the optional hardware debugger.

For a quick Windows inspection:

```powershell
Get-ChildItem "$env:USERPROFILE\\.espressif\\tools\\openocd-esp32" -Filter openocd.exe -Recurse -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName
```

This is **discovery**, not a claim that Workbench installs OpenOCD
automatically. Automatic pinned vendor-tool provisioning, WinUSB/JTAG driver
validation, and physical debugging acceptance remain separate release gates.

## Current Workbench contract

1. Project-specific build and ELF are verified.
2. Generated `runners.yaml` must name the supported `openocd` debug runner.
3. Workbench owns the launched `west debugserver --no-rebuild` process.
4. Only when its GDB port accepts connections can CDT DSF-GDB start.
5. Failure should report the actual exit status, a bounded tail of OpenOCD
   output, and classified JTAG/USB/runner guidance. Console output is retained.
6. A failed Debug startup **must not** cause fallback flash, forced firmware
   programming, board changes, or attachment to an unknown server.

## Debug readiness is not physical acceptance

Remaining hardware acceptance tests: native USB JTAG interface visibility,
Windows driver compatibility, Espressif OpenOCD executable/script selection,
concurrent debugger conflicts, exact ELF/source mapping, attach/step/breakpoints,
detach and process teardown, reconnect after board reset or cable removal.
These tests cannot be proved by compilation or an OS TCP connection alone.

Official sources:
- https://docs.zephyrproject.org/latest/boards/espressif/esp32s3_devkitc/doc/index.html
- https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/api-guides/jtag-debugging/configure-builtin-jtag.html
