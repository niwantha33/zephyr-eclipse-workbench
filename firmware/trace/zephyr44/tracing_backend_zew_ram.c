/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Vendor-neutral, bounded, RAM-backed CTF wire recording (Zephyr 4.4 pin).
 * No debug probe, RTT module, UART peripheral or vendor address is required.
 * This is a finite captured BUFFER, not a live running-target transport.
 *
 * CTF hook -> Zephyr async trace ring -> trace worker -> framed RAM store.
 * Overflows poison this single epoch; never silently roll over/recover.
 */
#define DISABLE_SYSCALL_TRACING

#include <zephyr/kernel.h>
#include <zephyr/random/random.h>
#include <zephyr/sys/util.h>
#include <tracing_backend.h>
#include <tracing_core.h>
#include <stdint.h>
#include <string.h>

#include "zew_trace_frame.h"

BUILD_ASSERT(IS_ENABLED(CONFIG_TRACING_ASYNC), "RAM frame backend needs async tracing");
BUILD_ASSERT(IS_ENABLED(CONFIG_TRACING_CTF), "RAM frame backend needs Zephyr CTF");
BUILD_ASSERT(!IS_ENABLED(CONFIG_SMP), "Only single core epochs are supported");
BUILD_ASSERT(CONFIG_ZEW_TRACE_RAM_BYTES >= 1024, "RAM capture needs bounded storage");

enum {
    ZEW_TRACE_RAM_ERROR_NONE = 0,
    ZEW_TRACE_RAM_ERROR_HASH = 1,
    ZEW_TRACE_RAM_ERROR_INIT = 2,
    ZEW_TRACE_RAM_ERROR_OVERFLOW = 3,
    ZEW_TRACE_RAM_ERROR_SOURCE_LOSS = 4,
    ZEW_TRACE_RAM_ERROR_ENCODE = 5,
};

struct zew_trace_ram_receipt {
    uint32_t capacity;
    uint32_t committed;
    uint32_t ready;
    uint32_t ended;
    uint32_t error;
    uint32_t source_drops;
    uint32_t raw_ctf_bytes;
    uint32_t frames;
};

/* Public ELF names for *halted* debugger capture. Never claim running reads. */
uint8_t zew_trace_ram_bytes[CONFIG_ZEW_TRACE_RAM_BYTES];
volatile struct zew_trace_ram_receipt zew_trace_ram_receipt;
static struct zew_trace_encoder zew_ram_encoder;

static int hexdigit(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    return -1;
}

static bool parse_digest(const char *text, size_t n, uint8_t *out)
{
    if (n != 64U) {
        return false;
    }
    for (unsigned i = 0U; i < 32U; ++i) {
        int a = hexdigit(text[2U * i]);
        int b = hexdigit(text[2U * i + 1U]);

        if (a < 0 || b < 0) {
            return false;
        }
        out[i] = (uint8_t)((a << 4) | b);
    }
    return true;
}

static bool ram_emit(const uint8_t *frame, size_t size, void *context)
{
    uint32_t used = zew_trace_ram_receipt.committed;

    ARG_UNUSED(context);
    if (size > sizeof(zew_trace_ram_bytes) - used) {
        zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_OVERFLOW;
        return false;
    }
    memcpy(&zew_trace_ram_bytes[used], frame, size);
    /* Commit only after ALL bytes have been copied. Halted-read only. */
    zew_trace_ram_receipt.committed = used + (uint32_t)size;
    return true;
}

static void zew_trace_ram_init(void)
{
    uint8_t metadata[32];
    uint8_t image_contract[32];
    uint8_t epoch[16];

    memset((void *)&zew_trace_ram_receipt, 0, sizeof(zew_trace_ram_receipt));
    zew_trace_ram_receipt.capacity = sizeof(zew_trace_ram_bytes);
    memset(zew_trace_ram_bytes, 0, sizeof(zew_trace_ram_bytes));
    if (!parse_digest(CONFIG_ZEW_TRACE_METADATA_SHA256,
                      sizeof(CONFIG_ZEW_TRACE_METADATA_SHA256) - 1U, metadata)
        || !parse_digest(CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256,
                         sizeof(CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256) - 1U,
                         image_contract)) {
        zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_HASH;
        return;
    }
    /* Candidate marker only; not verified unique boot identity. */
    sys_rand_get(epoch, sizeof(epoch));
    if (!zew_trace_encoder_init(&zew_ram_encoder, epoch, image_contract,
                                metadata, ram_emit, NULL)
        || !zew_trace_encoder_send(&zew_ram_encoder, ZEW_TRACE_FRAME_HELLO,
                                   NULL, 0U, 0U)) {
        zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_INIT;
        return;
    }
    zew_trace_ram_receipt.frames = zew_ram_encoder.committed_frames;
    zew_trace_ram_receipt.ready = 1U;
}

static void zew_trace_ram_output(const struct tracing_backend *backend,
                                  uint8_t *data, uint32_t length)
{
    uint32_t drops;
    uint32_t offset = 0U;

    ARG_UNUSED(backend);
    if (!zew_trace_ram_receipt.ready || !length) {
        return;
    }
    if (data == NULL || length > (CONFIG_TRACING_BUFFER_SIZE + 1U)) {
        zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_ENCODE;
        zew_trace_ram_receipt.ready = 0U;
        return;
    }
    drops = tracing_packet_drop_count_get();
    zew_trace_ram_receipt.source_drops = drops;
    if (drops) {
        uint32_t count = MIN(length, ZEW_TRACE_FRAME_MAX_PAYLOAD);

        /* One loss-marked record if room remains; host fails closed. */
        (void)zew_trace_encoder_send(&zew_ram_encoder, ZEW_TRACE_FRAME_DATA,
                                     data, count, drops);
        zew_trace_ram_receipt.ready = 0U;
        zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_SOURCE_LOSS;
        return;
    }
    while (offset < length) {
        uint32_t n = MIN(length - offset, ZEW_TRACE_FRAME_MAX_PAYLOAD);

        if (!zew_trace_encoder_send(&zew_ram_encoder, ZEW_TRACE_FRAME_DATA,
                                    data + offset, n, 0U)) {
            zew_trace_ram_receipt.ready = 0U;
            if (!zew_trace_ram_receipt.error) {
                zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_ENCODE;
            }
            return;
        }
        offset += n;
        zew_trace_ram_receipt.raw_ctf_bytes += n;
        zew_trace_ram_receipt.frames = zew_ram_encoder.committed_frames;
    }
}

/*
 * Called ONLY from the Zephyr tracing worker after the core has disabled
 * future traced producers AND drained the entire async ring. No RTOS
 * reset, no additional debug connection, no busy wait, no silent resume.
 */
void zew_trace_backend_close(void)
{
    uint32_t drops = tracing_packet_drop_count_get();

    zew_trace_ram_receipt.source_drops = drops;
    if (!zew_trace_ram_receipt.ready || drops ||
        zew_trace_ram_receipt.raw_ctf_bytes == 0U) {
        zew_trace_ram_receipt.ready = 0U;
        if (drops && !zew_trace_ram_receipt.error) {
            zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_SOURCE_LOSS;
        }
        return;
    }
    if (!zew_trace_encoder_send(&zew_ram_encoder, ZEW_TRACE_FRAME_END,
                                NULL, 0U, 0U)) {
        zew_trace_ram_receipt.ready = 0U;
        if (!zew_trace_ram_receipt.error) {
            zew_trace_ram_receipt.error = ZEW_TRACE_RAM_ERROR_ENCODE;
        }
        return;
    }
    zew_trace_ram_receipt.frames = zew_ram_encoder.committed_frames;
    zew_trace_ram_receipt.ended = zew_trace_encoder_complete(&zew_ram_encoder);
    zew_trace_ram_receipt.ready = 0U;
}

static const struct tracing_backend_api zew_trace_ram_api = {
    .init = zew_trace_ram_init,
    .output = zew_trace_ram_output,
};

TRACING_BACKEND_DEFINE(tracing_backend_zew_ram, zew_trace_ram_api);
