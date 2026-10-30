<!-- SPDX-License-Identifier: Apache-2.0 -->
# Visual Hardware Studio — compiled GPIO bank evidence

The Visual Hardware Studio Hardware tab now treats GPIO controllers differently
from pinctrl-capable peripherals. **A GPIO controller can have zero pinctrl
routes and still have meaningful consumers.** Zephyr represents those consumers
with `gpios` or `*-gpios` specifiers elsewhere in the compiled
`zephyr.dts`.

## What the user sees

Select **Hardware → GPIO → gpio@400a2000** on a built
`lp_mspm0g3507/mspm0g3507` target. The centre pane displays the
controller's **logical register bit positions**, not physical package pads.
MSPM0 GPIO ports have **up to 32 DIO register bit positions**; unhighlighted
cells are *unknown* rather than free/available. The exact device, package,
pinmux capability and pad location remain unverified.

Where the compiled tree includes a GPIO consumer and numeric phandle, for
example the board's Blue LED using GPIO B bit 22, the PB22 cell is highlighted.
Select the cell to inspect the consumer path, DTS property, line number and
raw flags. **Connections** shows an evidence list; **Package** still clearly
states that an authoritative physical pad map is missing.

The parameters panel retains all existing GPIO controller properties and
includes the actual compiled consumer property when it resolves. The generated
tree is read-only. Users must make source changes through the existing
reviewed application-overlay process, and then rebuild.

## Grounding and limitations

- Only compiled numeric GPIO phandles with a `#gpio-cells = <2>` controller
  and unambiguous numeric specifiers are interpreted.
- Missing phandles, nonnumeric expressions or unusual specifiers are
  explicitly **not** resolved. No usage is fabricated in their absence.
- `ngpios`, when present in the generated controller, defines the number of
  logical indexes displayed (capped for safe UI display).
- On TI MSPM0 GPIO without `ngpios`, show 32 *potential register indexes*;
  this does not establish package bonding or physical availability. For
  unrelated controllers with no `ngpios`, do not invent a GPIO width.
- A compiled consumer reference is **not proof** of pin configuration at
  runtime, pin direction, active level, electrical routing or a conflict-free
  design. Do not use this display as an electrical sign-off.
- Source: [Zephyr GPIO devicetree configuration](https://docs.zephyrproject.org/latest/hardware/peripherals/gpio.html)
  and [Zephyr lp_mspm0g3507 board DTS](https://github.com/zephyrproject-rtos/zephyr/blob/main/boards/ti/lp_mspm0g3507/lp_mspm0g3507.dts).
  The TI MSPM0 G-Series TRM documents the up-to-32 GPIO register bits.

## Regression coverage

- Headless core: MSPM0 PA/PB controller identification, exact numeric
  consumer phandle resolution, `gpios`/`cs-gpios`, malformed expressions,
  absence of fabricated pins or consumer/controller confusion.
- Installed Eclipse: select GPIO A then GPIO B, assert observed consumer
  counts and the Blue LED's source property, capture native screenshot
  `gpio-bank-evidence.png`.
- Mandatory gates before merge: Linux/Tycho/p2, native UI smoke (including
  screenshot), Windows matrix, and a visual review of the screenshot.
