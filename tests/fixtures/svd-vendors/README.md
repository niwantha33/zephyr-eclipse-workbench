# Trimmed vendor SVD sources

These are **subsets of real upstream vendor XML**, not generated register data.
Only selected complete `<peripheral>` elements were retained; the original XML
device header, original copyright and license text remain intact.
The fixtures are used **only in offline parser tests**, never to authorize
physical register access or to replace the complete vendor SVD.

| Fixture | Official upstream source | Git blob | License retained | Included original peripherals |
|---|---|---|---|---|
| `nrf52840_trimmed.svd` | [Nordic nrfx MDK nRF52840 SVD](https://github.com/nordicsemi/nrfx/blob/5822caf07442042310103c391dd91cbcaaaa1beb/bsp/stable/mdk/nrf52/nrf52840/nrf52840.svd) | `739949b39ab286ece4cd6c54919d70238dc1a957` | BSD-3-Clause `<licenseText>` | APPROTECT, SWI0, SWI1 (derivedFrom) |
| `rp2040_trimmed.svd` | [Raspberry Pi pico-sdk RP2040 SVD](https://github.com/raspberrypi/pico-sdk/blob/079c6f39023649b154152db30f1d781e884879bc/src/rp2040/hardware_regs/RP2040.svd) | `4c4e249ca06f6c5750c7bf5d3dd40eeb7ecab24e` | BSD-3-Clause header and licenseText | PLL_SYS, PLL_USB (derivedFrom), SYSINFO, TBMAN |

**Not yet included:** a reviewable official STM32F103 DFP/PDSC excerpt and
Microchip SAMD21 DFP SVD excerpt. Existing tests exercise **synthetic** exact
PDSC-to-family SVD mappings, not genuine pack acceptance for those two vendors.
Do not label them as real-vendor fixture passes until official distributable
source files have been extracted, licensed and committed with traceable blob
or pack hashes.

Hardware-in-loop acceptance is pending for **all** vendors.
