// SPDX-License-Identifier: Apache-2.0
/*
 * Standalone ADC diagnostic application. This never flashes itself, changes
 * pinctrl at runtime or probes arbitrary channels. All ADC inputs come from
 * the selected board overlay's zephyr,user io-channels.
 */
#include <stddef.h>
#include <stdint.h>
#include <zephyr/kernel.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/devicetree.h>
#include <zephyr/sys/printk.h>
#include <zephyr/sys/util.h>

#if !DT_NODE_EXISTS(DT_PATH(zephyr_user)) || \
    !DT_NODE_HAS_PROP(DT_PATH(zephyr_user), io_channels)
#error "An explicit zephyr,user io-channels overlay is required"
#endif

#define CHANNEL_SPEC(node_id, prop, idx) ADC_DT_SPEC_GET_BY_IDX(node_id, idx),
static const struct adc_dt_spec channels[] = {
    DT_FOREACH_PROP_ELEM(DT_PATH(zephyr_user), io_channels, CHANNEL_SPEC)
};

int main(void)
{
    printk("ADC diagnostic: %u declared channels, five bounded sample cycles\n",
            (unsigned int)ARRAY_SIZE(channels));
    for (size_t i = 0; i < ARRAY_SIZE(channels); i++) {
        if (!adc_is_ready_dt(&channels[i])) {
            printk("ADC channel %u device not ready; diagnostic stopped\n", (unsigned int)i);
            return 1;
        }
        int error = adc_channel_setup_dt(&channels[i]);
        if (error != 0) {
            printk("ADC channel %u setup failed: %d\n", (unsigned int)i, error);
            return 1;
        }
    }

    for (unsigned int cycle = 0; cycle < 5; cycle++) {
        for (size_t i = 0; i < ARRAY_SIZE(channels); i++) {
            int16_t raw = 0;
            struct adc_sequence sequence = {
                .buffer = &raw,
                .buffer_size = sizeof(raw),
            };
            int error = adc_sequence_init_dt(&channels[i], &sequence);
            if (error == 0) {
                error = adc_read_dt(&channels[i], &sequence);
            }
            if (error) {
                printk("sample=%u channel=%u error=%d\n", cycle, (unsigned int)i, error);
            } else {
                printk("sample=%u channel=%u id=%u raw=%d\n", cycle,
                        (unsigned int)i, (unsigned int)channels[i].channel_id, raw);
            }
        }
        k_sleep(K_MSEC(500));
    }
    printk("ADC diagnostic complete; software success is not electrical verification\n");
    return 0;
}
