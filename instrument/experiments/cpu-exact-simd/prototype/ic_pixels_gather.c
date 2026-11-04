/* Isolated unintegrated prototype. Baseline validation/crop preserved. */
#include "ic_pixels.h"
#include "ic_decoder.h"
#include <errno.h>
#include <stdint.h>
#include <string.h>

#include "p7_row.h"
#ifndef P7_HAVE_NEON_OBJECT
#define P7_HAVE_NEON_OBJECT 0
#endif
#if P7_HAVE_NEON_OBJECT
#include <sys/auxv.h>
#include <asm/hwcap.h>
#if !defined(__linux__) || !defined(__arm__) || defined(__ARM_PCS_VFP)
#error "NEON dispatcher requires Linux ARM32 base AAPCS"
#endif
#endif
static int disjoint(const unsigned char *src, size_t n,
                    const unsigned char *dst, size_t len) {
    uintptr_t a = (uintptr_t)src, b = (uintptr_t)dst;
    return a <= UINTPTR_MAX - n && b <= UINTPTR_MAX - len &&
           (a + n <= b || b + len <= a);
}
int ic_pixels_rgb565(const struct ic_yuv_frame *f,
                     unsigned cx, unsigned cy, unsigned cw, unsigned ch,
                     unsigned char *dst, size_t len) {
    size_t yn, cn;
    unsigned w, h, ox, oy;
    unsigned sx_tab[IC_PIXELS_WIDTH];
    unsigned char yy[IC_PIXELS_WIDTH], uu[IC_PIXELS_WIDTH], vv[IC_PIXELS_WIDTH];
    void (*row_convert)(const unsigned char *,const unsigned char *,
                        const unsigned char *,unsigned char *,unsigned)=p7_rgb565_row_scalar;
#if P7_HAVE_NEON_OBJECT
    /* Unsupported/missing OS feature report leaves the scalar fallback. */
    if (getauxval(AT_HWCAP) & HWCAP_NEON) row_convert=p7_rgb565_row_neon;
#endif
    if (!f || !f->y || !f->u || !f->v || !dst ||
        len != IC_PIXELS_RGB565_BYTES || !f->width || !f->height ||
        f->width > 1280u || f->height > 720u ||
        f->y_stride < f->width || f->c_stride < (f->width + 1u) / 2u ||
        !cw || !ch || cx >= f->width || cy >= f->height ||
        cw > f->width - cx || ch > f->height - cy ||
        f->y_stride > SIZE_MAX / f->height ||
        f->c_stride > SIZE_MAX / ((f->height + 1u) / 2u)) {
        errno = EINVAL; return -1;
    }
    yn = (size_t)f->y_stride * f->height;
    cn = (size_t)f->c_stride * ((f->height + 1u) / 2u);
    if (!disjoint(f->y, yn, dst, len) || !disjoint(f->u, cn, dst, len) ||
        !disjoint(f->v, cn, dst, len)) { errno = EINVAL; return -1; }
    if ((uint64_t)cw * IC_PIXELS_HEIGHT >= (uint64_t)ch * IC_PIXELS_WIDTH) {
        w = IC_PIXELS_WIDTH; h = (unsigned)((uint64_t)ch * w / cw);
    } else {
        h = IC_PIXELS_HEIGHT; w = (unsigned)((uint64_t)cw * h / ch);
    }
    if (!w) w = 1;
    if (!h) h = 1;
    ox = (IC_PIXELS_WIDTH - w) / 2u;
    oy = (IC_PIXELS_HEIGHT - h) / 2u;
    memset(dst, 0, len);
    /* ARM32 has no 64-bit divide: compute the column map once, never per pixel. */
    for (unsigned dx = 0; dx < w; ++dx)
        sx_tab[dx] = cx + (unsigned)((uint32_t)dx * cw / w);
    for (unsigned dy = 0; dy < h; ++dy) {
        unsigned sy = cy + (unsigned)((uint32_t)dy * ch / h);
        unsigned char *row = dst + ((size_t)(dy + oy) * IC_PIXELS_WIDTH + ox) * 2u;
        for (unsigned dx = 0; dx < w; ++dx) {
            unsigned sx = sx_tab[dx];
            size_t uv = (size_t)(sy / 2u) * f->c_stride + sx / 2u;
            yy[dx] = f->y[(size_t)sy * f->y_stride + sx];
            uu[dx] = f->u[uv];
            vv[dx] = f->v[uv];
        }
        row_convert(yy,uu,vv,row,w);
    }
    return 0;
}
