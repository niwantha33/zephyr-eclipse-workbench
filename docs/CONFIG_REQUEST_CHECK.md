<!-- SPDX-License-Identifier: Apache-2.0 -->
# Config request snapshot check (Phase-0 learning feasibility)

This opt-in, **read-only** check helps answer a frequent Zephyr question:
"Did a boolean or tristate option in this configuration snippet actually appear
with the requested value in the last generated build?"

The checker belongs to the existing Evidence Engine CLI. It does not introduce
a second Eclipse editor or alter the current Configuration Studio, physical
pin planner, build/flash/debug operations, or user source files.

## Run it

Create or select a local `prj.conf` / snippet (including one copied from an AI
assistant), configure/build the **exact board and profile**, then run:

```sh
python zew.py config-check --build /path/to/build --snippet /path/to/prj.conf
python zew.py config-check --build /path/to/sysbuild --image app=app \
  --image boot=mcuboot --snippet /path/to/snippet.conf
```

The standalone `zew-0.1.1.pyz` development CLI accepts the same command.
The explicit snippet is checked independently against every selected image.
A separate snippet should be used for each image if their intended settings
differ. No source or build directory is modified.

Example: if the snippet contains `CONFIG_GPIO=y`, while the generated
`zephyr/.config` records `# CONFIG_GPIO is not set`, the result is
`differs-from-generated`. It reports the image, symbol, input line number,
redacted requested value where necessary, effective boolean/tristate value,
and SHA-256 provenance of the actual generated snapshot.

Other states deliberately remain distinct:

| State | What is actually known |
| --- | --- |
| `matches-generated` | Requested y/m/n equals the observed generated y/m/n |
| `differs-from-generated` | They differ in the selected snapshot, not necessarily the current source |
| `conflicting-requests` | The supplied snippet contains different assignments for the same symbol |
| `symbol-not-observed` | Symbol absent from the generated observation; existence and dependency remain **unknown** |
| `no-generated-config` | No usable generated `.config` exists for that image |
| `unsupported-value-type` | Numeric/string/other requests are not evaluated by this Phase-0 checker |
| `generated-value-redacted-or-unsupported` | The generated observation cannot be safely compared |

Malformed request lines are reported by **line number only**, never by copying
the input. Comments and blank lines are ignored. Repeated identical settings
are shown with all source line numbers. Up to 128 KiB and 4096 lines may be
inspected; symlinked/invalid UTF-8 inputs are rejected.

## Exit codes

- **0:** every requested y/m/n setting matches every inspected generated
  snapshot; at least one request exists and there are no malformed lines.
- **1:** at least one mismatch, conflict, missing/unsupported observation,
  malformed snippet line or no valid requests. Never interpret this as proof
  of invalid Zephyr syntax or an error in firmware.
- **2:** unsafe/unavailable input, missing build directory or other collection
  error, emitted as a bounded JSON error on stderr.

The output is deterministic JSON; raw arbitrary values, host file paths and
snippet text are not exported. It includes hashes and request-line numbers to
support reproduction without including secrets. The JSON includes explicit
limitations; even state 0 is **snapshot agreement only**, not a successful
rebuild, device readiness, source freshness, or Kconfig validity certification.

## What remains to build

A full v3 Truth Check would use the selected Zephyr checkout's **Kconfiglib**
(and, for overlays, **edtlib**) to validate unknown symbols, dependencies,
properties and type constraints against the exact board and version. This
Phase-0 operation does **not** infer those semantics. The existing native
Configuration Studio already uses an evaluator-backed Kconfig inspector for
individual symbols; future shared-core integration should reuse that rather
than invent a parser or claim that absent symbols are hallucinations.

The programme's G0 release gate remains **open**. This incremental,
headless feasibility tool does not establish a learning-curve improvement.
