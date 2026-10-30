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
python zew.py inspect --input ./evidence.zip --show-graph
python zew.py compare --left-bundle ./working.zip --right-bundle ./failing.zip
```

`compare` reports only changed observed generated Kconfig values and byte-level
artifact digests; it explicitly does NOT infer root cause, semantic devicetree
changes, firmware identity on the device, or memory attribution. Redacted
configuration values are **not compared**. Each command outputs stable JSON.

`bundle` defaults to a preview, performs no write unless `--write` is
explicit, and refuses to overwrite an existing ZIP. Package content is limited
to a sanitized graph and integrity manifest. Inspection can expose that
graph for offline analysis, and comparisons can use verified bundles directly.
The imported graph is structurally validated and must retain redacted Kconfig
values even if the archive manifest has been forged. Raw ELF, DTS, source, private
configuration strings and logs are never included. `inspect` validates SHA-256
and hardens archive processing without extracting anything, but **does not
authenticate a producer**. Bundle format `zew-evidence-0.1`, two allowlisted
members, deterministic timestamps, bounded size. Review exported data before
sharing: even permitted board names and public metadata may be sensitive.

These are still **Phase-0 feasibility features**: no rule-causality engine,
automatic Zephyr semantic evaluation, independent hardware measurements,
production security certification or G1 gate.


## Verify archived artifact identities (prototype 0.1.1)

```sh
python zew.py verify --input ./evidence.zip --build /path/to/build
```

The archive is integrity-checked and structurally validated first. Each recorded
artifact is then rehashed in the explicitly selected build root, using the
snapshot's image-relative mappings. Output is deterministic JSON with expected
and observed SHA-256, provenance and `matching`, `changed` or `missing` states.
It never rebuilds, writes the build or touches hardware.

Exit codes for `verify`: **0** when all recorded artifacts match; **1** for an
artifact mismatch or no recorded artifacts; **2** for malformed inputs, unsafe
paths or I/O errors. Other commands retain their existing 0/2 contract.

Matching artifact bytes do not establish source/configuration freshness,
a successful build, producer authenticity or firmware identity on a device.
Missing evidence in the original snapshot remains explicitly listed. The command
compares snapshots; it cannot independently verify a malicious producer's claims.

Importer validation now checks exact node metadata, image-relative paths, allowed
artifact identities, matching Kconfig-to-artifact provenance and complete graph
relationships. Secret-bearing numeric values, arbitrary extra metadata, duplicate
JSON keys and malformed nested JSON are rejected. This is strict schema-0.1.0
validation, not cryptographic attestation.


## Standalone CLI distribution

Build without installing a packaging framework or fetching dependencies:

```sh
python scripts/package_cli.py --output ./dist/zew-0.1.1.pyz
python ./dist/zew-0.1.1.pyz --version
python ./dist/zew-0.1.1.pyz graph --build /path/to/build
```

Requires Python 3.10+; Windows and Linux use the same `python file.pyz` invocation.
The zipapp includes original source, Apache-2.0 licence, CLI reference and graph
schema. Source text line endings and ZIP timestamps are normalised for repeatable
bytes. Packaging refuses existing destinations and prints a SHA-256 digest.
CI retains the zipapp and SHA256SUMS as **zew-evidence-cli-prototype**. This is an
unsigned Phase-0 test distribution, not a production release or a signed asset.

## Upstream Zephyr SBOM evidence (read-only Phase-0 adapter)

```sh
python zew.py sbom --build /path/to/zephyr/build
python zew.py sbom --build /path/to/sysbuild --image app=app --image boot=mcuboot
```

This inspects the existing upstream `west spdx` outputs without generating or
rewriting files. It reports exact SHA-256 and byte counts for observed
`spdx/{app,zephyr,build,modules-deps}` files, metadata prerequisite state and
observed SPDX 3 Build elements and build-scoped relationship counts.
No source/tool paths, user-controlled component names, PURLs or CPEs are
exported. Missing outputs receive the exact upstream generation instructions.

**Not asserted:** full SPDX validity, source-to-ELF identity, physical
flashing, CVE applicability, VEX, licence compliance or certification.
See [upstream SPDX evidence guide](UPSTREAM_SPDX_EVIDENCE.md).

## Exact upstream SPDX source/module explanations (Phase 0)

```sh
python zew.py why --source drivers/adc/sensor.c --build /path/to/build
python zew.py why --module hal_ti --build /path/to/build
python zew.py why --source src/main.c --build /path/to/sysbuild --image app=app --image boot=mcuboot
```

The source or module must match an exact upstream SPDX 3 element name.
The report links a direct build-scoped `hasInput` observation to an
existing Evidence Graph artifact ID and SHA-256. An observed build input
**is not proof that the source is in the final ELF or flashed firmware**.
Ambiguity, missing evidence and lack of relationship are explicitly unknown.
Privacy preserves names only in the caller's CLI argument, not in output.
See [SPDX why-source guide](SPDX_WHY_SOURCE.md) and [ADR-0007](adr/0007-spdx-why-direct-input.md).

## Exact compiler-database evidence

```sh
python zew.py compile --build ./build --source drivers/adc.c --source-root ./my-app
python zew.py why --source drivers/adc.c --source-root ./my-app --build ./build
```

`--source-root` specifies an existing local directory; source identity is
resolved exactly within it. The command emits an SHA-256-anchored count of
recorded compile commands for the source, per firmware image. Existing
`zew why --source` without `--source-root` remains SPDX-only.
**A compiler database record never proves a compiler ran, an object was
linked, or that a board was flashed.** See [details](COMPILATION_EVIDENCE.md).
