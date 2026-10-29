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
`_kernel_openocd_size_t_size` from the physical target and rejects values other
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

An initial `No symbols for Zephyr` can occur during GDB symbol negotiation;
repeated failures after attachment require investigation. Workbench reports
the repeated size error once with these checks and preserves the raw output.
It does not automatically flash, reset again, or change the RTOS configuration.

The `target extended-remote` suggestion is an OpenOCD recommendation about
the connection mode; it does not explain missing peripheral definitions or
establish the cause of the thread metadata error. The current native CDT
launch controls its remote connection sequence.

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
container. The regression tests exercise diagnostic classification, not probe
or peripheral behavior.
