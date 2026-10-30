<!-- SPDX-License-Identifier: Apache-2.0 -->
# Offline fatal-log investigation

Open **Zephyr > Investigate Fatal Log…**, or **Window > Show View > Other > Debug > Zephyr Fault Investigation**.
No imported project, debug probe or connected hardware is required.

1. Open a UTF-8 `.log` or `.txt` excerpt containing one failure (up to 4 MiB).
2. Select the Cortex-M architecture from your board/SoC documentation. The default
   **Unspecified / other architecture** extracts supported labels but disables
   architecture-specific decoding. Never choose Cortex-M for an ESP32 Xtensa/RISC-V dump.
3. Inspect captured registers, their input line numbers and evidence-backed findings.
4. Optionally choose **Resolve PC / LR…**. Select the exact ARM ELF for that failure,
   explicitly confirm that you checked the match, and select a trusted toolchain's
   `addr2line` executable (for example `arm-zephyr-eabi-addr2line`).
5. Double-click a resolved source location to open an existing absolute local file.
   Workspace files open at the recorded line; external files open with their recorded
   line shown in the status. Source paths are not guessed or silently remapped.
6. **Export report…** saves the current text report. Review paths, function names and
   captured register values before sharing it.

## What is supported

- Zephyr's printed `r0/a1`, `r12/ip`, `r14/lr`, `r15/pc`, `xpsr` format and explicit
  hexadecimal PC/LR/SP/MSP/PSP/CFSR/HFSR/BFAR/MMFAR labels.
- ARMv7-M/ARMv7E-M configurable fault bits and relevant HFSR bits; ARMv8-M Mainline
  additionally decodes CFSR.STKOF. ARMv6-M does not use this configurable-fault decoding.
  Individual processor implementations may not implement every optional feature.
- BFAR/MMFAR validity gating: an address without its valid bit is not labelled a
  valid fault address. Missing status and stack pointers remain missing evidence.
- Rejection of files with multiple `ZEPHYR FATAL ERROR` summaries; conflicting
  register values are excluded rather than combined across failures.
- Offline PC/LR lookup in ELF32 ARM images. The Thumb bit is cleared for lookup.
  Exception-return LR tokens are excluded. LR is not represented as an unwound caller.
- Log SHA-256 provenance, selected ELF SHA-256, and ELF mutation checks around lookup.
  Symbol lookup has a bounded process timeout and supports cancellation.

## Important limits

This is a **fatal text-log analyser**, not a core-dump debugger, a stack unwinder or
an automatic root-cause detector. Printed labels do not prove that a frame was valid.
Fault status bits may be sticky. In particular an imprecise bus fault does not mean
that the printed PC identifies the failing memory access.

A checksum identifies the selected file; it does **not** establish that a log belongs
to that ELF. Matching is explicitly **user-confirmed**, not independently verified.
The view checks the ELF32 ARM header to reject other architectures, but does not
infer which Cortex-M CPU produced the log. Source contents are not verified against
the build. Missing symbols produce unresolved locations, not invented source lines.

Binary Zephyr core dumps and memory/thread reconstruction still require the matching
Zephyr checkout's `scripts/coredump` parser and GDB server. A `#CD:BEGIN#` payload in a
text log is recognised as a prerequisite for that workflow, not decoded by this view.
See [Zephyr core-dump documentation](https://docs.zephyrproject.org/latest/services/debugging/coredump.html).

## Reproducible first-use fixture

Open `examples/fault_investigation/synthetic-cortex-m4.txt` and select
**Cortex-M ARMv7-M/ARMv7E-M (M3/M4/M7)**. Expected findings include PRECISERR, FORCED
and valid BFAR. PC should be `0x08000104`; LR should be `0x08000109`.

This fixture is intentionally synthetic, not hardware evidence, and has no matching
ELF. Do not confirm an arbitrary firmware image for it. To test symbol navigation,
use your own named board's captured failure and matching unstripped debug ELF.

Automated `FaultLogAnalysisTests` cover parsing, architecture gating, partial data,
validity bits, malformed/conflicting/multiple captures, limits and ELF checks.
Native rendering, actual toolchain source lookup and hardware capture still require
CI/UI and named-hardware acceptance; passing core tests does not establish these.

References used for format and bit positions:

- [Zephyr fatal errors](https://docs.zephyrproject.org/latest/kernel/services/other/fatal.html)
- [Arm CMSIS core headers](https://github.com/ARM-software/CMSIS_6/tree/main/CMSIS/Core/Include)
