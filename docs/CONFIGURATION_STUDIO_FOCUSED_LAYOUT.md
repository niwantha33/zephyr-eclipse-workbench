<!-- SPDX-License-Identifier: Apache-2.0 -->
# Configuration Studio — focused workspace and competitor benchmark

## What prompted this change

The native advanced Hardware/Devicetree view displayed four persistent summary
metrics, a second button row, three technical columns, a large evidence dock
and a horizontally scrolling strip of 16 actions simultaneously. At an ordinary
Windows 1366x768 or 1920x1080 scale this makes a selected GPIO controller
difficult to inspect. The advanced view must remain available for real review,
source trace and scratch configuration; it is **not** the primary entry point.

## Competitor study (21 November 2026)

| Tool | Useful design pattern | Limitation for our Zephyr application |
|---|---|---|
| ST STM32CubeMX | Peripheral list -> graphical pinout -> local property editing | Vendor-specific part/project generation; pin/package mapping requires exact supported part data |
| TI SysConfig | Module/pin setup, guided conflicts, automatic assignment for supported devices | Solver and device metadata are TI-specific; cannot claim Zephyr's built DTS solves a physical pin conflict |
| NXP MCUXpresso Config Tools | Distinct Pins, Clocks, Peripherals tools; graphical clock-tree editor; problems/diff views | Vendor SDK-bound generation rather than selected Zephyr build evidence |
| Nordic nRF Connect (VS Code) | First-class Devicetree and Kconfig GUIs tied to active application | Nordic-focused toolchain/workflows rather than vendor-neutral Eclipse |

Official references:
- https://www.st.com/en/development-tools/stm32cubemx.html
- https://www.ti.com/tool/SYSCONFIG
- https://www.nxp.com/design/design-center/software/development-software/mcuxpresso-software-and-tools-/mcuxpresso-config-tools-pins-clocks-and-peripherals:MCUXpresso-Config-Tools
- https://docs.nordicsemi.com/r/bundle/nrf-connect-vscode/page/index.html/extensions-included

## Interaction contract

1. Main Zephyr -> Configuration Studio opens the **four-page visual studio**:
   Overview / Hardware / Kconfig / Build & System (existing CS-20..26).
2. The legacy advanced editor's prominent **Visual Studio** action returns to
   the same current-project visual view, preserving the broad selected section.
3. On the legacy Hardware page, show a focused controller explorer, logical
   route planner and parameter inspector. The evidence dock is **collapsed by
   default**, with **Show evidence** to reveal it. Explicit evidence actions
   (source declaration, draft, real configure, GPIO example) reveal it and keep
   their correct selected tab.
4. Selecting an ordinary GPIO controller updates evidence contents but does
   not force the evidence dock open.
5. **More tools** reveals the complete legacy expert action strip; its default
   state is collapsed. **Show details** reveals the secondary toolbar and four
   existing metrics, without removing them.
6. **Open DTS Topology** hands off the exact selected project and
   built directory and only a real generated-DTS node path. If no project/build
   is selected, it explains why it cannot show a live configuration.
7. No source writer, Kconfig backend, overlay preview, diagnostic engine,
   board/profile resolution, GPIO usage claim or hardware truth source changes.

## Safety boundaries

- Logical GPIO indexes remain **not verified package pads**.
- Generated DTS and Kconfig remain read-only.
- Config requests still need existing explicit preview, confirmation, backup
  and subsequent build. No automatic pin assignment or conflict resolution.
- Driver enabled in DTS is not proof of running driver or physical wiring.
- UI-only source tests confirm wiring, **not native Eclipse rendering**.

## Qualification before merge

- Run `python scripts/test_core.py` and full Tycho `mvn verify`.
- Require installed Eclipse smoke, p2 inspection and Windows managed firmware
  matrix at the exact PR head.
- On installed Windows with a selected MSPM0G3507 build: open modern visual
  studio, Kconfig, native Hardware/Devicetree; confirm the planner occupies
  the space formerly used by evidence text and the 16-item toolbar.
- Toggle Show evidence and More tools repeatedly at 1366x768 and 1920x1080,
  100%, 125%, 150%, 200%, light/dark/high contrast, docked/detached.
- Select GPIO 0/1: no automatic evidence expansion. Click source trace,
  draft, GPIO example, and validation: correct evidence tab becomes accessible.
- Open DTS Topology and verify exact project, board, profile,
  build freshness and selected node. Unavailable/stale must remain explicit.
- Exercise cancel/commit/backups and source edits separately; hardware-on-desk
  and physical pin claims remain unverified until a real test.
