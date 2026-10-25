# One shared OpenOCD manager across vendor targets

**Goal:** developers should not edit Windows PATH or duplicate tool installations
per Eclipse project. The managed Eclipse workspace stores tool selections once.
The exact board determines the correct compatible distribution during each
Zephyr build and native Debug launch.

## Architecture

```text
Zephyr > Manage Shared OpenOCD Debug Tools
  |-- General (Zephyr SDK/standard OpenOCD) ------> STM32, Nordic, NXP etc.*
  |-- Espressif patched OpenOCD ------------------> ESP32, ESP32-S3 etc.
  +-- TI-compatible OpenOCD ----------------------> TI XDS110/MSPM0 etc.*

For each selected project:
  exact Zephyr board/SoC -> tool provider -> executable + matching scripts
  -> CMake OPENOCD / OPENOCD_DEFAULT_PATH -> zephyr/runners.yaml
  -> West debugserver -> CDT DSF-GDB (owned server, exact ELF)
```

(*) Only when the selected board/probe/runner combination supports OpenOCD.
The existence of a binary does not prove hardware compatibility. Other West
runner families (pyOCD, J-Link, vendor flashers etc.) must remain separate.

## Automatic discovery

- **General:** the managed Zephyr SDK hosttools binary and scripts, or a
  complete standard OpenOCD installation under Program Files.
- **Espressif:** EIM/ESP-IDF installation under `%USERPROFILE%\.espressif\tools\openocd-esp32`,
  or `IDF_TOOLS_PATH` where defined. **EIM GUI alone does not install tools.**
- **TI:** an explicitly selected compatible TI OpenOCD distribution; no
  automatic substitution of generic OpenOCD for TI-specific targets.
- **Custom installations:** choose the appropriate `openocd.exe` once in
  `Zephyr > Manage Shared OpenOCD Debug Tools`. These settings belong to the
  Eclipse workspace and do not modify an application's source.

Every candidate must have its executable and the corresponding interface/
target script directories. Espressif also requires `esp_usb_jtag.cfg` and
`esp32s3.cfg`. A valid candidate is not a validated JTAG connection.

## Setup flow

1. Open `Zephyr > Manage Shared OpenOCD Debug Tools` and review detected
   providers. Leave paths blank for automatic discovery; use **Browse** to
   configure a reviewed custom installation.
2. For the first ESP32-S3 user with only EIM GUI installed, complete EIM's
   **ESP-IDF tools** installation with **OpenOCD for ESP32** selected. Check
   for a usable `openocd.exe` and matching scripts.
3. `Zephyr > Build Application` configures the chosen provider in CMake and
   generated `zephyr/runners.yaml` without changing global PATH.
4. `Zephyr > Debug Application` validates the selected provider and its matching
   executable/scripts, then passes them to West using `--openocd` and
   `--openocd-search` before launching CDT DSF-GDB. No auto-flash occurs.
5. After selecting or moving a compatible tool installation, Debug uses it
   immediately, including for existing builds with old tool paths. No firmware
   rebuild or runner YAML modification is required. Board identity, build
   receipt, artifacts and architecture-correct GDB checks still apply.

## Safety

- One general OpenOCD binary is **not guaranteed to support all SoCs**.
- Espressif-specific hardware never silently falls back to generic OpenOCD.
- TI-specific board families never silently fall back to generic OpenOCD.
- Missing debugger tools do not prevent ordinary Zephyr builds or serial
  flashing. Missing tools produce actionable Debug errors instead.
- Downloads, WinUSB driver changes, firmware erase and probe switching are
  never performed without explicit user approval.
- No CI unit test can replace physical checks for probe visibility, GDB
  breakpoints/stepping, safe attach/detach and reconnect after removal.

Further information:
- https://docs.zephyrproject.org/latest/develop/flash_debug/host-tools.html
- https://docs.zephyrproject.org/latest/boards/espressif/common/openocd-debugging.html

## Cross-vendor debug launch policy

Native CDT Debug and External Debug Server share the same runtime OpenOCD binding.
The generated debug runner chooses whether this policy applies: OpenOCD targets
use the board-compatible shared provider; J-Link and other runners keep their
existing configuration. Flash and emulator launches do not receive debug overrides.
Offline regression fixtures cover Espressif, STM32, Nordic, NXP, Renesas,
Microchip, Silicon Labs and TI provider selection. These fixtures do not prove
physical probe or board support.
