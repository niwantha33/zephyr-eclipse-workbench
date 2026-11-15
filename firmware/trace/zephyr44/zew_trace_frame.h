/* SPDX-License-Identifier: Apache-2.0 */
#ifndef ZEW_TRACE_FRAME_H_
#define ZEW_TRACE_FRAME_H_

/*
 * Experimental firmware encoder for the strictly verified, test-only ZEWF
 * v1 envelope. These fields are NOT a flashed-image attestation. No transport
 * writes, allocation or blocking operations occur inside this encoder.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define ZEW_TRACE_FRAME_HEADER_SIZE 116U
#define ZEW_TRACE_FRAME_MAX_PAYLOAD 512U
#define ZEW_TRACE_FRAME_HELLO 1U
#define ZEW_TRACE_FRAME_DATA 2U
#define ZEW_TRACE_FRAME_END 3U

typedef bool (*zew_trace_emit_t)(const uint8_t *frame, size_t size, void *ctx);

struct zew_trace_encoder {
    uint8_t epoch[16];
    uint8_t image_contract_sha[32];
    uint8_t metadata_sha[32];
    uint64_t sequence;
    uint64_t source_losses;
    uint32_t committed_frames;
    zew_trace_emit_t emit;
    void *emit_context;
    bool started;
    bool ended;
    bool poisoned;
};

bool zew_trace_encoder_init(struct zew_trace_encoder *encoder,
                            const uint8_t epoch[16],
                            const uint8_t image_contract_sha[32],
                            const uint8_t metadata_sha[32],
                            zew_trace_emit_t emit, void *context);

/* One complete atomic frame per callback. Failure poisons the whole epoch. */
bool zew_trace_encoder_send(struct zew_trace_encoder *encoder,
                            uint8_t kind, const uint8_t *bytes,
                            size_t size, uint64_t source_losses);

/* Host can qualify only after explicit END and trusted separate attestation. */
bool zew_trace_encoder_complete(const struct zew_trace_encoder *encoder);

#endif /* ZEW_TRACE_FRAME_H_ */
