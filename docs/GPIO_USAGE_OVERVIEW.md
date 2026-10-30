<!-- SPDX-License-Identifier: Apache-2.0 -->
# GPIO Usage — cross-bank visibility

The GPIO bank screen previously showed only **references to the selected
controller**, so selecting PA made the board's PB22 LED disappear. C calls
using `device_get_binding("GPIO_0")` were also hidden unless the name matched
an exact enabled Devicetree device.

Hardware → **GPIO** now automatically opens the bottom **GPIO Usage** tab
containing a read-only table with four columns:
- **GPIO / bit:** PA15/PB22 only when an exact controller is known; otherwise
  `Unresolved bit 15`, never a fabricated PA label.
- **Evidence:** `DTS consumer`, `C source candidate`, or
  `C unresolved device`.
- **Reference:** compiled consumer node and property, or literal C call.
- **Location:** generated zephyr.dts line or saved application source line
  and literal device-binding string.

The summary counts **all GPIO controllers in the selected build** rather than
only the currently selected bank. Selecting a grounded row navigates to its
controller and highlights its logical bit. Selecting an unresolved row
explains why the source call cannot be mapped and does not highlight any bank.

## Example

Given the board's compiled Blue LED GPIO specifier at PB22 and source

```c
const struct device *gpio_dev = device_get_binding("GPIO_0");
gpio_pin_configure(gpio_dev, 15, GPIO_OUTPUT_ACTIVE);
```

the table lists **PB22 (DTS consumer)** and an **unresolved bit 15 C
candidate** if `GPIO_0` is not a unique device name in built DTS. It never
claims that the unresolved C call sets PA15. When the built DTS explicitly
names the target GPIO controller `GPIO_0`, it instead shows **PA15 (C source
candidate)**. A source call is not proof of execution or GPIO state.

The previously existing Changes, Validation, and Generated DTS tabs are
preserved. No source file, overlay, firmware or device is changed by this view.
Kconfig GPIO options are not live pin measurements.

## Verification gates

- Headless GPIO evidence tests.
- Native Eclipse UI integration with fixture containing PB22 DTS use,
  PA15 exact-labelled C source, and an unresolved device-name call.
- Native screenshot `gpio-used-overview.png`.
- Windows firmware matrix and manual review of actual target build before
  release approval.
