/* SPDX-License-Identifier: Apache-2.0 */
#ifndef TEST_TRACE_BACKEND_H_
#define TEST_TRACE_BACKEND_H_
#include <stdint.h>
struct tracing_backend;
struct tracing_backend_api {
    void (*init)(void);
    void (*output)(const struct tracing_backend *, uint8_t *, uint32_t);
};
struct tracing_backend {
    const struct tracing_backend_api *api;
};
#define TRACING_BACKEND_DEFINE(name, api) \
    static const struct tracing_backend name = { .api = &(api) }
#endif
