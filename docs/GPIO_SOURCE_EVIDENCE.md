<!-- SPDX-License-Identifier: Apache-2.0 -->
# GPIO saved-source patterns (not usage evidence)

The source scanner reads bounded **saved** application C/C++ files for literal
calls such as `device_get_binding("GPIO_0")` followed by
`gpio_pin_configure(dev, 15, GPIO_OUTPUT_ACTIVE)` and common
`gpio_dt_spec` patterns. It does not execute the code and does not check
actual compiler inputs, preprocessor configuration, link-time removal,
execution reachability or whether the call succeeded.

The default GPIO planner **does not mark any bit** from this scanner.
Users may enable the **Unverified C patterns** overlay, which displays
`C?` on neutral cells for exact-controller matches only. The GPIO Evidence
table keeps the saved source file and line, flags, and unresolved bindings.
Unresolved `GPIO_0` must never be guessed as PA15.

A board DTS consumer such as `led0`/PB22 is displayed only as a **board
declaration**, never as use. A `gpio_pin_configure_dt` reference to the
LED is still an unverified source hint, even if the DTS lookup is exact.
For example, code under an inactive `#if defined(CONFIG_...)` block may
exist on disk yet not be part of the built binary.

The scanner neither infers live direction/level nor physical pad bonding.
A separate, verified binary and target analysis is required before
reporting actual application usage or live hardware state. The existing
source boundedness, comment filtering, pointer-reassignment checks and
build-directory exclusions are preserved.
