<!-- SPDX-License-Identifier: Apache-2.0 -->
# Official documentation and reference links

Open **Zephyr > Documentation & References...** to view read-only links to official
upstream Zephyr and manufacturer sources. Type into the filter and double-click a
row, or select **Open selected official link in browser**. The workbench never
downloads content or accesses the network until the user explicitly opens a link.

The references include Zephyr documentation, C API, kernel, Devicetree, binding
index, Kconfig, GPIO and board index. When an imported Zephyr project is selected,
the view reads the bounded local <west workspace>/zephyr/VERSION file and uses its
release version in Zephyr documentation URLs when identifiable. Unknown, incomplete,
development and prerelease checkout versions fall back visibly to latest development
documentation. These navigational links are not runtime/API compatibility evidence,
and per-page existence is not verified by the workbench.

The first exact-board mapping is **lp_mspm0g3507** (including exact qualifier
suffixes): it adds the official TI MSPM0 G-Series TRM, TI MSPM0G3507
datasheet/errata product page, LP-MSPM0G3507 board guide/design files, and the
latest Zephyr board guide. The latest board guide can differ from the local
checkout. The TRM applies to the family; check your exact silicon variant,
package, errata and document revision before using register descriptions.

Every board and SoC receives generic Zephyr references and a curated set of
**manufacturer documentation portals** spanning TI, ST, Nordic, Espressif, NXP,
Renesas, Microchip, Silicon Labs, Raspberry Pi, Analog Devices and Infineon.
The operator selects the exact part/revision within the official manufacturer
site. These portals are vendor-neutral navigation, not automatic target matching.
This makes documentation navigation useful across all device selections without
fabricating an exact TRM association. Unknown boards do **not** receive an
unverified part-specific TRM. Add exact links only after validating the target.

This is documentation navigation, **not** a source of evidence for enabled drivers,
runtime register contents, GPIO pinouts or firmware identity.

## Engineering follow-ups

- Extend the source catalogue with verified official TRM/datasheet/errata/schematic
  sources per exact board and SoC (STM32, Nordic, Espressif, Realtek, TI).
- Add exact-binding links from selected built Devicetree compatible plus local YAML
  source; show missing or ambiguous mappings explicitly.
- Add symbol-aware C API links in editor and TRM section navigation only when a
  verified per-part peripheral/register-to-chapter map exists.
- Keep navigation explicit and offline by default; do not imply live hardware reads.
