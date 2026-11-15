/* SPDX-License-Identifier: Apache-2.0 */
/* Build the ACTUAL Zephyr RAM backend with bounded native test shims. */
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CONFIG_TRACING_ASYNC 1
#define CONFIG_TRACING_CTF 1
#define CONFIG_SMP 0
#define CONFIG_ZEW_TRACE_RAM_BYTES 4096
#define CONFIG_TRACING_BUFFER_SIZE 2048
#define CONFIG_ZEW_TRACE_IMAGE_CONTRACT_SHA256 \
    "1111111111111111111111111111111111111111111111111111111111111111"
#define CONFIG_ZEW_TRACE_METADATA_SHA256 \
    "2222222222222222222222222222222222222222222222222222222222222222"

static uint32_t fake_drops;
void sys_rand_get(void *dst, size_t length)
{
    memset(dst, 27, length);
}
uint32_t tracing_packet_drop_count_get(void)
{
    return fake_drops;
}

/* Source is compiled as C, using the test stubs above; exported real globals
 * and static routines are reachable only within this native test TU. */
#include "../../firmware/trace/zephyr44/tracing_backend_zew_ram.c"

static uint8_t sample_ctf[6] = {10U, 0U, 0U, 0U, 0x1bU, 0U};

int main(int argc, char **argv)
{
    bool loss = argc == 2 && strcmp(argv[1], "loss") == 0;
    bool overflow = argc == 2 && strcmp(argv[1], "overflow") == 0;
    bool duplicate_stop = argc == 2 && strcmp(argv[1], "duplicate") == 0;

    zew_trace_ram_init();
    if (!zew_trace_ram_receipt.ready || !zew_trace_ram_receipt.committed) {
        return 2;
    }
    if (loss) {
        fake_drops = 1U;
        zew_trace_ram_output(NULL, sample_ctf, sizeof(sample_ctf));
    } else if (overflow) {
        static uint8_t huge_ctf[2048];
        for (unsigned i = 0; i < 10U; ++i) {
            zew_trace_ram_output(NULL, huge_ctf, sizeof(huge_ctf));
        }
    } else {
        zew_trace_ram_output(NULL, sample_ctf, sizeof(sample_ctf));
    }
    zew_trace_backend_close();
    if (duplicate_stop) {
        zew_trace_backend_close();
    }
    if (loss || overflow) {
        if (zew_trace_ram_receipt.ended ||
            !zew_trace_ram_receipt.error) {
            return 3;
        }
    } else if (!zew_trace_ram_receipt.ended ||
               zew_trace_ram_receipt.error ||
               zew_trace_ram_receipt.source_drops) {
        return 4;
    }
    if (zew_trace_ram_receipt.committed > zew_trace_ram_receipt.capacity) {
        return 5;
    }
    if (fwrite(zew_trace_ram_bytes, 1U,
               zew_trace_ram_receipt.committed, stdout)
        != zew_trace_ram_receipt.committed) {
        return 6;
    }
    return 0;
}
