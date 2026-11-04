#include "ic_ipu_candidate.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
struct ic_ipu_candidate {
    struct ic_ipu_plan p;
    struct ic_ipu_backend b;
    struct ic_ipu_buffer in, out;
    ic_ipu_publish publish;
    void *publish_ctx;
    unsigned char packed[IC_IPU_RGB_BYTES];
    int busy, quarantine, failed;
};
static unsigned gcd(unsigned a, unsigned b) { while (b) { unsigned t=a%b; a=b; b=t; } return a; }
int ic_ipu_plan_make(struct ic_ipu_plan *p, unsigned w, unsigned h,
                     unsigned x, unsigned y, unsigned cw, unsigned ch) {
    unsigned d, rw, rh, k, kh;
    if (!p || w<64 || w>1280 || h<64 || h>720 || !cw || !ch ||
        (w|h|x|y|cw|ch)&7u || x>w || y>h || cw>w-x || ch>h-y) {
        errno=EINVAL; return -1;
    }
    d=gcd(cw,ch); rw=cw/d; rh=ch/d;
    k=560/rw; kh=312/rh; if (kh<k) k=kh;
    k=(k/8)*8; /* Coprime ratio and both hardware dimensions divisible by 8. */
    if (!k) { errno=ENOTSUP; return -1; }
    memset(p,0,sizeof(*p));
    p->width=w; p->height=h; p->x=x; p->y=y; p->crop_w=cw; p->crop_h=ch;
    p->out_w=rw*k; p->out_h=rh*k;
    p->dst_x=(560-p->out_w)/2; p->dst_y=(315-p->out_h)/2;
    p->input_bytes=(size_t)w*h*3/2; p->output_bytes=(size_t)p->out_w*p->out_h*2;
    return 0;
}
struct ic_ipu_candidate *ic_ipu_open_target(const struct ic_ipu_plan *p,
                                           ic_ipu_publish cb, void *ctx) {
    (void)p; (void)cb; (void)ctx; errno=ENOTSUP; return NULL;
}
static int plan_equal(const struct ic_ipu_plan *a, const struct ic_ipu_plan *b) {
    return a->width==b->width && a->height==b->height && a->x==b->x && a->y==b->y &&
        a->crop_w==b->crop_w && a->crop_h==b->crop_h && a->out_w==b->out_w &&
        a->out_h==b->out_h && a->dst_x==b->dst_x && a->dst_y==b->dst_y &&
        a->input_bytes==b->input_bytes && a->output_bytes==b->output_bytes;
}
#ifdef IC_IPU_HOST_CONTRACT_TEST
static int valid_buffer(const struct ic_ipu_buffer *b, size_t n) {
    return b->map && b->handle && b->bytes>=n &&
        (uintptr_t)b->map<=UINTPTR_MAX-b->bytes;
}
static int overlap(const struct ic_ipu_buffer *a, const struct ic_ipu_buffer *b) {
    uintptr_t x=(uintptr_t)a->map, y=(uintptr_t)b->map;
    return x<y+b->bytes && y<x+a->bytes;
}
struct ic_ipu_candidate *ic_ipu_open_test(const struct ic_ipu_plan *p,
             const struct ic_ipu_backend *b, ic_ipu_publish cb, void *ctx) {
    struct ic_ipu_plan canonical;
    if (!p || !b || !cb || !b->allocate || !b->release || !b->check ||
        !b->sync_input || !b->queue || !b->sync_output ||
        ic_ipu_plan_make(&canonical,p->width,p->height,p->x,p->y,p->crop_w,p->crop_h) ||
        !plan_equal(p,&canonical)) { errno=EINVAL; return NULL; }
    struct ic_ipu_candidate *s=calloc(1,sizeof(*s)); if (!s) return NULL;
    s->p=*p; s->b=*b; s->publish=cb; s->publish_ctx=ctx;
    /* allocate must either return one uniquely owned allocation or leave a
     * zero descriptor on failure. No DMA can start in allocate/check/sync. */
    if (b->allocate(b->ctx,p->input_bytes,&s->in) || !valid_buffer(&s->in,p->input_bytes)) goto fail;
    if (b->allocate(b->ctx,p->output_bytes,&s->out) || !valid_buffer(&s->out,p->output_bytes)) goto fail;
    if (s->in.handle==s->out.handle || overlap(&s->in,&s->out)) goto fail;
    return s;
fail:
    if (s->out.handle && s->out.handle!=s->in.handle) b->release(b->ctx,&s->out);
    if (s->in.handle) b->release(b->ctx,&s->in);
    free(s); errno=EINVAL; return NULL;
}
#endif
static int source_span(const unsigned char *p, unsigned stride, unsigned rows, unsigned cols) {
    size_t n;
    if (!p || stride<cols || stride>16384 || !rows) return 0;
    n=(size_t)(rows-1)*stride+cols;
    return (uintptr_t)p<=UINTPTR_MAX-n;
}
/* All ranges were overflow-checked before this helper is called. Reject
 * aliases rather than memmove: copying one plane can destroy a later plane. */
static int source_alias(const unsigned char *p, unsigned stride, unsigned rows,
                        unsigned cols, const struct ic_ipu_candidate *s) {
    uintptr_t first=(uintptr_t)p;
    uintptr_t end=first+(size_t)(rows-1)*stride+cols;
    uintptr_t in=(uintptr_t)s->in.map, out=(uintptr_t)s->out.map;
    uintptr_t packed=(uintptr_t)s->packed;
    return (first<in+s->in.bytes && in<end) ||
        (first<out+s->out.bytes && out<end) ||
        (first<packed+sizeof(s->packed) && packed<end);
}
int ic_ipu_frame_callback(const struct ic_yuv_frame *f, void *ctx) {
    struct ic_ipu_candidate *s=ctx;
    struct ic_ipu_task before, checked;
    size_t ybytes, cbytes;
    enum ic_ipu_completion done;
    if (!s || !f || s->busy || s->quarantine || s->failed ||
        f->width!=s->p.width || f->height!=s->p.height ||
        !source_span(f->y,f->y_stride,f->height,f->width) ||
        !source_span(f->u,f->c_stride,f->height/2,f->width/2) ||
        !source_span(f->v,f->c_stride,f->height/2,f->width/2) ||
        source_alias(f->y,f->y_stride,f->height,f->width,s) ||
        source_alias(f->u,f->c_stride,f->height/2,f->width/2,s) ||
        source_alias(f->v,f->c_stride,f->height/2,f->width/2,s)) { errno=EINVAL; return -1; }
    s->busy=1;
    ybytes=(size_t)f->width*f->height; cbytes=ybytes/4;
    for (unsigned y=0;y<f->height;y++) memcpy(s->in.map+(size_t)y*f->width,f->y+(size_t)y*f->y_stride,f->width);
    for (unsigned y=0;y<f->height/2;y++) {
        memcpy(s->in.map+ybytes+(size_t)y*f->width/2,f->u+(size_t)y*f->c_stride,f->width/2);
        memcpy(s->in.map+ybytes+cbytes+(size_t)y*f->width/2,f->v+(size_t)y*f->c_stride,f->width/2);
    }
    memset(&before,0,sizeof(before)); before.plan=s->p;
    before.input=s->in.handle; before.output=s->out.handle; checked=before;
    if (s->b.check(s->b.ctx,&checked) || !plan_equal(&before.plan,&checked.plan) ||
        checked.input!=before.input || checked.output!=before.output ||
        s->b.sync_input(s->b.ctx,&s->in)) goto fail;
    done=s->b.queue(s->b.ctx,&checked);
    if (done!=IC_IPU_COMPLETE) {
        if (done!=IC_IPU_FAILED_IDLE) s->quarantine=1;
        goto fail;
    }
    if (s->b.sync_output(s->b.ctx,&s->out)) { s->quarantine=1; goto fail; }
    memset(s->packed,0,sizeof(s->packed));
    for (unsigned y=0;y<s->p.out_h;y++)
        memcpy(s->packed+((size_t)(y+s->p.dst_y)*560+s->p.dst_x)*2,
               s->out.map+(size_t)y*s->p.out_w*2,(size_t)s->p.out_w*2);
    /* Existing ic_write_exact(...,350) may be used as publisher. Guardian
     * keeps reading exactly 352800 bytes and never receives a DMA pointer. */
    if (s->publish(s->publish_ctx,s->packed,sizeof(s->packed))) goto fail;
    s->busy=0; return 0;
fail:
    s->busy=0; s->failed=1; errno=EIO; return -1;
}
int ic_ipu_quarantined(const struct ic_ipu_candidate *s) { return s && s->quarantine; }
int ic_ipu_close(struct ic_ipu_candidate *s) {
    if (!s) return 0;
    if (s->busy || s->quarantine) { errno=EBUSY; return -1; }
    s->b.release(s->b.ctx,&s->out); s->b.release(s->b.ctx,&s->in); free(s); return 0;
}
