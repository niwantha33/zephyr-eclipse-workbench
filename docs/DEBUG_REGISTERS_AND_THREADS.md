# Registers and OpenOCD thread diagnostics

## CPU registers

Suspend the target and select a stack frame in the Debug view. Open
**Window > Show View > Other > Debug > Registers**. CDT displays the registers
reported by the architecture's GDB, including PC, SP and general registers.
This uses the current native Zephyr DSF-GDB session.

## Named SoC peripherals

Open **Window > Show View > Other > Debug > SoC Registers**. Click **Load SVD**
and choose the exact chip's vendor CMSIS-SVD file. The file is loaded locally;
Workbench does not download or guess a device definition. The selected SVD path
is restored when the view is reopened. Check its device name against your
physical chip before reading.

The native tree shows peripherals, registers, bitfields, addresses, access,
reset values and values from the selected CDT DSF-GDB debug session. Search by
peripheral, register, field or description. Select a register (or one of its
fields), halt the target, select a suspended frame in Debug, then click
**Refresh selected + watches**. **Watch / unwatch** includes a register in later
refreshes, including when filtered out of the tree. Values differing from the
SVD reset value within its reset mask are marked. An unspecified reset value
is shown as unavailable, not assumed to be zero.

Reads use the existing debug session: no second GDB connection, server launch,
firmware write, halt or resume is issued by this view. GDB handles target byte
order; the expression checks the target C type width before dereferencing an
8-, 16- or 32-bit address. Refresh drops the DSF expression cache. Reads are
serial and capped at 64 registers per refresh. Values are explicit snapshots,
not live polling, and should be refreshed after stepping or resuming. Context
changes discard values and pending responses; a new SVD or closed view cancels
subsequent requests. An already-issued read cannot be undone.

Write-only and SVD-declared read-side-effect registers (including field-level
read actions) are blocked. This is a read-only first implementation; there is
no register editor. Vendor SVDs may omit side effects, so consult the reference
manual before selecting a FIFO/status register. No peripheral address scan is
performed.

The offline parser supports inherited register properties, same-scope
`derivedFrom` peripherals/registers/fields, nested clusters, and numeric or
comma-separated register/cluster array indices. XML external entities and
DOCTYPE declarations are disabled. Files are limited to 32 MiB; array sizes,
nesting, register counts and expansion work are bounded. Cross-scope derived
references, peripheral/field arrays, non-byte address units, unaligned
registers and unsupported widths fail with an explicit diagnostic. A failed
load retains the previous device. The tree shows at most 2,000 matches at once;
narrow the search to find other registers in large devices.

Automatic board-to-SVD resolution, remote SVD catalogs, register writes,
polling and history plots are not included. CPU Registers remains the standard
CDT view. Parser and existing core tests run in `scripts/test_core.py`; these
checks do not establish probe or physical peripheral correctness. Validate on
hardware with a safe, documented read-only register and compare the same
address in GDB.

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

### Flash completed but target metadata reads `0xFF`

An observed report was `_kernel_thread_info_size_t_size` at `0x5e68`, ELF byte
`4`, target byte `255`, despite a reported successful Flash. This proves a
readback mismatch at that point, not its physical cause. Missing/stale image
data and probe/read behavior still need to be distinguished from the actual
Flash console output.

The Flash path previously left OpenOCD readback verification optional and
could use the executable cached in `runners.yaml`, while Debug overrode it with
the shared selected tool. Flash now uses that same selected OpenOCD executable
and matching script tree and always requests `--verify`. Other flash backends
keep their own tool selection.

The native Flash launch also waits for the programmer process to finish and
propagates nonzero exit status as a launch failure. Starting the process no
longer completes the Flash job. Cancellation remains attached to the owned
programmer process and its lifecycle watcher.

For Cortex-M XIP builds with thread metadata, the bundled validator resolves
the board-declared image, checks Intel HEX checksums/address records, and
requires all expected thread metadata bytes to be present. Merged HEX images
remain selected; no replacement HEX image is fabricated. ELF and explicitly
addressed BIN contracts are checked separately. The exact validated image and
format are passed to West and logged beside the debug ELF path.

After image verification, OpenOCD also reads and compares the metadata bytes
before its normal reset/run. A missing or mismatched byte raises an OpenOCD
error and fails Flash, rather than leaving it to be discovered at Debug. This
check performs no separate erase, RAM patch, or metadata write. Programming
continues to use the board runner after the normal Flash confirmation. Debug
still performs its independent non-programming check.

Regression checks include the reported `0x5e68`/`0xFF` mismatch, missing HEX
records, checksum failures, extended addresses, declared merged images,
alternative runner preservation, BIN load addresses and Tcl error propagation.
Installed Eclipse tests check compiler/objcopy-generated HEX images against
CDT-extracted ELF32/ELF64 metadata. Physical programming remains unverified in
the development container.

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

## Native debugging data and peripheral views

Use **Zephyr > Show Debug Data / SoC Peripherals** to open native Variables,
Expressions and CPU Registers alongside **SoC Registers**. A successful Zephyr
launch opens these views and initially activates CPU Registers. Select a suspended
stack frame to inspect its locals; globals can be added explicitly in Expressions.
Values may be unavailable for optimised code, missing symbols or a running target.

A separately installed view titled **Peripherals** is not the Workbench SVD view
and may require a different launch integration. Use **SoC Registers > Load SVD…**
and choose the exact physical chip's vendor file. Loading a model does not prove
it matches the device. Peripheral reads remain explicit and honour the SVD access
restrictions; the tool does not infer package/register definitions from a board name.

Routine OpenOCD `Getting thread … reg list` polling is hidden after one explanatory
message. A pending count is printed when a real diagnostic arrives or output ends;
there is no timer-driven polling spam. Errors, warnings, connection events and
unrelated output are preserved. The debug-server console is not automatically an
application UART or RTT console: firmware logging must use its configured transport.

These software changes require native Eclipse smoke and named-hardware acceptance.
Do not infer breakpoint hits or physical register correctness from packaging success.

### Source breakpoint checks

Native launches now install bidirectional CDT/GDB source mappings for build aliases
recorded in CMakeCache (`APPLICATION_SOURCE_DIR`, `CMAKE_HOME_DIRECTORY`,
`ZEPHYR_BASE`) only when both paths identify the same existing directory. Existing
explicit mappings are preserved. This addresses builds compiled through verified
junction/symlink aliases while the editor opens the original application path.
Unrelated paths are not guessed or substituted.

The startup console reports enabled/disabled C breakpoints and warns when Eclipse
**Run > Skip All Breakpoints** is enabled, without changing that user setting.
Installed counts can include another active target and may still be pending at
startup. Inspect the native Breakpoints view for insertion errors, conditions and
hit counts. Hardware breakpoint limits, optimised/non-executable lines and a
mismatched flashed image remain separate possible causes.

For the blinky loop, set an enabled breakpoint on the executable GPIO toggle line
and Resume. Selecting `main` in the thread list shows that thread's saved frame;
it is not evidence that the target stopped because of a breakpoint. Debug loads
symbols and attaches; it does not flash. Confirm that the device contains the
matching build. Globals such as `led` can be entered in native Expressions;
CPU Registers shows actual register data for the selected suspended context.

Automated remote-debug smoke now installs a workspace C line breakpoint through
Eclipse's breakpoint manager, verifies installation and a hit with a compiler
source alias, and checks global Skip All state preservation. The fixture uses a
host GDB server, not an MSPM0 probe or physical hardware.

PR p2 artifacts use a current UTC qualifier so the tested archive can be installed
as a newer preview rather than the old fixed October-25 smoke-test version. This
does not publish or change the normal Workbench update channel. Use the artifact
from the successful exact-head package job; install it through **Help > Install
New Software > Add > Archive**, then restart Eclipse before retesting Debug.

### Exact debug ELF and halted preflight

Flash metadata validation and native Debug now resolve `config.elf_file` from
this build's generated `zephyr/runners.yaml`. They no longer choose the first
ELF from an unordered set of mandatory outputs, which can also contain signed
ELF byproducts. A missing, ambiguous, external or invalid ELF declaration fails
explicitly instead of guessing another image.

Before the native Debug comparison, the owned Tcl connection waits up to five
seconds for the runner's halt to complete (`wait_halt 5000`). Listening-port
readiness alone is not a halted-target guarantee. This wait does not issue a
new reset, halt, flash or memory write. Failure to confirm halt is reported as
a startup/access failure, rather than a firmware metadata mismatch.

On a valid read that differs from the ELF, the checker reads the bounded
metadata regions once more and reports whether both snapshots agree. A later
matching snapshot does not bypass the failure. All initially read tables and
the first differing address remain in the diagnostic. For example,
`0x4ea8 expected byte 4, target byte 15` remains an observed mismatch, not proof
that a specific OpenOCD defect or old firmware caused it. Stable wrong reads
can still be a probe problem. No metadata is patched to satisfy the decoder.

Hardware acceptance: install the tested preview, terminate the old session,
Flash the exact selected build and require its image/metadata verification to
succeed, then Debug. Record the selected ELF, image and tool paths. If the
mismatch persists, retain the full readback diagnostic and Flash/OpenOCD logs.
The automated regressions cover multiple ELF outputs, the reported 4-versus-15
case, stable/inconsistent reads, failed repeat reads and halt failure using an
emulated Tcl peer; they do not establish MSPM0 hardware correctness.
