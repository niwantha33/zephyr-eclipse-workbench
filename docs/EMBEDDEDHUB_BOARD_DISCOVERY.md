<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Board Browser

Status: development preview. Release G0 and physical board verification remain open.

## Purpose

Help a developer discover boards in the **selected project's installed Zephyr checkout** without displaying a raw metadata database. Browsing must never silently switch the project target.

## User experience — two-column Board Browser

The browser now uses a resizable **two-column SWT layout**.

**Left — Find boards**
1. Select an imported Zephyr project and open **Zephyr > Board and Hardware > Browse Available Boards**.
2. Search by board, SoC, qualifier or exact target. Narrow by **chip vendor** and **SoC**.
3. Browse the compact table (Board, SoC, Chip vendor, Project). The active exact target is prioritised and marked **Current**.
4. The match count reports all matches; only the first **200** are rendered to avoid an unresponsive table. Narrow the search for later matches.
5. Single-click previews a board. Double-click has the same deliberate effect as **Use This Board...**.

**Right — Inspect and act**
1. The selected board's name, exact build target, SoC and chip vendor are visible; the exact target is copyable.
2. **Show technical details** is collapsed by default. It reveals manufacturer, revision and qualifier plus the evidence limitation.
3. **Use This Board...** is the main action when previewing a different target. It opens native **Project Properties > Zephyr**, preselecting the exact catalogue target. Nothing is applied until the user presses **Apply** or **Apply and Close** in that dialog.
4. **Configure Current Project...** deliberately operates on the current saved target, *not* on the previewed board.
5. **Refresh Board Catalogue** reloads from the selected project's installed local Zephyr source.

The action is disabled when the preview already matches the current project target.
The candidate exact target is passed through a **session-only project property** and cleared when the dialog closes, even on cancellation. An unsuccessful or stale catalogue lookup must not silently substitute a different board. Closing the native Properties dialog without Apply does not commit a target change. If the user pressed Apply before subsequently cancelling the dialog, the browser refreshes the real saved board.

No independent board database, network lookup, flash, build or probe access is introduced. Catalogue presence is **not** proof of compatibility, tested hardware or peripheral support.

## Release verification

- Pure Java parser/filter tests: `tests/core/EmbeddedHubBoardCatalogueTests.java`.
- Native installed-Eclipse smoke checks for opening/closing the view without physical target access.
- Interactive regression checks remain required for the two-pane resize at 1366×768 and 1920×1080, high DPI and system themes; search, vendor+SoC filtering, keyboard navigation, preview-to-Properties preselection, Apply/Cancel, job cancellation and project switching.
- Hardware compatibility, peripheral support, pin maps, commercially supported targets and physical board tests remain outside the claim of this browser.
