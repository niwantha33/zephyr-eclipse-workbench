# Registers and OpenOCD thread diagnostics

## CPU registers

Suspend the target and select a stack frame in the Debug view. Open
**Window > Show View > Other > Debug > Registers**. CDT displays the registers
reported by the architecture's GDB, including PC, SP and general registers.
This uses the current native Zephyr DSF-GDB session.

## Named SoC peripherals

CPU registers and memory-mapped peripheral registers are different views.
The current Zephyr launch does **not** connect a vendor SVD to a peripheral
decoder. An empty Peripherals view is not evidence that the probe failed.
Do not enter an SVD path in the Disassembly view's location field.

Named GPIO, UART, timer and other peripheral registers require the exact
device's vendor SVD plus an SVD-capable debugger integration. Eclipse Embedded
CDT's debug launchers offer an **SVD Path** tab; those are separate launch
types, not a tab currently supplied by this Workbench's **Zephyr Application**
launcher. Installing an SVD alone does not wire that integration into it.

For a specific documented address, the standard **Memory** view can inspect
target memory through this session. Consult the device reference manual first:
reads can clear flags or consume FIFO data. Never scan all peripheral addresses
to discover registers. No device definitions or peripheral reads are fabricated
by this Workbench.

## Repeated `Only size_t of 4 bytes are supported`

OpenOCD's Zephyr RTOS decoder reads the byte at the ELF symbol
`_kernel_thread_info_size_t_size` (older builds use its
`_kernel_openocd_size_t_size` alias) from the physical target and rejects values other
than 4. The message does not establish that the MSPM0's C ABI has a 64-bit
`size_t`. Possible causes include an ELF that differs from the flashed image,
unavailable/uninitialized metadata, target memory access, or incompatible
OpenOCD/Zephyr versions. The connection can still support single-core source
debugging while Zephyr thread enumeration fails.

1. Terminate Debug and verify the selected board and build configuration.
2. Check that this build's `zephyr/.config` enables
   `CONFIG_DEBUG_THREAD_INFO=y` (and the selected port's OpenOCD support).
3. Deliberately flash that same build if the on-board image is different, then
   launch Debug with its matching `zephyr.elf`.
4. If the error persists, check OpenOCD compatibility and inspect the value at
   `_kernel_openocd_size_t_size` in GDB while halted. A correct value on a
   32-bit supported target is 4. Do not patch the ELF or firmware metadata to
   conceal the error.

The native OpenOCD launch now reads static metadata from the exact launch ELF
using CDT's ELF reader. When `CONFIG_DEBUG_THREAD_INFO=y`, it verifies the
size byte, count and offset table through the owned OpenOCD Tcl endpoint before
GDB attaches. These are only the bounded addresses of the ELF's allocated,
read-only symbols, not a scan of RAM or peripheral registers. A mismatch stops
the launch and reports the first differing address, expected byte and observed
target byte. It does not proceed with invalid thread offsets, disable RTOS
awareness, write metadata into RAM, or automatically flash. An unreadable Tcl
endpoint or malformed/missing ELF metadata is also a launch error.

For a mismatch, explicitly Flash the selected exact build and retry Debug. A
successful metadata comparison verifies only that table, not the entire
firmware image. If comparison succeeds and OpenOCD still fails its RTOS
decoder after symbol negotiation, capture that output and investigate the
OpenOCD/GDB compatibility; the metadata comparison is not a hardware thread
enumeration test.

An initial `No symbols for Zephyr` before GDB connects can occur because West
enables RTOS decoding before GDB supplies the symbol table. It does not by
itself indicate a missing ELF. Raw OpenOCD messages remain visible.

Native OpenOCD launches with GDB 7.12 or newer now use `extended-remote` via
CDT's command factory, preserving its normal DSF startup, breakpoint, variable,
and stack services. Older GDB and other runners retain CDT's default services.
The connection-mode change does not establish the cause of a metadata mismatch.

`Data region NOT available!` comes from OpenOCD's MSPM0 flash driver when the
optional data-flash bank has zero size. It does not mean SRAM or the Zephyr
thread metadata is missing, and is not treated as a fatal launch error.

## `rtsflash` / missing Python `usb`

When **OpenOCD** is the selected runner and subsequently connects to the
probe, the import warning from the unused `rtsflash` runner is separate.
Install `pyusb` in the same Python environment used by West to resolve that
import if desired. Installing it does not repair Zephyr RTOS metadata or add
an SVD decoder.

## Primary references

- [OpenOCD Zephyr decoder](https://www.openocd.org/doc/doxygen/html/zephyr_8c_source.html)
- [Eclipse Embedded CDT peripherals](https://eclipse-embed-cdt.github.io/debug/peripheral-registers/)
- [Zephyr debugging and SVD configuration](https://docs.zephyrproject.org/latest/develop/debug/index.html)

No physical register or thread inspection was performed in the development
container. Tests exercise a real socket with an emulated Tcl peer for matching,
mismatched and malformed responses; installed Eclipse tests extract metadata
from compiler-generated ELF32/ELF64 files and check the actual DSF command.
These tests do not establish successful RTOS enumeration on hardware.
