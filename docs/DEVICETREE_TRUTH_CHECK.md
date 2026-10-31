<!-- SPDX-License-Identifier: Apache-2.0 -->
# Devicetree Truth Check — initial edtlib binding preview (Phase 0)

This is a **read-only evidence check**, not an automatic overlay fixer.
Zephyr's own `dtlib` and `edtlib` validate a *temporary* composite made
from the last generated `zephyr.dts` plus an optional simple overlay.

## Examples

Inspect one effective hardware node/property from an actual built target:

```sh
python zew.py dt-check --build ./build --zephyr ./zephyr \
  --node /soc/gpio@40020000 --property gpio-controller
```

Preview a simple overlay against that generated snapshot:

```sh
python zew.py dt-check --build ./build --zephyr ./zephyr \
  --node /soc/uart@40011000 --property status --overlay ./app.overlay
```

The example paths are illustrative only: the selected node's path must
exist in **your own generated DTS**; do not assume those addresses exist
on another board. For sysbuild, pass explicit `--image NAME=RELATIVE_PATH`
arguments. If the build uses out-of-tree or module bindings, supply each
additional binding directory using `--bindings /path/to/dts/bindings`.

The checker first requires `CMakeCache.txt` to identify the exact
`ZEPHYR_BASE` supplied by `--zephyr`, checks the generated DTS snapshot
and its SHA-256, then loads the matching checkout's
`scripts/dts/python-devicetree/src/devicetree/edtlib.py` and the binding
roots. No network is used. The only file write is a temporary copy of the
DTS in an isolated OS temporary directory; existing applications, build
artifacts, `.config`, and overlays are untouched.

When `--overlay` is supplied, the tool appends the **plain DTS text** to a
temporary copy of the generated DTS and asks actual edtlib to interpret it.
The tool reports whether the chosen node is present, whether a binding
matched, and (optionally) whether its selected property was interpreted by
that binding and its binding type. It emits *no raw property values, YAML
contents, DTS source, local file paths, or potentially private exception
messages*. Inaccessible, invalid or unverified evidence remains unknown.

## What the results actually mean

- `built-tree-evaluated`: selected built DTS parses, and the requested
  bound node/property is observed as described. **Not a physical GPIO claim.**
- `binding-validated-preview`: edtlib accepted the temporary combined DTS
  and the chosen node/property has binding-backed interpretation. This does
  **not** prove the overlay changed that property, was included by CMake, or
  can be built/flashed on the target.
- `built-tree-incomplete`, `preview-incomplete`: no matching binding,
  node or specified property; these are missing evidence, **not** a proof
  that the feature is impossible.
- `edtlib-rejected-preview`: edtlib rejected the temporary composition.
  No raw error is returned to avoid leaking arbitrary input; reproduce with
  Zephyr's real build for detailed diagnostics.
- `build-checkout-unverified`, `missing-generated-dts`,
  `edtlib-unavailable-or-rejected`: evidence is not enough for a claim.

Exit 0 requires interpreted evidence for all explicitly selected images.
Exit 1 means incomplete/rejected/unverified evidence; exit 2 means invalid
arguments or unsupported/unreadable input. The checker is intentionally
bounded: generated DTS ≤4 MiB, overlay ≤64 KiB, cache ≤8 MiB, up to ten
binding roots. Symbolic macros, `#include`, `/incbin/`, and standalone
preprocessor fragments are rejected, because they need the Zephyr build
pipeline rather than a guessed parser. Avoid plain overlays referring
to labels removed from the final generated tree; use full paths or build.

**Security:** `zephyr/edt.pickle` is deliberately NOT deserialised.
Unpickling arbitrary build outputs executes arbitrary Python objects and is
unnecessary for this read-only preview.

## Acceptance and future integration

Unit tests cover source integrity, missing binding, missing node/property,
malformed snippets, privacy, multi-image requests and CLI contract.
The native Windows firmware matrix checks actual selected-version edtlib
against the TI MSPM0 generated DTS; Linux core/UI packaging runs unchanged.

This is a headless first phase, **not** complete DTS overlay validation,
vendor pinmux/electrical checks, authoritative package mapping, a validated
auto-fix, full configuration Truth Check, or a measured learning-curve gain.
Use a real reconfigure/build before calling a proposed overlay valid for
production. Do not add a duplicate viewer; future Eclipse presentation
should reuse the existing Configuration Studio's Hardware/Insights tabs.
G0 remains open.
