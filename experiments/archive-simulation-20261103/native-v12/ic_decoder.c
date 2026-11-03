#define _POSIX_C_SOURCE 200809L
#include "ic_decoder.h"
#include "ic_preflight.h"
#include "ic_dma_budget.h"
#include <vpu_lib.h>
#include <vpu_io.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

_Static_assert(sizeof(void *) == 4 && sizeof(unsigned long) == 4, "ARM32 ILP32 required");
_Static_assert(sizeof(DecOpenParam) == 88, "DecOpenParam ABI");
_Static_assert(sizeof(DecInitialInfo) == 152, "DecInitialInfo ABI");
_Static_assert(sizeof(FrameBuffer) == 28, "FrameBuffer ABI");
_Static_assert(sizeof(DecParam) == 52, "DecParam ABI");
_Static_assert(sizeof(DecOutputInfo) == 308, "DecOutputInfo ABI");
_Static_assert(sizeof(vpu_mem_desc) == 16, "vpu_mem_desc ABI");
_Static_assert(sizeof(vpu_versioninfo) == 28 && sizeof(RetCode) == 4, "version/enum ABI");
_Static_assert(VPU_LIB_VERSION_CODE == VPU_LIB_VERSION(5, 4, 16), "SDK 5.4.16 required");
#define RING_BYTES IC_DMA_RING_BYTES
#define MAX_FB 32
#define MAX_SLICE_KIB (((1920u * 1088u / 256u) * 400u + 512u) / 1024u)
#define DEADLINE_MS 2000
struct memory { vpu_mem_desc d; int allocated, mapped; };
struct ic_decoder {
    DecHandle handle;
    int initialized, opened, unsafe, failed, in_call, count;
    unsigned stride, height;
    uint64_t request_budget, requested_bytes;
    struct ic_preflight gate;
    struct memory ring, slice, mem[MAX_FB];
    FrameBuffer fb[MAX_FB];
    ic_frame_callback cb;
    void *opaque;
};
static int process_poisoned;
static ic_decoder *active;
static int fail(ic_decoder *d, const char *why, int unsafe)
{
    fprintf(stderr, "ic_decoder: %s; stop stream, close once, exit process%s\n",
            why, unsafe ? "; unsafe: DMA allocations retained, no reset" : "");
    d->failed = 1; d->unsafe |= unsafe; process_poisoned = 1;
    return -1;
}
static long long now_ms(void)
{
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return -1;
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
/* This limits cumulative requested DMA payload bytes per decoder lifetime,
 * NOT returned descriptor sizes, allocator rounding, hidden VPU memory or RAM.
 * No default allowance: the operator must supply an explicit decimal budget.
 * Snapshot before SDK startup; later environment changes do not alter it. */
static int request_total(uint64_t frame_bytes, uint64_t count,
                         uint64_t slice_bytes, uint64_t *total)
{
    uint64_t available = UINT64_MAX - RING_BYTES;
    if (slice_bytes > available) return -1;
    available -= slice_bytes;
    if (count && frame_bytes > available / count) return -1;
    *total = RING_BYTES + frame_bytes * count + slice_bytes;
    return 0;
}
static int allocate(ic_decoder *d, struct memory *m, unsigned size)
{
    if (!size || size > INT_MAX || d->requested_bytes > d->request_budget ||
        (uint64_t)size > d->request_budget - d->requested_bytes) return -1;
    /* Charge before requesting: failed/uncertain requests never create credit.
     * There is no allocation retry in this lifetime; ordinary cleanup unchanged. */
    d->requested_bytes += size;
    m->d.size = (int)size;
    if (IOGetPhyMem(&m->d)) return -1;
    m->allocated = 1;
    if (m->d.size < (int)size) return -1;
    (void)IOGetVirtMem(&m->d);
    if (!m->d.virt_uaddr || m->d.virt_uaddr == ULONG_MAX) return -1;
    m->mapped = 1;
    if (!m->d.phy_addr || (m->d.phy_addr & 4095) || m->d.phy_addr > ULONG_MAX - size) return -1;
    memset((void *)(uintptr_t)m->d.virt_uaddr, 0, size);
    return 0;
}
static int release(struct memory *m)
{
    if (m->mapped) {
        if (IOFreeVirtMem(&m->d)) return -1;
        m->mapped = 0;
    }
    if (m->allocated) {
        if (IOFreePhyMem(&m->d)) return -1;
        m->allocated = 0;
    }
    return 0;
}
static int idle(ic_decoder *d)
{
    if (vpu_IsBusy()) return fail(d, "VPU not idle", 1);
    return 0;
}
static int sdk(ic_decoder *d, RetCode rc, const char *call)
{
    if (rc == RETCODE_SUCCESS) return 0;
    fprintf(stderr, "ic_decoder: %s rc=%d\n", call, (int)rc);
    return fail(d, "SDK failure, ownership uncertain", 1);
}
#define SDK(d, expr) sdk((d), (expr), #expr)
static int feed_ring(ic_decoder *d, const unsigned char *p, size_t n)
{
    /* Explicit next-AU boundary: AUD primary_pic_type=7 (all slice types),
     * rbsp_stop_one_bit. This is NOT EOS. In SDK 5.4.16 bitstreamMode=1
     * selects ROLLBACK, not a modern PIC_END enum. It returns insufficient
     * data instead of waiting for a producer that cannot run in this thread. */
    static const unsigned char aud[] = {0, 0, 0, 1, 9, 0xf0};
    PhysicalAddress rd, wr;
    Uint32 room;
    size_t offset, first, parts[2] = {n, sizeof(aud)}, k;
    const unsigned char *src[2] = {p, aud};
    if (idle(d) || SDK(d, vpu_DecGetBitstreamBuffer(d->handle, &rd, &wr, &room))) return -1;
    if (room > RING_BYTES || room < n + sizeof(aud) ||
        rd < d->ring.d.phy_addr || rd >= d->ring.d.phy_addr + RING_BYTES ||
        wr < d->ring.d.phy_addr || wr >= d->ring.d.phy_addr + RING_BYTES)
        return fail(d, "bounded ring full or invalid pointers", 0);
    offset = (size_t)(wr - d->ring.d.phy_addr);
    for (k = 0; k < 2; ++k) {
        first = RING_BYTES - offset;
        if (first > parts[k]) first = parts[k];
        memcpy((unsigned char *)(uintptr_t)d->ring.d.virt_uaddr + offset, src[k], first);
        memcpy((void *)(uintptr_t)d->ring.d.virt_uaddr, src[k] + first, parts[k] - first);
        offset = (offset + parts[k]) % RING_BYTES;
    }
    /* Never call Update(...,0): it sets stream-end bit2 in the official SDK. */
    return SDK(d, vpu_DecUpdateBitstreamBuffer(d->handle, (Uint32)(n + sizeof(aud))));
}
static int crop_matches(Rect r, const struct ic_preflight *g)
{
    if (!r.left && !r.top && !r.right && !r.bottom)
        return !g->left && !g->top && g->width == g->visible_width && g->height == g->visible_height;
    return r.left == g->left && r.top == g->top &&
           r.right == g->left + g->visible_width && r.bottom == g->top + g->visible_height;
}
static const unsigned char *plane(ic_decoder *d, int index, PhysicalAddress p,
                                  unsigned stride, unsigned scale)
{
    struct memory *m = &d->mem[index];
    size_t offset, tail;
    unsigned rows = d->gate.visible_height / scale, width = d->gate.visible_width / scale;
    if (p < m->d.phy_addr || !rows || stride < width) return NULL;
    offset = (size_t)(p - m->d.phy_addr) + (d->gate.top / scale) * stride + d->gate.left / scale;
    tail = (size_t)(rows - 1) * stride + width;
    if (offset > (size_t)m->d.size || tail > (size_t)m->d.size - offset) return NULL;
    return (const unsigned char *)(uintptr_t)m->d.virt_uaddr + offset;
}
static int decode_queued(ic_decoder *d)
{
    unsigned starts;
    for (starts = 0; starts < 64; ++starts) {
        DecParam param;
        DecOutputInfo info;
        long long began, current;
        int display, bad, callback_rc = 0;
        struct ic_yuv_frame frame;
        if (idle(d)) return -1;
        began = now_ms();
        if (began < 0) return fail(d, "clock failure", 0);
        memset(&param, 0, sizeof(param)); memset(&info, 0, sizeof(info));
        if (SDK(d, vpu_DecStartOneFrame(d->handle, &param))) return -1;
        while (vpu_IsBusy()) {
            current = now_ms();
            if (current < 0 || current - began >= DEADLINE_MS)
                return fail(d, "busy timeout: no GetOutputInfo/close/free", 1);
            (void)vpu_WaitForInt(1);
        }
        /* Every completed Start is paired, even rollback; this releases the
         * SDK pending-instance mutex. Never inspect output while still busy. */
        if (SDK(d, vpu_DecGetOutputInfo(d->handle, &info)) || idle(d)) return -1;
        display = info.indexFrameDisplay;
        bad = info.notSufficientPsBuffer || info.notSufficientSliceBuffer ||
              info.numOfErrMBs || info.interlacedFrame;
        if (info.decodingSuccess & 0x10) {
            /* Rollback is not fatal and never requires a next AU to return.
             * A rollback must not claim ownership of a displayed picture. */
            if (display >= 0 || bad) return fail(d, "inconsistent rollback output", 0);
            return 0;
        }
        if (info.decodingSuccess != 1) bad = 1;
        if (info.indexFrameDecoded >= 0) {
            if (info.indexFrameDecoded >= d->count || info.decPicWidth != (int)d->gate.width ||
                info.decPicHeight != (int)d->gate.height || !crop_matches(info.decPicCrop, &d->gate)) bad = 1;
        }
        if (display >= 0) {
            if (display >= d->count) return fail(d, "display index outside allocation", 0);
            frame.width = d->gate.visible_width; frame.height = d->gate.visible_height;
            frame.y_stride = d->stride; frame.c_stride = d->stride / 2;
            frame.y = plane(d, display, d->fb[display].bufY, frame.y_stride, 1);
            frame.u = plane(d, display, d->fb[display].bufCb, frame.c_stride, 2);
            frame.v = plane(d, display, d->fb[display].bufCr, frame.c_stride, 2);
            if (!frame.y || !frame.u || !frame.v) bad = 1;
            if (!bad) callback_rc = d->cb(&frame, d->opaque);
            /* Synchronous consumer MUST copy before this display flag release. */
            if (SDK(d, vpu_DecClrDispFlag(d->handle, display))) return -1;
        } else if (display != -2 && display != -3) bad = 1; /* no EOS expected */
        if (bad || callback_rc) return fail(d, "invalid output or callback rejected frame", 0);
        if (info.indexFrameDecoded < 0) return 0;
        /* At most previous rollback + this AU, then AUD-only rollback. */
    }
    return fail(d, "per-AU decode start bound exceeded", 0);
}
ic_decoder *ic_decoder_open(const unsigned char *au, size_t len, ic_frame_callback cb, void *opaque)
{
    ic_decoder *d;
    DecOpenParam op;
    DecInitialInfo initial;
    DecBufInfo buffers;
    vpu_versioninfo version;
    int i;
    unsigned y, c, frame_bytes, slice_bytes;
    uint64_t budget, total;
    if (active || process_poisoned || !cb) { process_poisoned = 1; return NULL; }
    if (ic_dma_request_budget_read(&budget)) {
        fprintf(stderr, "ic_decoder: explicit DMA request budget missing/invalid/too small; exit process\n");
        process_poisoned = 1; return NULL;
    }
    d = calloc(1, sizeof(*d));
    if (!d) { process_poisoned = 1; return NULL; }
    d->request_budget = budget;
    d->cb = cb; d->opaque = opaque; d->in_call = 1;
    if (ic_preflight_au(&d->gate, au, len)) { fail(d, "first AU preflight rejected", 0); goto error; }
    d->stride = (d->gate.width + 15) & ~15u; d->height = (d->gate.height + 15) & ~15u;
    /* Preflight bounds width/height to 1280/720 before this multiplication. */
    if ((uint64_t)d->stride * d->height > UINT_MAX / 2u) {
        fail(d, "frame geometry arithmetic overflow", 0); goto error;
    }
    y = d->stride * d->height; c = y / 4;
    frame_bytes = y + 3 * c;
    /* At least two frame/MV allocations and one KiB slice are required by
     * the existing metadata admission. Reject impossible budgets before SDK. */
    if (request_total(frame_bytes, 2, 1024, &total) || total > budget) {
        fail(d, "DMA request budget below preflight minimum", 0); goto error;
    }
    active = d;
    if (SDK(d, vpu_Init(NULL))) goto error;
    d->initialized = 1;
    memset(&version, 0, sizeof(version));
    if (SDK(d, vpu_GetVersionInfo(&version))) goto error;
    if (!cpu_is_mx6x() || version.lib_major != 5 || version.lib_minor != 4 || version.lib_release != 16 ||
        version.fw_major != 2 || version.fw_minor != 3 || version.fw_release != 10) {
        fail(d, "runtime chip/library does not match verified SDK", 0); goto error;
    }
    fprintf(stderr,"IC_VPU lib=%u.%u.%u fw=%u.%u.%u\n",version.lib_major,version.lib_minor,version.lib_release,version.fw_major,version.fw_minor,version.fw_release);
    if (idle(d)) goto error;
    if (allocate(d, &d->ring, RING_BYTES)) { fail(d, "ring allocation failed", 0); goto error; }
    memset(&op, 0, sizeof(op));
    op.bitstreamFormat = STD_AVC; op.bitstreamBuffer = d->ring.d.phy_addr;
    op.bitstreamBufferSize = RING_BYTES; op.pBitStream = (Uint8 *)(uintptr_t)d->ring.d.virt_uaddr;
    op.reorderEnable = 0; op.chromaInterleave = 0; op.mapType = LINEAR_FRAME_MAP;
    op.bitstreamMode = 1; /* rollback: returns instead of waiting for a producer in this thread */
    if (SDK(d, vpu_DecOpen(&d->handle, &op))) goto error;
    d->opened = 1;
    if (feed_ring(d, au, len) || SDK(d, vpu_DecSetEscSeqInit(d->handle, 1))) goto error;
    memset(&initial, 0, sizeof(initial));
    /* Official SEQ_INIT has an internal busy wait: rollback + escape prevent
     * input starvation, but do not cure hardware hangs. External watchdog. */
    if (SDK(d, vpu_DecGetInitialInfo(d->handle, &initial)) || idle(d) ||
        SDK(d, vpu_DecSetEscSeqInit(d->handle, 0))) goto error;
    if (initial.picWidth != (int)d->gate.width || initial.picHeight != (int)d->gate.height ||
        initial.interlace || !crop_matches(initial.picCropRect, &d->gate) ||
        initial.minFrameBufferCount < 1 || initial.minFrameBufferCount >= MAX_FB ||
        initial.worstSliceSize <= 0 || (unsigned)initial.worstSliceSize > MAX_SLICE_KIB) {
        fail(d, "sequence info outside preflight/allocation bounds", 0); goto error;
    }
    d->count = initial.minFrameBufferCount + 1;
    slice_bytes = (unsigned)initial.worstSliceSize * 1024u;
    if (request_total(frame_bytes, (uint64_t)d->count, slice_bytes, &total) ||
        total > d->request_budget) {
        fail(d, "DMA request budget below sequence requirement", 0); goto error;
    }
    for (i = 0; i < d->count; ++i) {
        if (allocate(d, &d->mem[i], frame_bytes)) { fail(d, "frame/MV allocation failed", 0); goto error; }
        d->fb[i].myIndex = i; d->fb[i].strideY = d->stride; d->fb[i].strideC = d->stride / 2;
        d->fb[i].bufY = d->mem[i].d.phy_addr; d->fb[i].bufCb = d->fb[i].bufY + y;
        d->fb[i].bufCr = d->fb[i].bufCb + c; d->fb[i].bufMvCol = d->fb[i].bufCr + c;
    }
    if (allocate(d, &d->slice, slice_bytes)) {
        fail(d, "slice allocation failed", 0); goto error;
    }
    memset(&buffers, 0, sizeof(buffers));
    buffers.avcSliceBufInfo.bufferBase = d->slice.d.phy_addr;
    buffers.avcSliceBufInfo.bufferSize = d->slice.d.size;
    buffers.maxDecFrmInfo.maxMbX = (int)(d->stride / 16);
    buffers.maxDecFrmInfo.maxMbY = (int)(d->height / 16);
    buffers.maxDecFrmInfo.maxMbNum = (int)(d->stride * d->height / 256);
    if (SDK(d, vpu_DecRegisterFrameBuffer(d->handle, d->fb, d->count, (int)d->stride, &buffers)) ||
        decode_queued(d)) goto error;
    d->in_call = 0; return d;
error:
    process_poisoned = 1; d->in_call = 0;
    if (ic_decoder_close(d)) fprintf(stderr, "ic_decoder_open: unsafe cleanup; exit process immediately, do not retry\n");
    return NULL;
}
int ic_decoder_push(ic_decoder *d, const unsigned char *au, size_t len)
{
    if (!d || d != active || d->in_call || d->failed) return -1;
    d->in_call = 1;
    int rc;
    if (ic_preflight_au(&d->gate, au, len)) rc = fail(d, "AU preflight rejected (including changed SPS/PPS)", 0);
    else rc = feed_ring(d, au, len);
    d->in_call = 0; return rc;
}
int ic_decoder_decode(ic_decoder *d)
{
    if (!d || d != active || d->in_call || d->failed) return -1;
    d->in_call = 1;
    int rc = decode_queued(d);
    d->in_call = 0; return rc;
}
int ic_decoder_feed(ic_decoder *d, const unsigned char *au, size_t len)
{
    int rc;
    if (!d || d != active || d->in_call || d->failed) return -1;
    d->in_call = 1;
    if (ic_preflight_au(&d->gate, au, len)) rc = fail(d, "AU preflight rejected (including changed SPS/PPS)", 0);
    else { rc = feed_ring(d, au, len); if (!rc) rc = decode_queued(d); }
    d->in_call = 0; return rc;
}
int ic_decoder_close(ic_decoder *d)
{
    int i, result = 0;
    if (!d) return 0;
    if (d->in_call) { process_poisoned = 1; return -1; }
    if (d->initialized && !d->unsafe && vpu_IsBusy()) d->unsafe = 1;
    if (d->unsafe) {
        process_poisoned = 1;
        fprintf(stderr, "ic_decoder_close: UNSAFE; no close/unmap/free/uninit/reset; exit process\n");
        return -1; /* Deliberately retain handle and every DMA allocation. */
    }
    if (d->opened) {
        if (SDK(d, vpu_DecClose(d->handle))) return -1;
        d->opened = 0;
        if (idle(d)) return -1;
    }
    for (i = MAX_FB - 1; i >= 0; --i) if (release(&d->mem[i])) result = -1;
    if (release(&d->slice)) result = -1;
    if (release(&d->ring)) result = -1;
    /* Do not ask SDK to drop its global state after a failed physical cleanup. */
    if (result) { process_poisoned = 1; return -1; }
    if (d->initialized) vpu_UnInit();
    if (active == d) active = NULL;
    free(d); return 0;
}
