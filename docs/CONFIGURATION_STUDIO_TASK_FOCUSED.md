<!-- SPDX-License-Identifier: Apache-2.0 -->
# Task-focused Configuration Studio (development PR #147)

This is a **development implementation**, not a published installer. The current
`main` release still uses the previous Configuration Studio. Repository G0 and
licensing/physical-hardware qualification gates remain open.

## The three primary sections

| Section | Primary user question | Grounded answer |
| --- | --- | --- |
| **Overview** | What is described by my selected board/profile? | Selected target and qualifier, last-generated snapshot freshness, unambiguous CPU-mapped Flash/RAM capacities, observed controller statuses, and next actions. |
| **Hardware** | How do I configure this particular peripheral? | One actual generated node at a time, selected from GPIO/ADC/UART/I²C/SPI/CAN where described, with focused evidence and guided steps. |
| **Build & System** | How is this build configured and reproduced? | Exact project/profile, selected build directory, toolchain/SDK path, output availability, explicit unknown revision and links to Zephyr setup, Kconfig/RTOS and evidence tools. |

**No nested home dashboards or colourful flow graphs.** Advanced workbench
capabilities are retained through explicit on-demand actions instead of loading
all panels and editors in the default view. The main menu opens this new
three-section `ConfigurationHomeView`; explicit source/overlay context menus
continue to reach the existing `ConfigurationStudioView`.

## Evidence states

- **Configured (build):** currently offered only when the actual generated
  ADC channel consumer and channel configuration are unambiguous and effective
  `CONFIG_ADC=y` is observed. This does **not** mean the peripheral was used
  at runtime, flashed, or physically wired. Other controller types are not
  promoted to Configured without an equivalent evidence check.
- **Disabled:** node or ancestor has disabled DTS status.
- **Setup incomplete:** a known, enabled DTS controller still needs binding,
  driver, pinctrl, channel, consumer or wiring evidence.
- **Unsupported:** reserved for positively verified unsupported operations;
  the UI does not infer this state from a missing DTS controller.
- **Unknown:** no generated DTS, stale facts or absent/unresolvable hardware
  metadata. The app must never invent a controller, pad, memory region or value.

Build freshness is separate from all peripheral states. Flash/RAM values come
only from a single mapped, unambiguous region in the selected generated
`zephyr.dts`; an unknown or multiple-bank capacity displays **Unknown**.
These are *capacities*, not linker usage or live allocation.

## ADC: Controller → Pin & channel → Settings → Review

1. **Controller:** select a real `adc@` node with precise built DTS path and
   local/parent status. A known disabled ancestor blocks setup.
2. **Pin & channel:** inspect generated `channel@N`, its `reg`, `/zephyr,user`
   `io-channels` and defined acquisition/gain/reference/resolution values.
   Logical ADC input **is not** a physical package pad. Exact pin mapping,
   pinmux ownership and conflicting assignments need the matched board, pin
   metadata and schematic; when absent, show Unknown and block auto-assignment.
3. **Settings:** show observed DTS properties and effective Kconfig. They are
   not editable inline because the correct binding constraints, pinctrl,
   dependencies and clocks must be verified before proposing a change.
4. **Review:** show that no changes have been applied, then explicitly open
   the existing binding-guided reviewed proposal. That older, guarded flow
   provides local source-diff preview, scratch Zephyr configure acceptance,
   exact-hash matching, separate save confirmation and atomic backup.

**Limitation:** the new small Review page currently delegates to the existing
advanced proposal/validation view; it does not embed an entire new diff editor
or auto-assign physical pins. These are deliberately blocked until exact
package, binding, pinctrl and conflict evidence can support the operation.
After any accepted source save, the selected Zephyr build must be regenerated
before interpreting configuration as effective. A C reference is shown only
when the existing ADC guide observes an unambiguous generated channel
consumer and an enabled effective driver setting. It is never proof of a
compiled/flashed/running application.

Other peripheral paths use their own semantics: GPIO line identities, UART
TX/RX, I²C addresses/pull-ups, SPI chip-select and CAN transceiver/termination,
rather than assuming every peripheral requires ADC gain/resolution.

## Dedicated tools / compatibility

The following **existing** views remain reachable rather than copied:
chip/pin planner (distinguishes physical package vs logical signal), SoC
memory map, generated Devicetree, source/DTSI/dependency browser, YAML
binding inspection, real CMake preflight, Kconfig/prj.conf and RTOS/evidence.
Projects, source files, build profiles and historic import/exports are not
rewritten by opening this view.

The UI reads exact project settings and selected build artifacts on a
cancellable Eclipse `Job` and updates SWT widgets only through the display
thread. No network, flash or probe operation occurs merely by opening it.

## Validation and screenshot provenance

`tests/core/FocusedConfigurationModelTests.java` covers missing/stale build
evidence, parent disabled nodes, unambiguous channel 0, incomplete or
ambiguous `io-channels`, absence of a CAN description, reported memory
capacity, and C-example gating.

`tests/eclipse/EditorSmoke.java` opens the **real installed** Eclipse SWT
view, checks the three top-level sections, selects an ADC and navigates to its
channel and review pages. When the native screenshot option is enabled it
captures:

- `configuration-overview-ci-fixture.png`
- `configuration-adc-setup-ci-fixture.png`
- `configuration-review-ci-fixture.png`
- `configuration-missing-data-ci-fixture.png`

**These images are from the actual installed UI displaying synthetic,
explicitly marked CI fixtures**. They are not render mockups and are not
measurements of the owner's actual MSPM0 board. Do not infer physical hardware
validation from them. The full CI gate, Windows firmware matrix and owner
approval are required before merge/publication; exact CI results and any
failures must be reported, never overridden by screenshot checks.

References: `AGENTS.md`, `docs/EVIDENCE_ENGINE_PROGRAM_V2.md`,
`docs/ARCHITECTURE.md` and Zephyr's Devicetree/Kconfig sources in the
selected workspace.
