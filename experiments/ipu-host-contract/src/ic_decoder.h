#ifndef IC_DECODER_H
#define IC_DECODER_H
#include <stddef.h>
typedef struct ic_decoder ic_decoder;
struct ic_yuv_frame {
    const unsigned char *y, *u, *v;
    unsigned width, height, y_stride, c_stride;
};
typedef int (*ic_frame_callback)(const struct ic_yuv_frame *, void *);
/* ARM32 Linux / libvpu.so.4, SDK 5.4.16 external headers. Single owner,
 * single thread, one active decoder per process; no reentry from callback.
 * open validates and FEEDS/DECODES first_au, including synchronous callback
 * when a frame is available; never feed first_au again. Rollback mode can
 * retain an incomplete firmware picture until a later complete AU arrives.
 * NULL means failure: caller MUST terminate process, not retry open.
 * All callbacks run only after hardware idle; copy planes before returning.
 * Plane pointers expire at callback return; callback returns 0 on success.
 * Annex-B <=1 MiB complete AUs, one immutable SPS/PPS, Baseline progressive
 * 8-bit 4:2:0 <=1280x720, no B/FMO/redundant pictures. No AVCC conversion.
 * No EOS is submitted by open/feed; close does not drain delayed pictures.
 * feed: 0 accepted (possibly buffered), -1 fatal; caller should close then
 * terminate on errors. close: 0 fully closed, nonzero unsafe/cleanup failed;
 * caller MUST exit process, never retry. Unsafe close retains DMA memory.
 * SDK calls themselves have internal unbounded waits; external process
 * supervision remains necessary. No explicit reset is ever called here.
 */
ic_decoder *ic_decoder_open(const unsigned char *first_au, size_t len,
                            ic_frame_callback cb, void *opaque);
int ic_decoder_feed(ic_decoder *, const unsigned char *au, size_t len);
int ic_decoder_push(ic_decoder *, const unsigned char *au, size_t len);
int ic_decoder_decode(ic_decoder *);
int ic_decoder_close(ic_decoder *);
#endif
