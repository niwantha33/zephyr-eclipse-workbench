/* SPDX-License-Identifier: Apache-2.0 */
/* Host-compiled portable C firmware encoder contract probe (no Zephyr SDK). */
#include "zew_trace_frame.h"
#include <stdio.h>
#include <string.h>

static unsigned emits;
static int fail_second;

static bool emit(const uint8_t *bytes, size_t length, void *context)
{
    (void)context;
    emits++;
    if (fail_second && emits == 2U) {
        return false;
    }
    return fwrite(bytes, 1U, length, stdout) == length;
}

int main(int argc, char **argv)
{
    struct zew_trace_encoder writer;
    const uint8_t epoch[16] = {
        27, 27, 27, 27, 27, 27, 27, 27,
        27, 27, 27, 27, 27, 27, 27, 27,
    };
    uint8_t image[32], metadata[32];
    const uint8_t bytes[] = {1U, 2U, 3U, 0U, 255U, 254U};
    bool fail = argc > 1 && strcmp(argv[1], "reject") == 0;

    memset(image, 0x11, sizeof(image));
    memset(metadata, 0x22, sizeof(metadata));
    if (!zew_trace_encoder_init(&writer, epoch, image, metadata, emit, NULL)) {
        return 1;
    }
    fail_second = fail ? 1 : 0;
    if (!zew_trace_encoder_send(&writer, ZEW_TRACE_FRAME_HELLO,
                                NULL, 0U, 0U)) {
        return 2;
    }
    if (fail) {
        /* A nonblocking backend drop MUST poison the stream permanently. */
        if (zew_trace_encoder_send(&writer, ZEW_TRACE_FRAME_DATA,
                                   bytes, sizeof(bytes), 0U)
            || zew_trace_encoder_send(&writer, ZEW_TRACE_FRAME_END,
                                      NULL, 0U, 0U)
            || zew_trace_encoder_complete(&writer)
            || !writer.poisoned) {
            return 3;
        }
        return 0;
    }
    if (!zew_trace_encoder_send(&writer, ZEW_TRACE_FRAME_DATA,
                                bytes, sizeof(bytes), 0U)
        || !zew_trace_encoder_send(&writer, ZEW_TRACE_FRAME_END,
                                   NULL, 0U, 0U)
        || !zew_trace_encoder_complete(&writer)) {
        return 4;
    }
    /* Cannot silently reopen a terminated session. */
    if (zew_trace_encoder_send(&writer, ZEW_TRACE_FRAME_DATA,
                               bytes, sizeof(bytes), 0U)) {
        return 5;
    }
    return 0;
}
