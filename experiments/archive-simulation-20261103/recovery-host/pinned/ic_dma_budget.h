#ifndef IC_DMA_BUDGET_H
#define IC_DMA_BUDGET_H
#include <stdint.h>
#include <stdlib.h>
/* Requested payload only, not total physical RAM or allocator overhead.
 * One shared parser prevents receiver and decoder admission grammar drift.
 * No default allowance. This constant is the decoder's existing ring size,
 * not an operator-selected memory policy. */
#define IC_DMA_RING_BYTES (2u * 1024u * 1024u)
static inline int ic_dma_request_budget_read(uint64_t *budget)
{
    const char *p = getenv("IC_DECODER_DMA_REQUEST_BUDGET_BYTES");
    uint64_t n = 0;
    if (!budget || !p || !*p) return -1;
    for (; *p; ++p) {
        unsigned digit;
        if (*p < '0' || *p > '9') return -1;
        digit = (unsigned)(*p - '0');
        if (n > (UINT64_MAX - digit) / 10) return -1;
        n = n * 10 + digit;
    }
    if (n < IC_DMA_RING_BYTES) return -1;
    *budget = n;
    return 0;
}
#endif
