# Verified automatic vendor SVD discovery

Previously the Zephyr Hardware Register Explorer view could attach to a native DSF-GDB frame yet stay blank because it never connected the selected Zephyr project's SoC with installed SVD files.

## November 2026: vendor-neutral, fail-closed source selection

The explorer now uses `SvdPartCompatibility.modelsFor(part)` and the bundled
`svd-part-mappings.json` to shortlist filenames. **Filenames do not establish trust.**
The parsed device name must still exactly match the part or a documented
alias; an installed PDSC can authorize a family SVD only through an exact
Dname/Dvariant-to-debug-SVD mapping.

### Source comments / audit trail for every part-map entry

The JSON file has no comment syntax. Each mapping must have a matching
**source comment in this section** before it is added. Reviewers must record
the official PDSC/device page or a vendor release note that explicitly names
the SVD model. General family prefixes, an `X` wildcard, Cortex-M CPU type and
similar part numbers are insufficient evidence.

| Part | Qualifier | SVD model | Exact mapping evidence |
|---|---|---|---|
| `mspm0g3507` | none | `mspm0g350x` | TI MSPM0G1X0X_G3X0X device family pack and [Keil LP-MSPM0G3507 listing](https://www.keil.com/boards2/texasinstruments/lp_mspm0g3507/); exact PDSC mapping remains mandatory for a downloaded vendor pack |
| `nrf5340` | `cpuapp` | `nrf5340_application` | [NordicSemiconductor nRF_DeviceFamilyPack version history](https://www.keil.arm.com/packs/nrf_devicefamilypack-nordicsemiconductor/versions/) explicitly lists the `nrf5340_application.svd` and `nrf5340_network.svd` models; [Nordic dual-core description](https://nrfconnectdocs.nordicsemi.com/ncs/latest/tfm/platform/nordic_nrf/nrf5340dk_nrf5340_cpuapp/README.html) distinguishes application and network cores |
| `nrf5340` | `cpunet` | `nrf5340_network` | Same Nordic pack and core documentation; no core is selected if the qualified target or effective `CONFIG_BOARD_QUALIFIERS` disagrees |

For example, `nrf5340dk/nrf5340/cpuapp` must resolve
`nrf5340_application`, not the network SVD. Without an explicitly
verified core qualifier, the result is **AMBIGUOUS**. Generated
`CONFIG_SOC` and `CONFIG_BOARD_QUALIFIERS` are checked, and saved SVD
preferences are isolated by part **and** core qualifier.

### Vendor coverage and acceptance matrix

| Vendor | Local discovery source | Automatic pinned download | Parser fixture / hardware acceptance |
|---|---|---|---|
| Texas Instruments MSPM0 | CMSIS pack PDSC and the exact alias above | **Disabled:** former TI URL has no reviewed SHA-256 pin | Synthetic PDSC tests; XDS110 **pending** |
| Nordic nRF52/nRF53 | Official Nordic MDK/PDSC and qualified nRF5340 SVDs | Not configured | Bundled license-preserving real nRF52840 SVD subset plus core tests; physical **pending** |
| Espressif ESP32 | Installed SVD or published Espressif SVD | Eight exact models, immutable source commit and original digest pins retained | Checksum tests; ESP32 JTAG hardware **pending** |
| STMicroelectronics STM32 | Installed STM32 CMSIS DFP, exact device/PDSC | Not configured | Exact/PDSC parser tests; ST-Link hardware **pending** |
| Raspberry Pi RP2040 | Installed pico-sdk `RP2040.svd` | Not configured | Bundled license-preserving real RP2040 SVD subset (PLL/SYSINFO); full-file and probe **pending** |
| Microchip SAMD21 | Official SAMD21 DFP, exact PDSC mapping | Not configured | DFP fixture and physical CMSIS-DAP **pending** |
| Renesas RA | Installed FSP/DFP, exact PDSC mapping | Not configured | Vendor parser/hardware **pending** |
| Silicon Labs EFR32 | Installed Device Family Pack, exact PDSC mapping | Not configured | Vendor parser/hardware **pending** |

Vendor scan ordering only moves `modules/hal/<vendor>` folders ahead for
`stm32*`, `nrf*`, `esp32*`, `rp2*`, `samd*`, `ra*`, `efr32*`
and supported TI targets. It never determines a part number or authorizes
register access. Each root has a strict 10,000-entry scan budget plus
160,000 entries globally. A truncated root is named in `Result.inspected`,
subsequent roots can still be checked, and the result remains
**INCOMPLETE_SCAN** rather than trusting a partial discovery result.

### SHA-256 download catalog

`svd-download-catalog.json` replaces all hardcoded Espressif URL/digest
tables and the TI URL constant. `sha256` must be exactly 64 hexadecimal
characters to permit any network I/O. Original SHA-256 pins for the
eight Espressif models remain unchanged. The TI 1.3.1 pack entry retains
its former URL but has an empty checksum: **Get SVD will not download it**.
Manual installation of an official pack with verified PDSC mapping remains
available; this is not a bypass of the network trust policy. Every cache
hit and fresh download must be rehashed before acceptance; redirects and
archive sizes remain bounded.

### Generated Devicetree reg evidence

The linker accepts 1–8 explicit `<address size>` groups, with strict
32-bit cells or unambiguous zero-high-word 64-bit pairs. It never
interprets phandles or undocumented bus translations. Missing
`build/zephyr/zephyr.dts`, unknown paths, unparseable cells and absent
MMIO ranges are explained separately as **UNKNOWN**; these conditions
never claim a peripheral is active. All real MMIO reads, polling and
writes remain subject to the halted native DSF-GDB and SVD safety checks.

**Hardware-in-loop acceptance is pending for every vendor.** Tycho,
CodeQL and synthetic regression tests do not prove physical peripheral
register reads, writes or power-domain safety. Only Nordic nRF52840 and Raspberry Pi RP2040 currently have committed original-vendor XML subsets with their license text preserved (`tests/fixtures/svd-vendors`). STM32F103 and Microchip SAMD21 pack-sourced fixtures, full vendor file acceptance and hardware/driver qualification remain pending before claiming broad compatibility.

---

## Live register source integrity and target binding

When importing or auto-loading SVD definitions, the workbench now reads at most
32 MiB **once** from a regular non-symlink file. The CMSIS parser and SHA-256
fingerprint consume the **same in-memory bytes**, avoiding a race where edited
source files produce different map and fingerprint revisions.

Physical MMIO operations additionally require both an **exact SoC selected
from the qualified board or generated build metadata** and the **same named
project's active native DSF-GDB launch**. A manual SVD import without a verified build can be inspected
offline, but cannot authorise physical reads, live polling or writes. The
connection diagnostics report shows the verified build target and selection
failure reason, not fabricated register data.

For on-board checking with MSPM0G3507/XDS110: launch **Flash and Debug**
for the exact project and build, halt the target, open **Zephyr Hardware
Register Explorer**, select a known SVD-readable GPIO DIN register and click
**Refresh selected**. Open **More → Why no value? / Connection diagnostics**
to copy the exact DSF-GDB expression and reply. A write-only GPIO DOUT
register cannot be used as a read test. Live Watch is halted-only (1–8
registers, 250–60,000 ms). This documents a repeatable HIL procedure,
**not** a claim that board/probe verification has passed.

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

## Opt-in official vendor pack provisioning (MSPM0G3507)

The historical **Get Device Registers…** implementation referenced an official TI MSPM0G1X0X_G3X0X_DFP 1.3.1
CMSIS Device Family Pack from the Arm Keil distribution. It required consent
before contacting the vendor (this network path is now disabled without an approved digest) and caches the pack in the managed setup's
`vendor-svd` directory. A bounded HTTPS download and ZIP parse are followed
by an exact device/variant PDSC-to-SVD mapping check using the existing
SvdAutoDiscovery parser. The command fails closed on mismatched or ambiguous
descriptions; it does **not** generate register addresses from guesses or
access the physical target during provisioning.

The pack's SHA-256 is shown after installation for provenance. This is a
content fingerprint, **not** a substitute for a separately published and
trusted upstream checksum or signature. The downloaded pack remains vendor
content subject to its own license; it is not bundled into this repository.

The historical TI route is now disabled pending a trusted SHA-256 pin. Other SoCs
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
