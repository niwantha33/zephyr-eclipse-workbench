<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Workbench task-focused UX

Status: development preview. No release or hardware qualification is implied by the interface change.

## One developer workflow

**New / Import Project → Choose Board → Configure → Build → Flash or Debug → Trace & Analyse**

The Zephyr menu now uses task names, with specialised investigations in dedicated submenus. Commands and the existing build/debug/trace backends remain registered. The unavailable live-streaming placeholder is not advertised as a working action.

## Two everyday workspaces

- **Guided Setup (step by step)**: a two-column guide with workflow navigation (left) and one selected step (right). Project Explorer, the editor, Console and Problems remain part of the Eclipse perspective. Steps are Project, Hardware, Configuration, Build, Flash & Debug, Inspect & Analyse, and More Tools. Navigation alone never runs a command.
- **Development Workspace (coding)**: an editor-focused layout with Project Explorer, Console and Problems, plus a compact, dismissible development dashboard. This is not the beginner guide: it offers project context, source navigation and explicit hardware, build, flash/debug and advanced settings actions. For existing developer layouts, a one-time upgrade migration replaces the former duplicate guide with the dashboard. Configuration Studio, Hardware Studio, boards, tracing and registers remain on demand.
- **Hardware Debug Workspace** remains separate for actual debug sessions.

Both workspaces are layouts only: switching them cannot flash/reset a target.

## Two-column Getting Started guide

- The left navigation keeps seven concise, keyboard-selectable workflow steps and live selected-project, board, profile and ELF-presence context.
- The right pane shows **only the selected step**: its purpose, three or fewer short usage notes, and explicit actions.
- Drag the central divider to resize navigation and instructions. Either column can scroll independently.
- Choosing a step changes only the right-pane content. Pressing a tool/action button opens the corresponding existing Eclipse view, wizard or command; it is not embedded in the guide.
- Build, flash and debug remain explicit user actions. ELF presence does not verify target execution; trace and CPU tools must not imply live capture without evidence.
- Advanced tools remain discoverable by name, without showing every option on initial load.

## Workspace visibility and Zephyr menu wording

- Choosing **Guided Setup (step by step)** reopens the beginner guide even if a saved Eclipse perspective previously closed it.
- Choosing **Development Workspace (coding)** keeps the editor and Project Explorer visible, hides the duplicate beginner guide, and opens the optional development dashboard.
- When upgrading an existing Developer perspective without an open editor, show the development dashboard once. Users can subsequently close it without startup restoring it on every launch.
- The Zephyr menu uses literal conjunctions ("Board and Hardware", "Flash and Run", "Trace and Analyse") because a single ampersand is interpreted as an Eclipse mnemonic and disappears.
- The guide only displays controls and project evidence. Neither switching workspaces nor selecting guide steps runs build, flash, attach or debug commands.

## Board browser

**Board & Hardware > Browse Available Boards** shows a searchable 4-column list with an SoC/vendor filter and optional technical details. **Change Project Board** uses the same validated Project Properties workflow as before. Merely browsing metadata cannot change the saved build target. **Explore Pins and Peripherals** opens the guided **Hardware** section for the saved project board, not an uncommitted catalogue preview. Hardware Studio offers a visual, read-only graph for generated Devicetree evidence; this is not yet a fully editable SysConfig-style pin mux tool, and missing or stale builds must not fabricate pins.

## Native acceptance to execute before release

1. Ensure new commands, handlers and perspectives register and open cleanly.
2. Exercise new/import, changing project board, Configuration Studio, build, flash and debug with deliberate user confirmation.
3. Test board filtering with both search and silicon vendor, empty results, current target highlight, environment failure, cancellation and switching projects during discovery.
4. Check 1366×768 and 1920×1080, high DPI, light/dark system themes, keyboard focus and screen-reader labels.
5. Verify existing workspace layouts survive upgrade and that all advanced commands are still reachable.
6. Run Tycho, core tests, Eclipse native smoke and Windows release matrix. Hardware tests are separate qualification and must not be marked PASS without evidence.
