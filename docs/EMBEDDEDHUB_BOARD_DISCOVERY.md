<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Board Browser

Status: development preview. Release G0 and physical board verification remain open.

## Purpose

Help a developer discover boards in the **selected project's installed Zephyr checkout** without displaying a raw metadata database. Browsing must never silently switch the project target.

## User experience

1. Select a Zephyr project in Project Explorer.
2. Open **Zephyr > Board & Hardware > Browse Available Boards**.
3. Search by board, SoC or target, or filter by silicon vendor.
4. See only **Board**, **Chip vendor**, **SoC** and whether the target is the current project target. The view favours the current target, limits visible rows and displays exact match counts.
5. Select a row for a copyable exact target. The optional **Show technical details** checkbox reveals manufacturer, revision, qualifier, target and the evidence boundary.
6. **Change Project Board** opens existing Project Properties for deliberate confirmation. **Configure Project** opens the current project's Configuration Studio, not a browsed catalogue target.

The catalogue is read-only, local-only, cancellable, and bound to the exact project and Zephyr checkout. A board being listed is **not** evidence that it builds, flashes, debugs or works on physical hardware.

## Release verification

- Pure Java parser/filter tests: `tests/core/EmbeddedHubBoardCatalogueTests.java`.
- Native installed-Eclipse smoke and regression checks remain required for view interactions (vendor filter, search, project change, cancellation and 1366×768 layout).
- Hardware compatibility, peripheral support, pin maps, commercially supported targets and physical board tests remain outside the claim of this browser.
