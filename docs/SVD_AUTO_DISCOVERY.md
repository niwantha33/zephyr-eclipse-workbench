# Verified automatic vendor SVD discovery

Previously the Zephyr Hardware Register Explorer view could attach to a native DSF-GDB frame yet stay blank because it never connected the selected Zephyr project's SoC with installed SVD files.

## How it works

1. Launch Debug As → Zephyr Application for an imported project, or open Zephyr Hardware Register Explorer with a unique live Zephyr debug launch.
2. The view resolves the exact project board qualifier; for LP-MSPM0G3507 this is mspm0g3507.
3. A background job searches project .zew/svd, the managed setup's vendor-svd folder, installed Arm CMSIS packs, local West vendor HALs, the Zephyr workspace and SDK. Additional paths can be supplied via ZEW_VENDOR_SVD_DIRS (Windows uses semicolons).
4. It only accepts an exact SVD device name, or an installed vendor CMSIS pack descriptor (.pdsc) that explicitly maps this exact part to a shared family SVD. Other-chip or generic CPU-core matches are rejected.
5. When validated, the view shows peripheral/register names, addresses, reset values, bit fields and SVD read safety. Actual live values remain blank until a manual Refresh on a halted debug frame.
6. If no vendor SVD is installed, the view reports the missing exact chip and searched paths instead of inventing registers. Find Device Definitions lets the user select a project; Load SVD lets them select a trusted exact-chip file.

## Remembering verified register files (November 2026)

The previous explorer cleared its saved SVD path each time it selected a Zephyr
project. Consequently, a user could successfully import an official device
description but had to locate the file again on the next debug session.

The current Workbench remembers a successfully verified path and its SHA-256
**per exact SoC** in the local Eclipse instance preferences. On a later launch,
the discovery job re-parses the SVD, confirms that its model matches the built
chip and verifies the original content hash before opening it. Missing, edited,
symbolically linked, different-chip and unparseable files are rejected; normal
official-pack discovery is used instead. The older managed `~/.zew/svd-cache`
directory is also scanned. No vendor pack is copied into the project tree.

A previous Eclipse view memento is accepted only after exact-part inspection,
then migrated into hashed per-SoC preferences. An explicitly PDSC-mapped family
SVD whose model is *not* itself exact-compatible is **not** trusted merely from
its extracted cache path; repeat official package mapping verification.

A green SVD match verifies a register *description*, not the attached physical
MCU. Hardware reads still require a uniquely identified, suspended CDT-GDB
session.

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

## Offline pinned download catalog (November 2026)

The Workbench now loads the bounded, bundled `svd-download-catalog.json`
instead of executable per-vendor URL, model and checksum maps. The catalog
stores `part`, optional `qualifier`, `url`, `sha256`, `type`
(`pack` or `svd`) and optional `pdsc`. An HTTPS URL is **not**
authorisation to download. A full, separately verified SHA-256 pin, an allowed
origin, bounded response and matching exact part identity are all required.
Cached content is rehashed before reuse; `.pack` payloads additionally need
a PDSC mapping for the actual named part. Redirects remain disabled for direct
Espressif SVDs and restricted to existing official TI hosts for packs.

The eight Espressif SVD entries retain their pre-existing SHA-256 pins exactly,
against upstream `espressif/svd` commit
`be20aa12560889d6125d144cdb48cf615ac17628`.

**TI caveat:** The prior MSPM0 pack URL had no independently verified SHA-256
pin; it relied on XML/PDSC inspection and a post-download content fingerprint.
It is recorded in the catalog with an **empty** SHA field and automatic network
download is **disabled** until a trusted digest is established. No checksum
has been guessed or borrowed. Locally installed official TI packs can still
be discovered with exact PDSC evidence. This security restriction is
intentional.

## Exact SVD model aliases and evidence sources

`src/svd-part-mappings.json` stores exact chip/cluster mappings, not
heuristic `X` wildcards. Every new mapping should carry its provenance as a
documentation row with `source:` and an exact named device/core mapping,
a reviewed upstream revision and the expected SVD basename. If evidence is
incomplete, do not add the mapping.

| Part / qualified core | SVD model | Evidence (`source:`) | Hardware-in-loop |
|---|---|---|---|
| MSPM0G3507 | MSPM0G350X | `source:` Arm Keil TI MSPM0G1X0X_G3X0X Device Family Pack; installed PDSC must explicitly name MSPM0G3507: https://www.keil.com/boards2/texasinstruments/lp_mspm0g3507/ | Pending XDS110 |
| nRF5340/cpuapp | nrf5340_application | `source:` Nordic nRF MDK DFP https://www.keil.arm.com/packs/nrf_devicefamilypack-nordicsemiconductor/versions/ and Zephyr HAL Kconfig/SVD selection https://pigweed.googlesource.com/third_party/github/zephyrproject-rtos/zephyr/+/b0afa1e571d143af0d9e29d6c13eb3b43c222a7a/modules/hal_nordic/nrfx/CMakeLists.txt | Pending app-core probe |
| nRF5340/cpunet | nrf5340_network | `source:` same Nordic DFP and Zephyr HAL core-specific mapping above | Pending net-core probe |
| Espressif 8 exact parts | Exact part-named SVDs | `source:` https://github.com/espressif/svd/tree/be20aa12560889d6125d144cdb48cf615ac17628/svd and pinned Git SHA-256 byte digests in catalog | Pending each chip |
| nRF52840 | Exact named nrf52840 | `source:` Nordic MDK at https://github.com/zephyrproject-rtos/hal_nordic (fixture trimmed, BSD-3-Clause header retained) | Pending |
| RP2040 | Exact named RP2040 | `source:` Raspberry Pi pico-sdk https://github.com/raspberrypi/pico-sdk/blob/master/src/rp2040/hardware_regs/RP2040.svd | Pending |
| STM32F103 | Exact named STM32F103 or verified device PDSC | `source:` ST distribution mirrored under Apache-2.0 at https://github.com/modm-io/cmsis-svd-stm32/tree/main/stm32f1 | Pending |
| ATSAMD21* | Only an exact matching part or DFP PDSC | `source:` Microchip SAMD21_DFP https://www.keil.arm.com/packs/samd21_dfp-microchip/overview/ ; no family alias inferred | Pending |

A multicore target such as `nrf5340dk/nrf5340/cpuapp` must match
the **full** generated `CONFIG_BOARD_QUALIFIERS=nrf5340/cpuapp` and
the exact bundled core mapping; otherwise discovery returns ambiguous.
This does not prove which actual physical core was flashed.

## Traversal and DTS honesty

Installed local roots are reordered to inspect the matching vendor HAL first
(STM32, Nordic, Espressif, TI, Raspberry Pi, Microchip/Atmel, Renesas,
Silicon Labs); order never changes the identity policy.
Each root has its own 8,000-path budget plus a global 80,000-path ceiling,
and truncation is reported by root. Any incomplete root makes discovery
`INCOMPLETE_SCAN` rather than silently trusting a partial match.

The register/DTS linker accepts up to eight fully parseable `reg`
address-size ranges per node, including comma-separated `<address size>`
regions. Malformed, symbolic, missing, overlong or ambiguous cell layouts
remain `UNKNOWN`, with a reason. A DTS status of `okay` is not a
guarantee the peripheral clock is powered and does not substitute for
target-halt + GDB evidence.

**Release qualification is not the same as hardware validation.** The
remaining acceptance work for each vendor is exact installed DFP/PDSC
resolution, CPU/core match, sample readable MMIO address, a safe
confirmed write, suspend/resume, live watch without target starvation and
a hardware-in-loop trace from a real probe. All remain pending until observed.

## Cross-vendor register-source discovery extension

The Zephyr Hardware Register Explorer view now also searches `CMSIS_PACK_ROOT`,
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

## Effective build metadata and unqualified board targets (development)

Zephyr allows a single-SoC board target with no SoC qualifier. For such a
project, the resolver first checks generated `zephyr/.config` for a matching
`CONFIG_BOARD`, exact `CONFIG_SOC` and compatible `CONFIG_BOARD_QUALIFIERS`.
Only then does it supply an SoC-qualified target to the existing exact-SVD/pack
verifier. No board-name heuristic or generic CPU SVD fallback is permitted.

For explicitly qualified targets, contradictory effective-build board or SoC
metadata blocks automatic discovery; a stale build must be rebuilt or a
verified exact SVD chosen manually. Missing generated build metadata does not
override an already explicit SoC qualifier but is labeled accordingly.
The attempt and result are logged in Eclipse Error Log as informational entries,
including scan status and number of inspected roots. UI and log messages do
not establish flashed-firmware identity.

Synthetic tests cover board-only targets, valid qualifiers, stale builds,
conflicting SoCs and architecture-only identifiers. Real vendor metadata,
pack selection, Eclipse UI and physical read qualification remain open.
