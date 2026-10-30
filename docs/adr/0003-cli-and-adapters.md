<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0003 — Headless CLI and adapter boundaries
Status: provisional (2026-10-30).

Headless stdlib Python CLI is separate from Eclipse Java/OSGi. Outputs sorted JSON, documented exit codes and explicit unknown states. Semantic adapters must use the selected checkout's kconfiglib/edtlib, CMake File API and Zephyr SPDX. Architecture, vendor and runner adapters declare exact capabilities; no Cortex-M fault decoding for RISC-V/Xtensa. Avoid shell interpolation, symlink following and unbounded deserialization. Eclipse integration must reuse finding identifiers and show equivalent CLI call. It is not implemented yet.
