# GPIO usage and Configuration Studio example

This real Zephyr application blinks the selected board's `led0` alias. It checks
controller readiness and every GPIO return value. It does not use the unreliable
`device_get_binding("GPIO_0")` name or guess that a device name means GPIO A.

1. Import this directory as an existing Zephyr application.
2. Select the exact board, for example `lp_mspm0g3507/mspm0g3507`, and Build.
3. Open Configuration Studio > Hardware > GPIO. The **GPIO Usage** tab lists
   the built LED consumer on its actual controller. Numeric and symbolic
   generated DTS references are supported. Selecting its row selects its bank.
4. Select a logical index in the planner, then **GPIO Example C** to inspect
   and copy a template using that bank's exact node label. Nothing is saved
   automatically. The index is not a physical package pad or a free-pin claim.
5. Flash this build, then Debug. For blinking, a source breakpoint on
   `gpio_pin_toggle_dt()` is an executable line; the target must be resumed
   to hit it.

CLI alternative from a configured West workspace:

```sh
west build -b lp_mspm0g3507/mspm0g3507 /path/to/examples/gpio_usage_demo -d build/gpio_usage_demo
west flash -d build/gpio_usage_demo
west debug -d build/gpio_usage_demo
```

The optional `CONFIG_GPIO_USAGE_DEMO_EXTRA_OUTPUT=y` configures GPIO A logical
index 15 with an exact `DT_NODELABEL(gpioa)` getter and readiness/error checks.
It is **off by default**. Enable it only after checking the schematic, package,
attached electronics, ownership and electrical requirements. It drives the pin;
no claim is made that index 15 is free or bonded on every MSPM0 package.

The Studio C inventory inspects saved source text. It can include inactive
`#if` branches or uncompiled files: a C candidate is not an executed call or
compiler-verified use. Built DTS consumers are shown separately. Save files
and click Refresh after an edit; rebuild after Devicetree changes.
