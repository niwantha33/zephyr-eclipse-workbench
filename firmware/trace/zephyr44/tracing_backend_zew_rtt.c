/* SPDX-License-Identifier: Apache-2.0 */
/*
 * EXPLICIT OPT-IN: proposed Zephyr 4.4.0-specific CTF binary RTT backend.
 * This file is not built by default. It requires exact pinned-Zephyr patch
 * integration and real probe qualification before any Workbench live feature.
 *
 * Zephyr's CONFIG_TRACING_ASYNC ring is the producer/consumer handoff:
 * IRQ/CTF hooks only enqueue bounded records. This backend is invoked by
 * the Zephyr tracing thread, never by the synchronous ISR hook path.
 *
 * No target scanning, dynamic allocation, implicit channel fallback,
 * console multiplexing, blocking-write mode or automatic session restart.
 */
#define DISABLE_SYSCALL_TRACING

#include <zephyr/kernel.h>
#include <zephyr/random/random.h>
#include <zephyr/sys/util.h>
#include <SEGGER_RTT.h>
#include <tracing_backend.h>
#include <tracing_core.h>
#include <stdint.h>
#include <string.h>

#include "zew_trace_frame.h"

BUILD_ASSERT(IS_ENABLED(CONFIG_TRACING_ASYNC), "ZEW RTT requires async CTF tracing");
BUILD_ASSERT(IS_ENABLED(CONFIG_TRACING_CTF), "ZEW RTT requires Zephyr CTF format");
BUILD_ASSERT(!IS_ENABLED(CONFIG_SMP), "Multi-core clock domains are not supported");
BUILD_ASSERT(CONFIG_SEGGER_RTT_MAX_NUM_UP_BUFFERS >= 2,
             "Dedicated RTT up channel 1 must exist");
BUILD_ASSERT(CONFIG_ZEW_TRACE_RTT_UP_BYTES >= 1024,
             "RTT ring must fit one complete atomic CTF frame");

enum {
    ZEW_TRACE_RTT_CHANNEL = 1,
    ZEW_TRACE_ERROR_NONE = 0,
    ZEW_TRACE_ERROR_DIGEST = 1,
    ZEW_TRACE_ERROR_CHANNEL = 2,
    ZEW_TRACE_ERROR_INIT = 3,
    ZEW_TRACE_ERROR_SOURCE_LOSS = 4,
    ZEW_TRACE_ERROR_RTT_FULL = 5,
    ZEW_TRACE_ERROR_FRAME = 6,
};

struct zew_trace_rtt_diagnostic {
    uint32_t channel;
    uint32_t ready;
    uint32_t error;
    uint32_t frames;
    uint32_t raw_bytes;
    uint32_t source_drops;
};

/* Halted debugger evidence only; this is not a stable running-time counter API. */
volatile struct zew_trace_rtt_diagnostic zew_trace_rtt_diagnostic;

static uint8_t dedicated_up_buffer[CONFIG_ZEW_TRACE_RTT_UP_BYTES];
static struct zew_trace_encoder encoder;

static int nibble(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    return -1; /* No ambiguous uppercase/delimited digest input. */
}

static bool parse_hex(const char *src, size_t src_len, uint8_t *dst, size_t dst_len)
{
    if (src_len != dst_len * 2U) {
        return false;
    }
    for (size_t i = 0U; i < dst_len; ++i) {
        int high = nibble(src[2U * i]);
        int low = nibble(src[2U * i + 1U]);

        if (high < 0 || low < 0) {
            return false;
        }
        dst[i] = (uint8_t)((high << 4) | low);
    }
    return true;
}

static bool emit_rtt(const uint8_t *frame, size_t size, void *ctx)
{
    ARG_UNUSED(ctx);
    /*
     * Exact dedicated channel 1 was configured NO_BLOCK_SKIP, where writes
     * either fit entirely or return 0. A partial write (if ever returned)
     * is treated as a poisoned stream, never retried at a new byte offset.
     */
    unsigned sent = SEGGER_RTT_Write(ZEW_TRACE_RTT_CHANNEL, frame, (unsigned)size);

    if (sent != size) {
        zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_RTT_FULL;
        return false;
    }
    return true;
}

static void zew_trace_rtt_init(void)
{
    uint8_t metadata[32];
    uint8_t image_contract[32];
    uint8_t epoch[16];
    int channel;

    memset((void *)&zew_trace_rtt_diagnostic, 0,
           sizeof(zew_trace_rtt_diagnostic));
    zew_trace_rtt_diagnostic.channel = UINT32_MAX;

    if (!parse_hex(CONFIG_ZEW_TRACE_METADATA_SHA256,
                   sizeof(CONFIG_ZEW_TRACE_METADATA_SHA256) - 1U,
                   metadata, sizeof(metadata))
        || !parse_hex(CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256,
                      sizeof(CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256) - 1U,
                      image_contract, sizeof(image_contract))) {
        zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_DIGEST;
        return;
    }
    /*
     * No claim is made about the strength or uniqueness of sys_rand_get on
     * a particular SoC. Production boot-epoch proof remains REQUIRED.
     */
    sys_rand_get(epoch, sizeof(epoch));
    if (!zew_trace_encoder_init(&encoder, epoch, image_contract, metadata,
                                emit_rtt, NULL)) {
        zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_INIT;
        return;
    }
    /* AllocUpBuffer performs INIT() if necessary; do NOT call
     * SEGGER_RTT_Init(), which could reset a pre-existing text console. */
    channel = SEGGER_RTT_AllocUpBuffer(
            "ZEW-CTF-V1", dedicated_up_buffer, sizeof(dedicated_up_buffer),
            SEGGER_RTT_MODE_NO_BLOCK_SKIP);
    if (channel != ZEW_TRACE_RTT_CHANNEL) {
        zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_CHANNEL;
        return;
    }
    zew_trace_rtt_diagnostic.channel = (uint32_t)channel;
    if (!zew_trace_encoder_send(&encoder, ZEW_TRACE_FRAME_HELLO, NULL, 0U, 0U)) {
        zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_FRAME;
        return;
    }
    zew_trace_rtt_diagnostic.frames = encoder.committed_frames;
    zew_trace_rtt_diagnostic.ready = 1U;
}

static void zew_trace_rtt_output(const struct tracing_backend *backend,
                                  uint8_t *data, uint32_t length)
{
    uint32_t drops;
    uint32_t offset = 0U;

    ARG_UNUSED(backend);
    if (zew_trace_rtt_diagnostic.ready == 0U) {
        return;
    }
    if (length == 0U) {
        return;
    }
    if (data == NULL || length > (CONFIG_TRACING_BUFFER_SIZE + 1U)) {
        zew_trace_rtt_diagnostic.ready = 0U;
        zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_FRAME;
        return;
    }
    /*
     * Zephyr's async ring can overflow BEFORE the backend is invoked;
     * read the actual pinned tracing-core atomic drop counter.
     * No rebaseline/recovery: any loss invalidates the epoch.
     */
    drops = tracing_packet_drop_count_get();
    zew_trace_rtt_diagnostic.source_drops = drops;
    if (drops != 0U) {
        uint32_t n = MIN(length, (uint32_t)ZEW_TRACE_FRAME_MAX_PAYLOAD);

        (void)zew_trace_encoder_send(&encoder, ZEW_TRACE_FRAME_DATA,
                                     data, n, drops);
        zew_trace_rtt_diagnostic.frames = encoder.committed_frames;
        zew_trace_rtt_diagnostic.ready = 0U;
        zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_SOURCE_LOSS;
        return;
    }
    while (offset < length) {
        uint32_t n = MIN(length - offset,
                         (uint32_t)ZEW_TRACE_FRAME_MAX_PAYLOAD);

        if (!zew_trace_encoder_send(&encoder, ZEW_TRACE_FRAME_DATA,
                                    data + offset, n, 0U)) {
            zew_trace_rtt_diagnostic.ready = 0U;
            if (zew_trace_rtt_diagnostic.error == ZEW_TRACE_ERROR_NONE) {
                zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_FRAME;
            }
            return;
        }
        offset += n;
        zew_trace_rtt_diagnostic.raw_bytes += n;
        zew_trace_rtt_diagnostic.frames = encoder.committed_frames;
    }
}

/* The tracing thread calls this ONLY after producers are disabled and
 * the entire Zephyr async ring is drained. No target reset or polling. */
void zew_trace_backend_close(void)
{
    uint32_t drops = tracing_packet_drop_count_get();

    zew_trace_rtt_diagnostic.source_drops = drops;
    if (!zew_trace_rtt_diagnostic.ready || drops ||
        zew_trace_rtt_diagnostic.raw_bytes == 0U) {
        zew_trace_rtt_diagnostic.ready = 0U;
        if (drops) {
            zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_SOURCE_LOSS;
        } else if (zew_trace_rtt_diagnostic.error == ZEW_TRACE_ERROR_NONE) {
            zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_FRAME;
        }
        return;
    }
    if (!zew_trace_encoder_send(&encoder, ZEW_TRACE_FRAME_END,
                                NULL, 0U, 0U)) {
        zew_trace_rtt_diagnostic.ready = 0U;
        if (zew_trace_rtt_diagnostic.error == ZEW_TRACE_ERROR_NONE) {
            zew_trace_rtt_diagnostic.error = ZEW_TRACE_ERROR_FRAME;
        }
        return;
    }
    zew_trace_rtt_diagnostic.frames = encoder.committed_frames;
    zew_trace_rtt_diagnostic.ready = 0U;
    /* An END frame was emitted; host-side flashed identity is still PENDING. */
}

static const struct tracing_backend_api zew_trace_rtt_api = {
    .init = zew_trace_rtt_init,
    .output = zew_trace_rtt_output,
};

TRACING_BACKEND_DEFINE(tracing_backend_zew_rtt, zew_trace_rtt_api);
