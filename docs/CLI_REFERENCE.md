<!-- SPDX-License-Identifier: Apache-2.0 -->
# CLI contract — Phase-0 prototype, schema 0.1.0

Python 3.10+ standard library; offline, read-only; no rebuild or target operation.

    python zew.py graph --build /path/to/west/build
    python zew.py diagnose --build /path/to/west/build
    python zew.py why CONFIG_GPIO --build /path/to/west/build
    python zew.py graph --build /path/to/sysbuild --image app=app --image mcuboot=mcuboot

All successful commands emit deterministic UTF-8 sorted JSON on stdout and return exit 0. This indicates successful observation, **not a clean build**. Invalid input and file errors emit JSON on stderr with exit 2. Argparse help/usage also exits with 2 for invalid commands.

Artifact paths are allowlisted and relative to build root; symlinked paths are rejected, SHA-256 is computed with bounded memory, oversized files are rejected. No timestamps, absolute host paths, raw DTS, arbitrary Kconfig strings or ELF bytes are emitted.

A Kconfig output shows an observed generated value, but dependency_reason and why_enabled are **unknown**. DTS is not parsed and no driver inclusion reason is fabricated. Artifact hashes identify bytes but do not prove a physical device was flashed. Build/provenance tool versions remain unknown until extracted from trusted tool metadata. Sysbuild image paths are explicit, not auto-discovered. R1 is not complete.
