<!-- SPDX-License-Identifier: Apache-2.0 -->
# Guided peripheral overlay drafts — read-only Phase-0 foundation

The `zew peripheral-guide` command produces an **in-memory, explicitly
reviewable source override**, not a configured or working device. It is
headless-first and is based on the selected real Zephyr **edtlib**
interpretation of an existing `zephyr/zephyr.dts`, the exact checkout from
the selected build's `CMakeCache.txt`, and caller-specified module binding
roots. It deliberately does **not** propose GPIO pad numbers, pinmux,
interrupts, DMA, clocks, phandles, compatible strings, bus chip-select
routes, or source locations.

## Use

Choose the **actual** generated node path for the selected board/core and a
single source intent:

```powershell
python zew.py peripheral-guide --build "C:\Zephyr_Project\.zew-build\app" `
  --zephyr "C:\Zephyr_Workspace\zephyr" `
  --node "/soc/gpio@40000000" --kind gpio --status okay
```

For an existing **binding-interpreted** integer field, such as a controller's
or channel's real `spi-max-frequency`, `clock-frequency`, or
`zephyr,resolution`, use `--set-int NAME=VALUE`. The property must
actually exist for this selected bound node and have upstream
`PropertySpec.type == int`; a command-line name alone is never evidence.

For existing **string enum** fields, use `--set-enum NAME=CHOICE`. The
selected upstream binding must declare the exact choice in its `enum`.
No arbitrary free-form strings, guessed pin flags or generated numeric
phandles are allowed. Deprecation and incompatible `const`/enum values
fail to produce an automatic draft. Arbitrary property changes require
the existing reviewed source editor instead.

To compare against a specific saved overlay **without modifying it**:

```powershell
python zew.py peripheral-guide --build "C:\Zephyr_Project\.zew-build\app" `
  --zephyr "C:\Zephyr_Workspace\zephyr" `
  --node "/soc/adc@40000000" --kind adc --set-int "zephyr,resolution=12" `
  --application "C:\Zephyr_Project" --overlay "C:\Zephyr_Project\app.overlay" `
  --show-diff
```

`--show-diff` is intentionally opt-in: it includes a real unified diff,
which may show context from the existing source overlay. Without it, the
output includes only hashes, source evidence, requested change category,
and whether a draft is available. It does not output user strings, paths,
registers, pins, code or existing source text. No file output is supported.

For sysbuild, use one explicit `--image NAME=relative/build` to identify the
actual target firmware image. This operation refuses to make the same
proposal simultaneously across multiple images. Additional real module
binding roots can be supplied with repeated `--bindings` and must exist.

## What the tool actually checks

1. The selected generated DTS exists and its configured checkout matches
   the exact selected Zephyr root in `CMakeCache.txt`.
2. Upstream selected Zephyr `edtlib.EDT` interprets that generated DTS,
   using the explicit binding paths and the selected node.
3. The node has a real matched binding; for integer/enum proposals the
   property is **already interpreted on this exact node**, non-deprecated
   and of the exact matching upstream `PropertySpec` type. Enum/const
   restrictions are enforced when present.
4. A small, single-node symbolic `&{/exact/path}` source addition is
   proposed. If a saved overlay was supplied, its original SHA-256 and
   the proposed combined SHA-256 are reported. Original DTS and overlay
   files are not overwritten.
5. When the user explicitly requests it, a bounded unified diff can be
   copied to the editor for review. The tool does not commit or activate it.

Return status `draft-requires-real-configuration` (exit 0) means only
that the **preview was generated** under these narrow evidence rules.
`manual-review-required` (exit 1) means insufficient or mismatched
binding/property evidence. Invalid inputs return exit 2.

A status override to `okay` or `disabled` changes **source intent**, not
physical device availability. It cannot establish parent power domains,
controller readiness, board pad routing, pin conflicts, electrical safety,
SoC firmware inclusion or driver probing. The requested `--kind` value is
a user-selected category and is never treated as proof that the node is
that class of peripheral. Reported interface hints come only from existing
EDT node properties and `buses`/`on_buses` metadata.

**Next:** inspect/edit the saved source using the existing Workbench
review-and-backup workflow, and run the opt-in `zew dt-configure` against
that exact saved overlay. A successful configure still does not prove
compiled firmware or physical correctness.

**G0 is open.** This CLI is not production qualification or release approval.
