/* SPDX-License-Identifier: Apache-2.0 */
#ifndef TEST_ZEPHYR_UTIL_H_
#define TEST_ZEPHYR_UTIL_H_
#define BUILD_ASSERT(test, msg) _Static_assert((test), msg)
#define IS_ENABLED(x) (x)
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif
