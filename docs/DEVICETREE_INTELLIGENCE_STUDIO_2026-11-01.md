<!-- SPDX-License-Identifier: Apache-2.0 -->
# Devicetree Intelligence Studio — evidence-first engineering specification

**Status:** research and implementation specification, NOT a completed feature or release approval.
**Date:** 2026-11-01
**Integration:** Extend existing native Configuration Studio Hardware/Insights, never build a duplicate view.

## Research findings

1. Zephyr combines board/SoC DTS, includes, application overlays and YAML bindings into generated `zephyr/zephyr.dts` and a generated C header. Zephyr's `dtlib` parses DTS; `edtlib` interprets matching bindings. The effective built tree is authoritative for *configuration*, not for physical behaviour.
2. `pinctrl-0`, `pinctrl-1`, etc. are state-indexed references paired with `pinctrl-names`. Pin identifiers and pad mux encodings are SoC-specific; an arbitrary numerical GPIO index is not proof of a package ball or a free pad.
3. Devicetree and Kconfig are different layers. An enabled node does not prove the corresponding driver was linked or initialized. Zephyr's `dtdoctor` can assist with unresolved devicetree ordinals.
4. Nordic already provides a Devicetree Visual Editor; graphical editing alone is not a defensible market differentiator. Cross-vendor **auditable semantic evidence and round-trip configuration validation** is the stronger opportunity.
5. Existing Workbench contains Chip Pin Canvas, Java DTS discovery, binding previews, safe overlay proposal, `zew dt-check`, `zew dt-configure`, and GPIO evidence. Preserve the existing explicit source/built/physical evidence separation.

References:
- https://docs.zephyrproject.org/latest/build/dts/intro-input-output.html
- https://docs.zephyrproject.org/latest/build/dts/bindings.html
- https://docs.zephyrproject.org/latest/build/dts/howtos.html
- https://docs.zephyrproject.org/latest/build/dts/troubleshooting.html
- https://docs.zephyrproject.org/latest/hardware/pinctrl/index.html
- https://docs.zephyrproject.org/latest/develop/sca/dtdoctor.html
- https://docs.nordicsemi.com/r/bundle/nrf-connect-vscode/page/index.html/extensions-included

## UX: select a peripheral -> explain -> edit -> validate -> navigate

The native Hardware tab should show a left-side hierarchy (peripheral type, compatible, node and status), a central chip/package pane only if authoritative geometry exists, and a right-side typed property inspector derived from the matched binding. Bottom dock: source provenance, diagnostic evidence, proposed patch, result comparison and history. Preserve keyboard and accessible table mode if package geometry is unavailable.

For a selected UART/I2C/SPI/ADC/CAN/PWM/GPIO node, display:
- exact Zephyr version, board revision/qualifier, core, build profile, image and snapshot hash
- node path, label and alias (if any), compatible, effective status, matching binding path and property types
- `default`/`sleep` pinctrl states resolved through the exact target's adapter; expose raw encoded values when unsupported
- clocks, resets, power, DMA, interrupts, bus parent/children, Kconfig and driver status **as separately proven**
- declaration/source references with actual original-file paths when known, and explicit 'origin unknown' otherwise
- actual C references as **text candidates**, distinct from compiled translation-unit evidence and runtime traces
- code sample only for a verified compatible/controller; otherwise no fabricated sample pin route

**Overlay wizard:** choose an eligible target -> choose typed value -> show minimal diff -> check external modification -> save only with user confirmation -> disposable exact-target Zephyr configure -> show old/new effective DTS diff -> full build separately. No background flashing or automatic rewrites.

## Essential evidence model

Every claim has `value, kind, confidence, provenance, target_identity, input_sha256, observed_at`.
Kinds are `SILICON_CAPABILITY`, `BOARD_WIRING`, `DTS_DECLARATION`, `BUILT_CONFIGURATION`, `SOURCE_CANDIDATE`, `COMPILED_REFERENCE`, `RUNTIME_OBSERVATION`.
A missing data source must be UNKNOWN, never FALSE or 'free'.
For a pin:
- declared in DTS != application uses it
- GPIO controller bit index != package pad name
- C scanner match != built or executed code
- valid pinctrl binding != proven electrical compatibility
- configured peripheral != initialized runtime device
- source search negative != no firmware use
- `led0` alias != physical LED usage
- no named SVD != no peripheral silicon capability

## Hard gate: package and pin adapters

Per-target provider API:
`supports(exact_soc, package, board_revision, zephyr_version)`;
`resolve_controller_pin`; `resolve_pinctrl_state`; `package_pad`;
`allowed_functions`; `electrical_constraints`; `source_reference`.
Use versioned, licensed, authoritative vendor data. Unknown mappings stay UNRESOLVED and the GUI must not draw a guessed package connection. Never extrapolate one MSPM0, STM32, nRF or ESP32 device's pin options to a family.

Pin-conflict detection requires same identified physical pad, same relevant state and simultaneous use with authoritative mappings. No warnings merely because two raw numeric pin values match on different controllers. Ignore or annotate mutually exclusive build profiles and alternate sleep/default states appropriately. Never equate C text matches with confirmed conflicts.

## Delivery order and acceptance (all blocking)

### Increment A: evidence-core integration
- Define immutable DTO and adapters between selected build artefacts, binding preview and native view.
- Display exact snapshot identity and stale source/build status everywhere.
- Add unit fixtures: DTS alias exists but never used, disabled peripheral, absent binding, unknown package, C false positive, two controllers with equal numeric index.
- No new pin shown as active solely from DTS or source scanner.

### Increment B: typed multi-peripheral inspector
- Binding-driven supported property types, required/enum/const/deprecated semantics.
- ADC gain/reference/resolution/acquisition; UART current-speed; I2C clock-frequency; SPI chip select; PWM period/polarity; CAN timing as supported by the **specific** binding.
- Source navigation and exact build-derived values; unsupported settings show raw/readonly, never speculative controls.
- Tests across named MSPM0G3507, nRF52840 DK, STM32F446RE and ESP32-S3 selected boards/Zephyr versions.

### Increment C: vendor pinctrl and physical chip
- Implement adapter SPI and only evidence-backed device/board/package providers.
- Overlay collision analysis with actual physical pad identity, state and controller role.
- Test negative cases and known conflict cases, including mixed controller namespaces and board revisions.

### Increment D: source-to-effective round trip
- Proposed minimally scoped overlay, version-controlled diff, safe write with concurrent-edit rejection, revert.
- Use existing `dt-configure` for real temporary configure, compare generated effective tree.
- Validate Kconfig/driver dependencies separately, optionally use `dtdoctor`.
- Distinguish configuration success from compiled/linked/physical runtime success.

### Increment E: advanced debugging
- From `__device_dts_ord_N` map to generated header ordinal and node; then status, matching driver and Kconfig evidence.
- From target debug/register views back to selected DTS node only on verified exact SoC/device mapping.
- Offline provenance report and side-by-side build revision difference.

## Required testing and release gates

Unit: parsers, property types, alias/phony node, multi-image, no-binding, phandle resolution, stale hashes, malformed paths, injection safeguards.
Integration: `west build --cmake-only` with actual selected checkout, check generated DTS/header; complete firmware build and compile commands; verify candidate overlay has intended result; pristine rebuild.
UI: installed Eclipse SWT smoke, screenshots, keyboard/HiDPI, no duplicates, no UI blocking or SWT disposal leaks.
CI: Windows and Linux, named boards, real Zephyr version, green final HEAD; do not disable existing checks.
Hardware: exact evaluation board + revision + probe captured for any claims of flash/runtime/physical-pin confirmation.

## Non-goals and truthful status

This document makes no claim that any feature is already implemented, that these pin mappings exist in the repository, that CI is green, or that usability has been measured. No artificial '60% learning curve improvement' without a predeclared, timed beginner-study protocol and measured outcomes. The commercial acceptance gate remains OPEN.
