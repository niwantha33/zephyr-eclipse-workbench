/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Vendor-neutral, opt-in Zephyr 4.4 trace lifecycle build probe.
 * It performs no peripheral/flash IO, no probe commands and no RTT setup.
 * Runs on MSPM0, STM32, and other explicitly qualified !SMP Zephyr boards.
 */
#include <zephyr/kernel.h>
#include <stdint.h>

extern int zew_trace_stop_request(void);

volatile int zew_trace_stop_result = -1000;
volatile uint32_t zew_trace_probe_iterations;

static void worker(void *a, void *b, void *c)
{
    ARG_UNUSED(a);
    ARG_UNUSED(b);
    ARG_UNUSED(c);
    for (unsigned i = 0; i < 6U; ++i) {
        zew_trace_probe_iterations++;
        k_msleep(5);
    }
}
K_THREAD_DEFINE(zew_trace_worker_id, 768, worker,
                NULL, NULL, NULL, 5, 0, 0);

int main(void)
{
    /* Let the trace thread drain bounded scheduler events. */
    k_msleep(150);
    zew_trace_stop_result = zew_trace_stop_request();
    /* Stay alive for the async tracing thread to append END. */
    while (true) {
        k_msleep(1000);
    }
}
