/* SPDX-License-Identifier: Apache-2.0 */
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/printk.h>
#include <zephyr/tracing/tracing.h>
#include <stdint.h>
#include <stdio.h>
#include "demo_config.h"

LOG_MODULE_REGISTER(rtos_demo, LOG_LEVEL_INF);

/* Atomic counters are shared with main and the timer callback. */
atomic_t worker_a_count;
atomic_t worker_b_count;
atomic_t timer_irq_count;

/* These control words can be inspected/changed with the halted debugger. */
volatile uint32_t load_mode = DEMO_INITIAL_LOAD_MODE;
volatile uint32_t fault_request;
volatile uint32_t sample_signal;
volatile uint32_t demo_magic = 0x13579BDFU;

static void timer_expiry(struct k_timer *timer)
{
    ARG_UNUSED(timer);
    /* Timer expiry runs in interrupt context; keep it short. */
    atomic_inc(&timer_irq_count);
}

K_TIMER_DEFINE(demo_timer, timer_expiry, NULL);

static void worker_a(void *p1, void *p2, void *p3)
{
    ARG_UNUSED(p1);
    ARG_UNUSED(p2);
    ARG_UNUSED(p3);

    for (;;) {
        uint32_t mode = load_mode;
        uint32_t n = (uint32_t)atomic_inc(&worker_a_count) + 1U;

        sys_trace_named_event("a_begin", n, mode);
        k_busy_wait(mode ? 5000U : 1000U);
        sys_trace_named_event("a_end", n, mode);
        k_msleep(mode ? 45 : 99);
    }
}

static void worker_b(void *p1, void *p2, void *p3)
{
    ARG_UNUSED(p1);
    ARG_UNUSED(p2);
    ARG_UNUSED(p3);

    for (;;) {
        uint32_t mode = load_mode;
        uint32_t n = (uint32_t)atomic_inc(&worker_b_count) + 1U;

        sample_signal = n & 0xFFU;
        sys_trace_named_event("b_begin", n, sample_signal);
        k_busy_wait(mode ? 40000U : 5000U);
        sys_trace_named_event("b_end", n, sample_signal);
        k_msleep(mode ? 60 : 195);
    }
}

/* A has higher preemptive priority than B. Delayed start lets main name them. */
K_THREAD_DEFINE(worker_a_id, 1024, worker_a, NULL, NULL, NULL, 2, 0, 100);
K_THREAD_DEFINE(worker_b_id, 1024, worker_b, NULL, NULL, NULL, 3, 0, 100);

#if DEMO_FAULT_TESTS_ENABLED
__attribute__((noinline)) static void trigger_fault(uint32_t request)
{
    demo_magic = 0xFA170001U;
    sys_trace_named_event("fault_request", request, demo_magic);
    printk("DELIBERATE TEST: fault_request=%u\n", (unsigned int)request);

    if (request == 1U) {
        /* Portable Zephyr fatal path; this is NOT a hardware HardFault. */
        k_panic();
    }

#if defined(CONFIG_CPU_CORTEX_M)
    /* Undefined instruction. M0/M0+ escalates to HardFault. Larger Cortex-M
     * cores may deliver UsageFault or HardFault, depending on configuration.
     */
    __asm__ volatile ("udf #0");
    __builtin_unreachable();
#else
    printk("Undefined-instruction test is Cortex-M only; using k_panic.\n");
    k_panic();
#endif
}
#endif

int main(void)
{
    k_thread_name_set(k_current_get(), "demo_main");
    k_thread_name_set(worker_a_id, "worker_A");
    k_thread_name_set(worker_b_id, "worker_B");
    k_timer_start(&demo_timer, K_MSEC(100), K_MSEC(100));

    printk("RTOS analysis demo: fault tests %s\n",
           DEMO_FAULT_TESTS_ENABLED ? "ARMED (request still required)" : "disabled");
    LOG_INF("Normal run: A priority 2, B priority 3, timer every 100 ms");

    for (;;) {
        uint32_t a = (uint32_t)atomic_get(&worker_a_count);
        uint32_t b = (uint32_t)atomic_get(&worker_b_count);
        uint32_t irq = (uint32_t)atomic_get(&timer_irq_count);
        uint32_t request = fault_request;

        /* All three output styles are intentional test inputs. */
        printf("printf: A=%u B=%u IRQ=%u\n",
               (unsigned int)a, (unsigned int)b, (unsigned int)irq);
        printk("printk: mode=%u signal=%u\n",
               (unsigned int)load_mode, (unsigned int)sample_signal);
        LOG_INF("Counters A=%u B=%u IRQ=%u", a, b, irq);
        sys_trace_named_event("counter_sample", a, b);

        if (request != 0U) {
            fault_request = 0U;
#if DEMO_FAULT_TESTS_ENABLED
            if (request == 1U || request == 2U) {
                trigger_fault(request);
            } else {
                printk("Unknown fault request; use 1 or 2.\n");
            }
#else
            printk("Fault request ignored: enable DEMO_FAULT_TESTS_ENABLED first.\n");
#endif
        }

        k_msleep(1000);
    }
    return 0;
}
