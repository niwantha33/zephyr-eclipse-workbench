<!-- SPDX-License-Identifier: Apache-2.0 -->
# GPIO Evidence — not a runtime-use map

**Default:** The GPIO logical-index planner displays neutral cells. It does not
colour a pin as "used", "occupied", "available" or "free" based on a
board-provided DTS consumer or a text match in C source. GPIO names such as
`led0` are **hidden by default**. A DTS reference only proves that the last
generated `zephyr.dts` contains a declaration, not that the application
requests, configures or drives that line.

Two independent, unchecked opt-in layers are available:
- **Board DTS declarations**: shows a `D` and the declared consumer's name
  for inspection, still on a neutral cell; no use/availability inference.
- **Unverified C patterns**: shows a `C?` for exact-controller saved source
  matches, still on a neutral cell. These patterns are **not proven compiled,
  linked, reachable or executed**, and can be inside disabled preprocessor
  branches.

The **GPIO Evidence** dock separates `Board DTS only`, `Saved C pattern`
and `Unresolved source`; when generated config is outdated its DTS rows say
`STALE board DTS`. The heading always says **GPIO RUNTIME USE: UNKNOWN**
because there is no live probe/driver-register measurement. It must never
display a count of "used" pins based on DTS or text alone.

**Example: MSPM0G3507.** The board's `led0`/PB22 DTS consumer is expected
to exist even when the application never uses `DT_ALIAS(led0)`. PB22 stays
neutral and shows only `PB22` by default. Its declaration remains inspectable
in the GPIO Evidence dock, Generated DTS, Connections view, and optional DTS
layer. A saved `gpio_pin_configure(gpio_dev, 15, ...)` may identify PA15
only when the device name resolves exactly; it is still a non-executed hint.

This change neither edits project files nor reads target registers. A future
"Application GPIO usage" or "Live GPIO state" feature must first establish
the exact build image, compiler inclusion and reachability, or a verified
debug/readback session, respectively, and visibly distinguish evidence levels.

## Regression coverage

The native Eclipse smoke checks that the `led0` cell is neutral by default,
both evidence layers are unchecked, the opt-in DTS label appears only after the
user selects it, and the original label disappears when it is switched off.
It also checks the evidence table's explicit categories. Linux/Windows CI
and hardware UI screenshots are separate release gates; this change has not
been verified on a physical board.
