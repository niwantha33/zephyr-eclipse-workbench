<!-- SPDX-License-Identifier: Apache-2.0 -->
# Hardware Studio — Start Peripheral (cross-SoC DTS-first onboarding)

Zephyr users should not need to memorise phandles, aliases, channel child nodes,
bindings and driver APIs before starting. Hardware Studio's **Start peripheral**
tab provides a navigation/learning workflow grounded in the **exact selected
board/SoC and its generated `zephyr.dts`**, instead of a hardcoded vendor pin
table.

## Navigate

1. Build the exact selected board/profile so that `zephyr/zephyr.dts` and
   `zephyr/.config` exist.
2. Open **Hardware → Start ADC reading →** or **Start selected peripheral →**.
   Alternatively select the category in the **Start peripheral** tab.
3. **Find controller in DTS** navigates to the real generated DTS inventory.
   **Inspect generated DTS** shows its declaration in the read-only generated
   tree. `prj.conf`, `app.overlay`, and `src/main.c` can be opened directly.
   The tool does not create or overwrite the application.
4. Review the guide's steps: generated controller path + line, compatible,
   effective status (including disabled ancestors), requested driver Kconfig,
   `reg`, interrupts, clocks, pinctrl and binding constraints.
5. For an ADC read, understand the 4 distinct pieces:
   - SoC controller node; possibly a disabled `status` in `.dtsi`.
   - A child `channel@N` with gain/reference/acquisition-time/resolution
     when required by the actual Zephyr ADC binding.
   - `/zephyr,user` `io-channels` phandle connection to the exact controller
     and input, not an arbitrary guessed pin.
   - Application API `ADC_DT_SPEC_GET_BY_IDX`,
     `adc_channel_setup_dt`, `adc_sequence_init_dt`, `adc_read_dt`.
6. Only when last-generated DTS supplies a unique input mapping for the selected
   ADC controller with supported one-cell phandle format **and** its channel
   configuration, the editor shows a read-only **C scaffold**. Even then it
   is **not compile/board/pin validated**: check channel resolution, sampling
   buffer type, pin mux, Kconfig, driver and physical connection. Review and
   build before flash. The value is **raw ADC storage**, not volts.
7. For I2C/SPI/UART/CAN, navigation works for any named compatible-bearing
   controller in generated DTS; the guide deliberately directs users to
   the upstream sample and binding **instead of inventing a bus client or
   SPI slave configuration**. More guided, binding-verified examples can
   be added without broadening evidence claims.

## Explicit states

- `BUILD_REQUIRED`: missing or stale generated DTS/Kconfig — no example.
- `NOT_IN_DTS`: the SoC hardware may support a peripheral but the selected
  Zephyr DTS has **no controller**. Requires Zephyr DTS/binding/driver support.
  Never fabricate controller nodes or electrical pin mappings.
- `DTS_DISABLED`: controller exists but is disabled by its own/ancestor status.
  Review enabling requirements with the binding; no code scaffold.
- `SETUP_REQUIRED`: ADC exists but no exact `zephyr,user` `io-channels`
  reference, incomplete channel settings, or unsupported phandle format.
  No invented channel, gain or reference.
- `ADC_WIRING_FOUND`: a specific phandle/input/index maps to a complete
  generated ADC child configuration. Read-only C reference is available;
  driver, pin and successful compile must still be independently verified.
- `INSPECT_BINDING`: another peripheral controller exists, but app-level
  client setup is device-specific and needs its binding.

## Important technical distinction

The exact TI MSPM0G3507 silicon includes ADC/I2C/SPI, but the Zephyr v4.4.0
SoC DTS used in some projects does **not** describe those as controllers.
The guide must say `NOT_IN_DTS`, never imply the chip lacks ADC or offer a
buildable ADC app by inventing a `&adc0` label. The existing exact-part vendor
SVD discovery and separately sourced silicon catalogue provide hardware
reference evidence, **not Zephyr driver integration**.

The reference ADC API sequence follows upstream Zephyr v4.4.0:
https://github.com/zephyrproject-rtos/zephyr/tree/v4.4.0/samples/drivers/adc/adc_dt

For cross-vendor behavior, the generated-DTS parser and guide intentionally
depend on compatible, exact paths, phandle properties and status, **not**
hardcoded addresses, alias numbers or GPIO pads. Unsupported multi-cell
`io-channels` formats fail closed; users can inspect the binding and build.

## Release qualification

Regression tests cover missing/stale/disabled ADC, exact matching phandles,
distinct I/O channel indexes, mandatory ADC child properties, unsupported
specifier shapes, missing I2C/SPI/CAN and valid UART controller guidance.
Installed native SWT smoke exercises ADC, UART and I2C navigation. Physical
ADC input/electrical tests and exact-board compile validation remain separate
release gates; never claim runtime hardware use from a generated DTS alone.
