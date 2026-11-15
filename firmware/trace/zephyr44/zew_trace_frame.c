/* SPDX-License-Identifier: Apache-2.0 */
#include "zew_trace_frame.h"
#include <string.h>

#define WIRE_VERSION 1U
#define MAX_FRAMES 300000U

static void put16(uint8_t *to, uint16_t v)
{
    to[0] = (uint8_t)v;
    to[1] = (uint8_t)(v >> 8);
}

static void put32(uint8_t *to, uint32_t v)
{
    for (unsigned i = 0; i < 4U; ++i) {
        to[i] = (uint8_t)(v >> (8U * i));
    }
}

static void put64(uint8_t *to, uint64_t v)
{
    for (unsigned i = 0; i < 8U; ++i) {
        to[i] = (uint8_t)(v >> (8U * i));
    }
}

static uint32_t crc32_frame(const uint8_t *header, const uint8_t *data,
                            size_t length)
{
    uint32_t crc = UINT32_MAX;
    /* Header excluding last four CRC bytes, then exactly the payload. */
    for (size_t p = 0U; p < ZEW_TRACE_FRAME_HEADER_SIZE - 4U + length; ++p) {
        uint8_t b = p < ZEW_TRACE_FRAME_HEADER_SIZE - 4U
                ? header[p] : data[p - (ZEW_TRACE_FRAME_HEADER_SIZE - 4U)];
        crc ^= b;
        for (unsigned bit = 0U; bit < 8U; ++bit) {
            uint32_t mask = 0U - (crc & 1U);
            crc = (crc >> 1U) ^ (0xEDB88320U & mask);
        }
    }
    return ~crc;
}

static bool any_nonzero(const uint8_t *bytes, size_t n)
{
    uint8_t seen = 0U;
    for (size_t i = 0U; i < n; ++i) {
        seen |= bytes[i];
    }
    return seen != 0U;
}

bool zew_trace_encoder_init(struct zew_trace_encoder *encoder,
                            const uint8_t epoch[16],
                            const uint8_t image_contract_sha[32],
                            const uint8_t metadata_sha[32],
                            zew_trace_emit_t emit, void *context)
{
    if (encoder == NULL || epoch == NULL || image_contract_sha == NULL
        || metadata_sha == NULL || emit == NULL
        || !any_nonzero(epoch, 16U)
        || !any_nonzero(image_contract_sha, 32U)
        || !any_nonzero(metadata_sha, 32U)) {
        return false;
    }
    memset(encoder, 0, sizeof(*encoder));
    memcpy(encoder->epoch, epoch, 16U);
    memcpy(encoder->image_contract_sha, image_contract_sha, 32U);
    memcpy(encoder->metadata_sha, metadata_sha, 32U);
    encoder->emit = emit;
    encoder->emit_context = context;
    return true;
}

bool zew_trace_encoder_send(struct zew_trace_encoder *encoder,
                            uint8_t kind, const uint8_t *bytes,
                            size_t size, uint64_t source_losses)
{
    uint8_t frame[ZEW_TRACE_FRAME_HEADER_SIZE + ZEW_TRACE_FRAME_MAX_PAYLOAD];
    size_t total;
    if (encoder == NULL || encoder->emit == NULL || encoder->poisoned
        || encoder->ended || encoder->committed_frames >= MAX_FRAMES
        || size > ZEW_TRACE_FRAME_MAX_PAYLOAD
        || (size > 0U && bytes == NULL)
        || source_losses < encoder->source_losses) {
        if (encoder != NULL) {
            encoder->poisoned = true;
        }
        return false;
    }
    if ((!encoder->started && (kind != ZEW_TRACE_FRAME_HELLO || size != 0U))
        || (encoder->started && kind == ZEW_TRACE_FRAME_HELLO)
        || (kind == ZEW_TRACE_FRAME_DATA && size == 0U)
        || (kind == ZEW_TRACE_FRAME_END && size != 0U)
        || (kind != ZEW_TRACE_FRAME_HELLO
            && kind != ZEW_TRACE_FRAME_DATA && kind != ZEW_TRACE_FRAME_END)) {
        encoder->poisoned = true;
        return false;
    }
    memset(frame, 0, ZEW_TRACE_FRAME_HEADER_SIZE);
    frame[0] = 'Z';
    frame[1] = 'E';
    frame[2] = 'W';
    frame[3] = 'F';
    frame[4] = WIRE_VERSION;
    frame[5] = kind;
    put16(frame + 6U, 0U);
    put32(frame + 8U, (uint32_t)size);
    put64(frame + 12U, encoder->sequence);
    memcpy(frame + 20U, encoder->epoch, 16U);
    memcpy(frame + 36U, encoder->image_contract_sha, 32U);
    memcpy(frame + 68U, encoder->metadata_sha, 32U);
    put16(frame + 100U, 0U);  /* Single core, no clock remapping. */
    put16(frame + 102U, 0U);  /* Dedicated binary stream only. */
    put64(frame + 104U, source_losses);
    if (size > 0U) {
        memcpy(frame + ZEW_TRACE_FRAME_HEADER_SIZE, bytes, size);
    }
    put32(frame + 112U, crc32_frame(frame, bytes, size));
    total = ZEW_TRACE_FRAME_HEADER_SIZE + size;
    if (!encoder->emit(frame, total, encoder->emit_context)) {
        encoder->poisoned = true;
        return false;
    }
    encoder->committed_frames++;
    encoder->sequence++;
    encoder->source_losses = source_losses;
    encoder->started = true;
    if (kind == ZEW_TRACE_FRAME_END) {
        encoder->ended = true;
    }
    return true;
}

bool zew_trace_encoder_complete(const struct zew_trace_encoder *encoder)
{
    return encoder != NULL && encoder->started && encoder->ended
           && !encoder->poisoned && encoder->source_losses == 0U;
}
