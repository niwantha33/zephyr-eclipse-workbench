<!-- SPDX-License-Identifier: Apache-2.0 -->
# Bounded ADC diagnostic — nRF52840 DK, Zephyr 4.4.0

This is a **standalone diagnostic firmware**, not a patch to an existing application.
No programming is started by merely opening or building this project. Flash it only
after reviewing the board revision, hardware connections and the exact target.

The initial, explicitly supported target is **nrf52840dk/nrf52840**. Its overlay
is derived from the upstream Zephyr v4.4.0 ADC Devicetree sample (Apache-2.0,
Nordic Semiconductor ASA copyright preserved). The known io-channels correspond
to channels 0, 1 and 7. The mapping includes SAADC AIN1/P0.03 and
AIN6/P0.30 / AIN7/P0.31 for the board's SoC and internal VDD input.
These are **SoC pad identifiers, not an invented physical header/package map**.
Confirm the exact board schematics and electrical input limits before wiring.
The example does not apply to a different board, package or revision without review.

## Workflow

1. Import this folder using File > Import > Zephyr > Existing Zephyr Application.
2. Select **nrf52840dk/nrf52840**, an appropriate Debug build and its source overlay.
3. In Configuration Studio > Hardware, inspect the selected ADC node and channel
   parameters (gain, reference, resolution, acquisition time and pin route).
4. Build. The code requires a real zephyr,user io-channels binding and cannot
   silently guess an ADC channel.
5. When safe and explicitly intended, flash your physical board using its
   supported runner. This **replaces the currently installed firmware**.
6. Inspect the serial console: five bounded sampling cycles are printed,
   with an explicit result/error for each channel.

Command-line reproducibility (inside a prepared Zephyr v4.4.0 workspace):

```sh
west build -b nrf52840dk/nrf52840 -d build .
```

The CI firmware matrix compiles this application on Windows using the managed
Zephyr 4.4.0 toolchain. A successful CI build does **not** establish ADC signal
accuracy, pad voltage safety, connected probe support or a physical test result.

A reported ADC read is a software observation. Electrical validation requires a
known reference voltage, calibrated source, wiring/probe revision, measured
raw count and expected tolerance recorded by the operator. Do not connect
unknown voltages or automatically scan unrelated GPIO pins.
