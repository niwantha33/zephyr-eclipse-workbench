/* SPDX-License-Identifier: Apache-2.0 */
#ifndef DEMO_CONFIG_H
#define DEMO_CONFIG_H

/* 1 = heavier workload, 0 = lighter workload. Both leave idle time. */
#define DEMO_INITIAL_LOAD_MODE 1U

/* Explicitly change to 1, rebuild and flash to allow deliberate fault tests.
 * Even then, no fault happens until fault_request is set through the debugger.
 */
#define DEMO_FAULT_TESTS_ENABLED 0

#endif
