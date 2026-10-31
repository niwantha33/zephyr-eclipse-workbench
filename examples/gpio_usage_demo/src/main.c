/* SPDX-License-Identifier: Apache-2.0 */
#include <errno.h>
#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>

#define LED0_NODE DT_ALIAS(led0)
#if !DT_NODE_HAS_STATUS(LED0_NODE, okay)
#error "This example requires an enabled led0 alias in the selected board Devicetree"
#endif

static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(LED0_NODE, gpios);

int main(void)
{
    if (!gpio_is_ready_dt(&led)) {
        return -ENODEV;
    }
    int err = gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
    if (err < 0) {
        return err;
    }

#if defined(CONFIG_GPIO_USAGE_DEMO_EXTRA_OUTPUT)
    /* Opt-in only: verify the physical circuit before driving GPIO A index 15. */
    const struct device *gpio_dev = DEVICE_DT_GET(DT_NODELABEL(gpioa));
    if (!device_is_ready(gpio_dev)) {
        return -ENODEV;
    }
    err = gpio_pin_configure(gpio_dev, 15, GPIO_OUTPUT_INACTIVE);
    if (err < 0) {
        return err;
    }
#endif

    while (1) {
        err = gpio_pin_toggle_dt(&led);
        if (err < 0) {
            return err;
        }
        k_msleep(500);
    }
}
