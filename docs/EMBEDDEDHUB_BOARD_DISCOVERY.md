<!-- SPDX-License-Identifier: Apache-2.0 -->
# EmbeddedHub Board Discovery — offline Eclipse CDT increment

Status: review PR implementation, not a commercially verified hardware catalogue.
This implements a narrow business-plan opportunity without launching a second IDE,
marketplace, telemetry system, AI backend, account service or remote lab.

## User workflow

1. Import or open a Zephyr application in the existing Eclipse Workbench.
2. Select the application in Project Explorer.
3. Choose **Zephyr > EmbeddedHub > Board Discovery (Local Zephyr Catalogue)...**
4. The view invokes the installed `tools/hardware_catalog.py` using the
   project's explicitly configured managed Python and selected West workspace.
   The helper imports the **selected Zephyr checkout's** list_boards and
   list_hardware modules (HWMv2) and returns board manufacturer, silicon
   vendor, SoC, revision, qualifier and exact build target.
5. Filter by any displayed metadata. The selected project's saved exact target
   is highlighted if present. Browse a different row **without changing**
   the saved target, board revision, build profile or workspace.
6. Use **Open Configuration Studio** to navigate to the existing selected
   project's current inspection experience. The discovery view does not
   duplicate a Kconfig/Devicetree editor.

## Honest information boundaries

- 'Discovered in selected checkout' proves only that this version of Zephyr
  offers a target metadata record. It says nothing about whether it builds,
  flashes, debugs, is supported by the selected toolchain, or works electrically.
- Chip vendor and board manufacturer are separate fields.
- The architecture, MCU package, datasheet, pinmux, availability, purchase
  links, licences and peripherals are **not inferred** by this catalogue.
- The view does not generate sample code or claim an application is buildable.
  It links to the existing project-bound Configuration Studio.
- No remote registry, personal data collection, uploads, sponsored ranking,
  package manager, downloads, CMake action, build action or source edits.
- A missing project, unready environment, failed helper, malformed row, oversized
  catalogue or interrupted discovery produces an explicit unavailable state.
- Metadata is re-queried when the user selects Refresh. Rows are not cached
  across differing workspaces, Zephyr revisions or active projects.

## Implementation

- `EmbeddedHubBoardCatalogue`: pure Java, strict seven-column HWMv2
  adapter, exact qualifier/revision checking, filters and bounded rows.
- `EmbeddedHubBoardCatalogueView`: native SWT read-only table with
  cancellable background discovery and a single link to Configuration Studio.
- `EmbeddedHubBoardCatalogueHandler`: exact selected project routing.
- `plugin.xml`: native view, command, handler and Zephyr menu entry.
- `EmbeddedHubBoardCatalogueTests`: positive and negative metadata fixtures,
  called from the existing headless `scripts/test_core.py` suite.

## Acceptance, not yet proven

- Compile pure Java tests using JDK 21 and run `scripts/test_core.py`.
- Tycho packaging and installed-Eclipse SWT view smoke on Windows/Linux
  must pass on the **exact** pull request head.
- Selected-target navigation must not rewrite `.zew/project.json` or
  application files. Refresh/cancel and malformed-data conditions need UI smoke.
- Running the view against a real managed Zephyr 4.4 checkout needs qualification.
- Existing Evidence Engine G0 remains open. This is not vendor certification
  or physical board validation.

## Planned follow-ups (independent PRs)

1. Attach genuinely sourced, exact-board vendor manuals and sample entrypoints.
2. Link verified build/flash/debug CI results to scoped board+revision+profile
   records, with stale and unknown states rather than broad compatibility badges.
3. Offer reviewed sample import via existing Zephyr wizard and permissions.
4. Add a separate curated module marketplace after licence and safety review.
