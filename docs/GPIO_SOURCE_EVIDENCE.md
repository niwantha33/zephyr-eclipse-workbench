<!-- SPDX-License-Identifier: Apache-2.0 -->
# GPIO C source candidates (read-only)

The GPIO bank view already displayed **compiled Devicetree consumer references** such
as the LP-MSPM0G3507 `led0` alias pointing to GPIO B bit 22. It could not show
literal calls made in C source like:

```c
const struct device *gpio_dev = device_get_binding("GPIO_0");
gpio_pin_configure(gpio_dev, 15, GPIO_OUTPUT_ACTIVE);
```

These are *source candidates*, not generated-DTS consumers and never a live
pin-level/direction reading.

## Behavior

- Reads a bounded set of saved application C/C++ source files, not build outputs
  or generated code. No source mutation, build invocation, code execution, or
  remote service.
- Finds literal `device_get_binding("NAME")` pointer assignments followed by
  literal `gpio_pin_configure(pointer, bit, flags)` calls in the same source
  file. Ignores comments and unparseable calls.
- A device name is resolved only if exactly one **enabled GPIO** controller
  in the selected generated Devicetree has a matching `label` property or
  full node name. Device naming follows Zephyr's `DEVICE_DT_NAME` behavior.
- If `"GPIO_0"` is not an exact match, show **UNRESOLVED DEVICE NAME** with
  source file, line, bit and flags. Never guess that it means GPIO A, or
  colour PA15.
- Where the controller is uniquely resolved, mark the logical register
  index with **C**. Preserve the separate compiled-DTS visual treatment.
- The validation/evidence dock lists both resolved and unresolved source
  candidates. Selecting a logical bit shows source evidence and the
  corresponding DTS consumers. No file is changed.

## Limits

This is a bounded **text scanner**, not CDT AST/CFG/whole-program data-flow or
a compiler. It cannot prove whether a branch executes, that
`device_get_binding` returns a non-null value, what a pointer denotes after
arbitrary aliasing, or whether the GPIO call succeeds. Dynamic names, pointers,
macros, and nonsimple expressions may remain unreported. Files that are unsaved
in an Eclipse editor are not covered; save before refreshing.

It does **not** infer package bonding, alternate functions, pin conflicts,
direction, logic level, output drivers or runtime state. Kconfig enabling
`CONFIG_GPIO`, `CONFIG_GPIO_GET_CONFIG` and `CONFIG_GPIO_GET_DIRECTION`
does not constitute a live pin capture. Real live status would require a
verified probe/debug session and target-specific driver/register evidence.

## Safer user-source pattern

Check the pointer before use and check `gpio_pin_configure`'s result.
Where available prefer an explicit `DEVICE_DT_GET(DT_NODELABEL(gpioa))`
reference with `device_is_ready` to a legacy name string. Confirm the exact
node label from the selected target before adopting it.

## Release acceptance

Core tests check unresolved `GPIO_0`, exact label matches, comment exclusion,
pointer reassignment and exclusion of build-generated source. Native
Eclipse smoke creates a source fixture and verifies that GPIO A gets a
source-candidate at bit 15 while GPIO B retains its independent LED DTS
reference. A screenshot `gpio-source-candidate.png` is captured from the
installed UI.

Keep this PR draft until Linux/Maven/p2, real Eclipse SWT tests, and the
Windows firmware matrix pass on the exact commit. Manual hardware and
source-compiler verification remains a separate acceptance gate.
