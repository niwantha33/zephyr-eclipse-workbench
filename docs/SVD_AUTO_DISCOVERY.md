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

## Opt-in official vendor pack provisioning (MSPM0G3507)

The **Download vendor SVD…** command offers an official TI MSPM0G1X0X_G3X0X_DFP 1.3.1
CMSIS Device Family Pack from the Arm Keil distribution. It asks for consent
before contacting the vendor and caches the pack in the managed setup's
`vendor-svd` directory. A bounded HTTPS download and ZIP parse are followed
by an exact device/variant PDSC-to-SVD mapping check using the existing
SvdAutoDiscovery parser. The command fails closed on mismatched or ambiguous
descriptions; it does **not** generate register addresses from guesses or
access the physical target during provisioning.

The pack's SHA-256 is shown after installation for provenance. This is a
content fingerprint, **not** a substitute for a separately published and
trusted upstream checksum or signature. The downloaded pack remains vendor
content subject to its own license; it is not bundled into this repository.

This download route is currently pinned only for MSPM0G3507. Other SoCs
continue using installed official vendor packs or manual exact-device SVD
selection. The downloaded description can populate the register names,
addresses, and fields even when no debugger is connected. **Live register
values require a successfully attached and suspended GDB session**; the
terminated session in the reported screenshot cannot produce values.

### Validation still required

Run the Eclipse/Tycho build, verify that the official pack URL responds on
Windows, inspect its exact MSPM0G3507 device mapping, and perform a
hardware-in-loop halted-register read using XDS110. No build or hardware
success is claimed by this source change alone.

## Cross-vendor register-source discovery extension

The SoC Registers view now also searches `CMSIS_PACK_ROOT`,
`CMSIS_PACK_PATH`, `KEIL_PACK_ROOT`, `PACK_ROOT`, vendor
install locations, `modules/hal/{espressif,stm32,nordic,ti}`, and
Espressif IDF directories. These are **discovery sources**, not proof
that a usable exact-chip SVD exists. A CMSIS pack must name the exact
device/variant in its PDSC and map it to a supported SVD, or the SVD
itself must identify the exact SoC. No unrelated Cortex-M family model
may be substituted.

For Espressif ESP32/ESP32-S2/S3/C2/C3/C5/C6/H2/P4 and other non-CMSIS
parts, ESP-IDF register headers are a **different source format** from
SVD. Merely locating those files cannot populate the SVD viewer.
A future importer must translate the device's actual `soc/*_reg.h`,
`soc/reg_base.h` and matching field descriptions deterministically,
record the file revisions and source paths, verify addresses/overlaps,
and mark incomplete/unsafe descriptions. Never invent bitfields,
reset values or MMIO read safety from an unmapped header.

Status of this change: extra local source discovery is implemented.
Automatic trusted vendor downloads for arbitrary parts, header-to-SVD
conversion for ESP-IDF, and physical target acceptance remain pending.
