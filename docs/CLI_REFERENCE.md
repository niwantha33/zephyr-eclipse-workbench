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


## Build comparison and portable offline evidence (feasibility)

```sh
python zew.py compare --left /path/to/working/build --right /path/to/failing/build
python zew.py bundle --build /path/to/working/build
python zew.py bundle --build /path/to/working/build --output ./evidence.zip --write
python zew.py inspect --input ./evidence.zip
```

`compare` reports only changed observed generated Kconfig values and byte-level
artifact digests; it explicitly does NOT infer root cause, semantic devicetree
changes, firmware identity on the device, or memory attribution. Redacted
configuration values are **not compared**. Each command outputs stable JSON.

`bundle` defaults to a preview, performs no write unless `--write` is
explicit, and refuses to overwrite an existing ZIP. Package content is limited
to a sanitized graph and integrity manifest. Raw ELF, DTS, source, private
configuration strings and logs are never included. `inspect` validates SHA-256
and hardens archive processing without extracting anything, but **does not
authenticate a producer**. Bundle format `zew-evidence-0.1`, two allowlisted
members, deterministic timestamps, bounded size. Review exported data before
sharing: even permitted board names and public metadata may be sensitive.

These are still **Phase-0 feasibility features**: no rule-causality engine,
automatic Zephyr semantic evaluation, independent hardware measurements,
production security certification or G1 gate.
