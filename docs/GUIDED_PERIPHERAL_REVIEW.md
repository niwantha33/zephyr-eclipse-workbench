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


## Real Zephyr preflight of an *unsaved* guided draft (Phase 0)

The new `zew dt-draft-configure` command tests the exact user-requested
binding-grounded change **before editing the real application overlay**.
It invokes real `west build --cmake-only --pristine=always` against a
bounded, copied application, using the matching original board/core and
Zephyr checkout.

```sh
python zew.py dt-draft-configure \
  --build ./app/.zew-build/debug --application ./app \
  --zephyr ./west/zephyr --python ./west/.venv/bin/python \
  --board lp_mspm0g3507/mspm0g3507 --node /soc/gpio@4000 \
  --overlay ./app/app.overlay --status okay --kind gpio
```

The node path here is an illustration, **not a supplied SoC GPIO mapping**.
Use an exact node discovered from the current generated DTS and the
corresponding real board identity in `CMakeCache.txt`. Supply
`--ninja /path/to/ninja` where needed. The command also accepts
`--set-int NAME=VALUE`, `--set-enum NAME=CHOICE`, and additional
real module `--bindings DIR` roots (repeatable). The target overlay
must be a selected .overlay within the application; it may not exist yet.

The command proceeds in two strict phases. It first runs the same
**read-only upstream edtlib binding guide** as `zew peripheral-guide`,
which may reject unknown or unsafe changes without launching CMake.
Then it verifies the original DTS and overlay SHA-256 and appends the exact
hashed addition **only to the disposable application copy**. It invokes
actual trusted West/CMake, verifies scratch board/checkout/overlay
provenance, and parses the resulting `zephyr.dts` through upstream Zephyr
`dtlib` to check **exactly the requested status, 32-bit integer or enum
string value**. A successful CMake exit without the requested value
**never** reports an accepted preflight.

The JSON result contains only typed evidence, artifact hashes and fixed
diagnostic classifications. It never prints copied source content, raw
CMake log, local paths, or the unsaved proposal. Use `peripheral-guide
--show-diff` separately when you want to review the source addition.
Exit **0** means real configuration completed and the exact requested
generated value was observed. Exit **1** means manual review/failed or
unavailable configuration. Exit **2** means invalid input.

**Security boundary:** This is not a sandbox. Project CMake scripts and
module hooks can execute with normal host privileges. Only run it on
trusted sources. The selected project and build are never written,
compiled, flashed or debugged by this command, but the scratch execution
can run arbitrary CMake hooks. The result proves configuration of the
*copied* application in this environment, **not** driver compilation,
a free package pad, electrical suitability, pinmux ownership, firmware
deployment, runtime state, or release qualification. The saved overlay
still requires explicit review and save followed by the real build.
