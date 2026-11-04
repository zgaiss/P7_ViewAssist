#ifndef IC_IPU_CANDIDATE_H
#define IC_IPU_CANDIDATE_H
#include "ic_decoder.h"
#include <stddef.h>
#include <stdint.h>
#define IC_IPU_RGB_BYTES (560u * 315u * 2u)
/* NOT the kernel ABI. A verified target adapter must translate these fields. */
struct ic_ipu_plan {
    unsigned width, height, x, y, crop_w, crop_h, out_w, out_h, dst_x, dst_y;
    size_t input_bytes, output_bytes;
};
struct ic_ipu_buffer { unsigned char *map; size_t bytes; uintptr_t handle; };
struct ic_ipu_task {
    struct ic_ipu_plan plan;
    uintptr_t input, output;
};
enum ic_ipu_completion { IC_IPU_COMPLETE = 0, IC_IPU_FAILED_IDLE = 1, IC_IPU_UNKNOWN = 2 };
struct ic_ipu_backend {
    void *ctx;
    int (*allocate)(void *, size_t, struct ic_ipu_buffer *);
    void (*release)(void *, struct ic_ipu_buffer *);
    /* check may mutate the task, as real IPU_CHECK_TASK does. */
    int (*check)(void *, struct ic_ipu_task *);
    int (*sync_input)(void *, struct ic_ipu_buffer *);
    enum ic_ipu_completion (*queue)(void *, const struct ic_ipu_task *);
    int (*sync_output)(void *, struct ic_ipu_buffer *);
};
typedef int (*ic_ipu_publish)(void *, const unsigned char *, size_t);
struct ic_ipu_candidate;
int ic_ipu_plan_make(struct ic_ipu_plan *, unsigned width, unsigned height,
                     unsigned x, unsigned y, unsigned crop_w, unsigned crop_h);
/* Intentionally closed: no production backend or ABI evidence exists here. */
struct ic_ipu_candidate *ic_ipu_open_target(const struct ic_ipu_plan *,
                                          ic_ipu_publish, void *);
#ifdef IC_IPU_HOST_CONTRACT_TEST
/* Available only in explicitly host-test builds. Never a hardware switch. */
struct ic_ipu_candidate *ic_ipu_open_test(const struct ic_ipu_plan *,
              const struct ic_ipu_backend *, ic_ipu_publish, void *);
#endif
/* Signature can be supplied to the existing synchronous VPU frame callback. */
int ic_ipu_frame_callback(const struct ic_yuv_frame *, void *);
/* UNKNOWN retains both DMA allocations and the object. No reopen/retry/reset.
 * A real adapter must additionally establish kernel process-exit behavior. */
int ic_ipu_close(struct ic_ipu_candidate *);
int ic_ipu_quarantined(const struct ic_ipu_candidate *);
#endif
