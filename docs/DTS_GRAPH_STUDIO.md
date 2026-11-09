<!-- SPDX-License-Identifier: Apache-2.0 -->
# DTS Graph Studio — selected-build, evidence-only topology

## Access

With a Zephyr project and exact board/profile selected, open the standard
**Zephyr → Configuration Studio → Hardware** tab. Select a controller if
desired, then click the always-visible
**DTS Graph Studio — connected blocks** button directly above
**Show advanced tools**. This shortcut passes the same selected build path
and controller to the graph view; no extra Advanced Studio navigation is
required. The source files must already have been configured/built to produce
`zephyr/zephyr.dts` before any hardware blocks appear.

The advanced **Zephyr Advanced Configuration Studio → Hardware** toolbar
also contains **DTS Graph Studio**. Opening the view through
**Window → Show View → Other… → Zephyr Devicetree Graph Studio** opens its
empty shell until a project/build is supplied by Configuration Studio.

If the primary Hardware tab lacks the shortcut, your installed Workbench is
older than this change. A merged PR alone does not update the Eclipse
installation; install a subsequently published Workbench update and restart
Eclipse.

The graph opens the **exact current output folder** for the selected
project/board/profile, not an arbitrary project-global `build` directory:
`<application>/.zew-build/<target-profile>/zephyr/zephyr.dts`.

## Understanding the graph

- **Cards** are real Devicetree nodes from the selected build's generated
  `zephyr.dts`, as interpreted by upstream Zephyr `dtlib`.
- **Solid connections** are parent/child relationships between observed
  node paths (root, SoC, controller, children).
- **Dashed connections** are typed, resolved references. Zephyr `dtlib`
  resolves single/multiple `phandle` and `path` properties; Zephyr
  `edtlib` decodes binding-aware `phandle-array` controllers, including
  GPIO, interrupts, clocks, resets, and reported `pinctrl` states.
  Numeric values alone never create a link. A pin number is shown only
  when the selected binding's decoded cell is literally named `pin`.
- **Green border** indicates DTS `okay` or default; **grey** means DTS
  `disabled`; **orange** is the focused node. These are not evidence of
  runtime activation, wiring, occupancy, flashing, available package pads,
  driver binding, or electrical safety.
- Click a node to **focus** its local neighborhood and inspect exact
  node data and reference properties; double-click it to open the line in
  generated `zephyr.dts`. The source editor does not edit a built artifact.
- Use **Focus** with an exact DTS node path or a unique substring, and
  use **− / 100% / +** or Ctrl+mouse-wheel to adjust zoom. The graph
  scrolls vertically and horizontally.

## Trust model

The observer `scripts/dts_graph_observe.py` runs in a background Job with
the configured managed Python interpreter. It matches the selected checkout
to `ZEPHYR_BASE` in that build's `CMakeCache.txt`, rejects linked or
oversize output and include/incbin parser read directives, hashes generated
DTS before/after parsing, and uses only the selected checkout's upstream
`dtlib` and `edtlib`. The source and build are read-only. No new network
service, compilation, overlays or GPIO writes occur.

The observer streams a bounded versioned protocol with base64 fields.
`DtsGraphModel` validates the record schema, SHA-256, unique paths,
known reference types, and target existence. It does not invent a
relationship where the exact Zephyr binding cannot decode one. If
`edtlib` is unavailable, the view displays a **hierarchy-only** graph
and an explicit explanation, not falsely verified phandles.

Only a **focused 72-card neighborhood** is drawn at once; this is not the
full Devicetree and omitted nodes/links are not absent hardware. The
underlying snapshot is limited to 3,500 nodes and 6,000 typed references.
The view warns if `app.overlay` is newer than the generated DTS and
does not assert that the build is fresh based solely on having a file.

### Editing policy

The graph itself is **read-only**. Navigate to original DTS/DTSI and an
application `app.overlay` through the existing Hardware Studio source tools
when edits are required; generated `zephyr.dts` must not be modified.
The existing binding validation, reviewed source drafts, and real CMake
scratch validation remain authoritative for proposed changes.

## Test and release requirements

- `tests/tools/test_dts_graph_observe.py`: exact checkout/profile,
  real typed GPIO reference, unknown controller rejection, partial EDT,
  missing build and forbidden parser I/O.
- `tests/core/DtsGraphModelTests.java`: strict protocol and source SHA
  identity, solid containment and dashed GPIO identity, missing-target
  refusal, unknown link types, immutable data and no-build failure.
- `tests/eclipse/DtsGraphSmoke.java`: installed OSGi view, packaged
  observer file, native SWT card/edge projection, zoom and disposal.
- `scripts/verify_site.py`: shipped classes must be present in the p2
  bundle before publishing.

This PR is a **development implementation**, not a verified device release.
It must pass the Linux Tycho/p2 + installed native UI smoke, Windows unit
and cross-vendor firmware matrix, and the existing qualification gate.
A Windows manual acceptance test using an actual MSPM0G3507 generated
`zephyr.dts` is still necessary before claiming hardware-level validation.
