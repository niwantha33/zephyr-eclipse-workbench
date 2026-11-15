/* SPDX-License-Identifier: Apache-2.0 */
#ifndef TEST_ZEPHYR_RANDOM_H_
#define TEST_ZEPHYR_RANDOM_H_
#include <stddef.h>
#include <stdint.h>
void sys_rand_get(void *dst, size_t n);
#endif
