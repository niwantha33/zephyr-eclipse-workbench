<!-- SPDX-License-Identifier: Apache-2.0 -->
# Visual Hardware Studio — safe native navigation increment

This change improves the **existing** Configuration Studio's Hardware page. It
does **not** add a parallel editor, change Zephyr's build logic, or install a
separate web component.

## User workflow

1. Open **Zephyr > Configuration Studio > Hardware** for an imported project
   with the exact target/profile selected.
2. Choose a peripheral category (ADC/GPIO/UART/I2C/SPI/PWM/Timers/DMA/CAN/
   Pinctrl/Other). Classification is a *navigation heuristic* based on actual
   node names and compatibles from the last built `zephyr.dts`, not a claim
   that any physical function, driver or package pin is available.
3. Filter by enabled/disabled state and search by node/address/compatible.
   Matching controllers retain their child channels and hierarchy ancestors.
4. Select a node to inspect its compiled parameters. The right side shows a
   **logical** pin-route planner and the exact built property table.
5. Click a route to select the corresponding property; selecting its table row
   highlights the route. Arrow keys cycle the visible routes when the canvas
   has focus. **Show declaration** navigates to generated source.
6. **Configure property** retains the existing review/copy/overlay chooser,
   file backup, concurrent-edit checking and rebuild requirements. No generated
   `zephyr.dts` or user source is silently rewritten.

## Evidence boundary: what the planner does NOT claim

- `pinmux <0x...>` and GPIO/ADC numeric values remain **raw** unless resolved
  through authoritative package metadata. Logical route cards are not package
  pads. A chip package drawing was deliberately **not fabricated**.
- ADC channel and property names appear only when present in the build. There
  is no attempt to infer supported ADC channels, electrical limits, routing
  alternatives, mux conflicts, or automatic pin allocation.
- A controller with no pinctrl or GPIO route shows an explicit empty state.
  All property rows remain accessible even when the diagram's drawing is
  bounded to the first 48 routes.
- Category/group information is not a substitute for Kconfig, binding
  evaluation or a successful build. Runtime status is not implied.

## Stability characteristics

- The existing Features and Insights pages and overlay writers are unchanged.
- The hardware layout is a two-pane SWT `SashForm`, preserving resize support.
- The UI caches parsed properties for a selected snapshot so selecting several
  devices does not repeatedly parse the same generated DTS.
- The schematic uses SWT-owned system colours and disposes no external assets.
- No new runtime dependencies, vendor SDKs, network calls or background tasks.
- Core tests cover ADC and pinctrl classification, UART status, GPIO consumer
  exclusion and channel grouping; native Eclipse/p2 smoke is required in CI.

## Not in this increment (requires separate verified device metadata)

A package-accurate chip drawing, binding-driven typed parameter forms, pin
capability/collision checking and vendor-specific pinctrl decoding remain
unfinished. Implementing those requires an exact part+package source, verified
data ingestion and board-level hardware evidence. Do not claim that the
illustrative mockup is already achieved by this change.

## Acceptance and verification

Required before merging: `python3 scripts/test_core.py`, Maven Tycho
`mvn verify`, installed-Eclipse UI smoke and Windows firmware matrix must
all pass on the PR head. A green CI result demonstrates source/build regression
coverage, **not** physical pin accuracy. Keep this PR in draft if any required
checks are pending or failing.
