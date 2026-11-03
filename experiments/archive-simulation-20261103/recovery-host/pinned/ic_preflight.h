#ifndef IC_PREFLIGHT_H
#define IC_PREFLIGHT_H
#include <stddef.h>
#define IC_AU_MAX (1024u * 1024u)
#define IC_PS_MAX 4096u
/* Narrow, allocation-free Annex-B gate. Not a full entropy decoder. A caller
 * must still provide complete, trusted encoder AUs; truncated slice payloads
 * cannot in general be detected without entropy decoding. State commits only
 * on success. One SPS/PPS pair, unchanged byte-for-byte for the whole stream. */
struct ic_preflight {
    unsigned width, height, left, top, visible_width, visible_height;
    unsigned sps_id, pps_id, frame_bits, poc_type, poc_bits, delta_always_zero;
    unsigned bottom_poc_present, ready;
    size_t sps_len, pps_len;
    unsigned char sps[IC_PS_MAX], pps[IC_PS_MAX];
};
int ic_preflight_au(struct ic_preflight *, const unsigned char *, size_t);
#endif
