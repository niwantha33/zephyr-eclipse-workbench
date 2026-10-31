# Verified automatic vendor SVD discovery

Previously the SoC Registers view could attach to a native DSF-GDB frame yet stay blank because it never connected the selected Zephyr project's SoC with installed SVD files.

## How it works

1. Launch Debug As → Zephyr Application for an imported project, or open SoC Registers with a unique live Zephyr debug launch.
2. The view resolves the exact project board qualifier; for LP-MSPM0G3507 this is mspm0g3507.
3. A background job searches project .zew/svd, the managed setup's vendor-svd folder, installed Arm CMSIS packs, local West vendor HALs, the Zephyr workspace and SDK. Additional paths can be supplied via ZEW_VENDOR_SVD_DIRS (Windows uses semicolons).
4. It only accepts an exact SVD device name, or an installed vendor CMSIS pack descriptor (.pdsc) that explicitly maps this exact part to a shared family SVD. Other-chip or generic CPU-core matches are rejected.
5. When validated, the view shows peripheral/register names, addresses, reset values, bit fields and SVD read safety. Actual live values remain blank until a manual Refresh on a halted debug frame.
6. If no vendor SVD is installed, the view reports the missing exact chip and searched paths instead of inventing registers. Find vendor SVD lets the user select a project; Load SVD lets them select a trusted exact-chip file.

## TI MSPM0G3507

The selected board target is lp_mspm0g3507/mspm0g3507. TI's shared MSPM0G350x SVD is allowed only if a vendor package declares MSPM0G3507 and explicitly points to that SVD.
Arm/Keil describes the MSPM0G1X0X_G3X0X Device Family Pack on https://www.keil.com/boards2/texasinstruments/lp_mspm0g3507/ .
The tool automatically locates already installed packs; it does not silently download third-party packages or bypass licence terms.

## Evidence and safety

- Discovery is asynchronous, with bounded directory, archive and SVD sizes.
- ZIP/PDSC-derived SVDs are content-addressed and cached bytes are re-hashed before reuse.
- Differing revisions and incomplete scans fail closed; the user must choose an exact source.
- The viewer never reads physical MMIO when opening an SVD. Refresh requires a suspended native DSF session and retains SVD read-side-effect guards.
- Chip model data proves the origin of a register description, not the electrical identity of the connected MCU.
- Native Eclipse smoke, Java core tests, cross-vendor Windows firmware CI, and a real XDS110 MSPM0 hardware test are separate acceptance gates.

## Status

Automated source-matching and UI tests are necessary but do not demonstrate that a genuine TI pack is already installed on the user's PC or that halted register reads work on real hardware.
