<!-- SPDX-License-Identifier: Apache-2.0 -->
# Hardware Studio peripheral coverage: Zephyr DTS vs actual silicon

The workbench must never claim that an MCU lacks a peripheral merely because
the currently selected **Zephyr version/board generated Devicetree** omits it.

## Two independent evidence sources

| Source | What it establishes | What it does not establish |
| --- | --- | --- |
| `<build>/zephyr/zephyr.dts` | Actual described nodes for the selected build, compatible, local/inherited `status`, bus paths and declared properties | The complete silicon feature set, pin availability, compiled driver, observed runtime state |
| Vendor **exact part** CMSIS-SVD via SoC Registers | Names/addresses/register layout for peripherals represented in a validated exact-part vendor device SVD | Zephyr integration, Kconfig/driver availability, DTS node bindings, pad mux or package connections |

**Hardware → Peripheral Explorer** now distinguishes:
- **DTS okay/default:** a controller node exists and the node and all its
  ancestors are not disabled. This is not driver compilation or runtime use.
- **DTS disabled/inherited:** node exists but its local or ancestor status
  disables it. The view retains it when All nodes is selected.
- **Not described in selected build DTS:** no matching node is present in
  the generated tree. Silicon may still implement it. Never invent a
  `status = "okay"` overlay for a node absent from upstream DTS bindings.
- **Filtered out:** matching nodes exist, but the selected search/status filter
  excludes them. Reset with **Show all DTS controllers**.
- **Build required:** source changes newer than generated evidence mean
  rebuild first. No new hardware capability is inferred.

The category selector includes ADC, DAC, GPIO, UART, I2C, SPI, I2S,
PWM, Timers, DMA, CAN, USB, RTC, Watchdog, Ethernet and Pinctrl.
These are navigation labels for real nodes, not claims that each SoC
includes or supports each feature. CAN-FD and vendor timer-PWM bindings are
classified by compatible without counting pinctrl state names or LED consumers
as controllers.

**Silicon / vendor** is a *separate source tab* in the Hardware explorer.
For the exact `lp_mspm0g3507/mspm0g3507` target it presents a
reviewed, source-linked TI published feature summary: ADC **2 units**,
DAC **1**, UART **4**, I2C **2**, SPI **2**, CAN-FD **1**, timers **7**,
one DMA controller with 7 channels, and **up to 22 PWM channels**
(the last is explicitly *not* 22 PWM controllers). Its right-hand
column compares the actual selected generated-DTS node count, which may be
zero even though silicon exists. Clicking a feature explains the difference
or selects its *real* DTS nodes. Package availability remains unverified.
For other SoCs there is no guessed catalogue; exact vendor SVD discovery
remains available.

**Exact silicon catalogue (SVD)…** opens the existing offline SoC Register
view and attempts exact-device SVD discovery from installed local vendor
SDKs and CMSIS packs. If the exact part is missing or ambiguous, the UI
reports that explicitly instead of showing fabricated peripherals. A vendor
register block must not be offered as a ready-to-build Zephyr device.

## Specific MSPM0G3507 example (Zephyr v4.4.0)

Upstream source chain:
- `boards/ti/lp_mspm0g3507/lp_mspm0g3507.dts`
- `dts/arm/ti/mspm0/g/mspm0g3507.dtsi`
- `dts/arm/ti/mspm0/g/mspm0g350x.dtsi`
- `dts/arm/ti/mspm0/g/mspm0g.dtsi`
- `dts/arm/ti/mspm0/mspm0.dtsi`

The upstream **v4.4.0** SoC DTS describes UART controllers
(UART0 enabled by the board; additional UARTs disabled by default),
GPIO banks, CAN-FD, timers and other nodes. Some chip silicon
peripherals, including ADC, I2C and SPI, are **not described as Zephyr
controller nodes in these v4.4.0 DTS files**. The workbench cannot
safely manufacture the missing Zephyr driver/DTS integration. Check
the currently installed Zephyr version, binding/driver support, and
any upstream changes. Chip capability and Zephyr application support
are different claims.

Upstream references:
- https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/boards/ti/lp_mspm0g3507/lp_mspm0g3507.dts
- https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/dts/arm/ti/mspm0/g/mspm0g.dtsi
- https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/dts/arm/ti/mspm0/mspm0.dtsi
- https://docs.zephyrproject.org/latest/build/dts/troubleshooting.html

## Acceptance criteria

- Real UART0, disabled UART1 and CAN-FD from a generated-tree-style
  fixture appear in the correct categories with correct DTS statuses.
- A disabled SPI fixture is distinguishable from a missing ADC or I2C
  DTS node. A search/status filter cannot cause a false missing-silicon
  conclusion.
- No generic ADC/SPI/I2C block is fabricated from a device family's name.
- Disabled ancestors propagate down to child nodes.
- The installed Eclipse SWT smoke inspects actual categories/empty messaging.
- Existing GPIO/SVD/debug views, reviewed overlay workflow and normal
  firmware builds remain independently functional.
- Only successful Linux and Windows packaging and physical board validation
  can qualify the change for a production release.
