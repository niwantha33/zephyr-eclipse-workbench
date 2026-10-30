// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.Map;

/** Read-only copyable template; never programs a pin or assumes package bonding. */
public final class GpioExampleCode {
    private GpioExampleCode() {}
    public static String selectedIndex(GpioBankEvidence.Bank bank, int bit, Map<String, String> labels) {
        if (bank == null || bit < 0 || bit >= bank.bitPositions())
            return "Select a GPIO controller and logical index to see an exact-controller C template.\n"
                    + "The complete examples/gpio_usage_demo application blinks the board's led0 alias.\n"
                    + "Nothing is written to your application automatically.";
        String label = labels.entrySet().stream().filter(e -> e.getValue().equals(bank.controllerPath()))
                .map(Map.Entry::getKey).sorted().findFirst().orElse("");
        if (!label.matches("[A-Za-z_][A-Za-z_0-9]*"))
            return "No usable node label for " + bank.controllerPath() + ".\n"
                    + "No DEVICE_DT_GET template was guessed. Use the board's symbolic DTS declaration.";
        return "/* CONFIG_GPIO=y in prj.conf. Template only: inspect the board schematic before use.\n"
                + " * " + bank.bitLabel(bit) + " is a controller index, not a package pad or availability claim. */\n"
                + "#include <errno.h>\n#include <zephyr/kernel.h>\n#include <zephyr/drivers/gpio.h>\n\n"
                + "int main(void)\n{\n"
                + "    const struct device *gpio_dev = DEVICE_DT_GET(DT_NODELABEL(" + label + "));\n\n"
                + "    if (!device_is_ready(gpio_dev)) {\n        return -ENODEV;\n    }\n"
                + "    int err = gpio_pin_configure(gpio_dev, " + bit + ", GPIO_OUTPUT_INACTIVE);\n"
                + "    if (err < 0) {\n        return err;\n    }\n"
                + "    while (1) {\n        err = gpio_pin_toggle(gpio_dev, " + bit + ");\n"
                + "        if (err < 0) {\n            return err;\n        }\n        k_msleep(500);\n    }\n}\n";
    }
}
