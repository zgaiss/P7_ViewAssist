#include "ic_pixels.h"
#include "ic_decoder.h"
#include <errno.h>
#include <stdint.h>
#include <string.h>

/* Experimental build opt-in; the default preprocesses to the scalar path. */
#ifndef IC_PIXELS_EXPERIMENTAL_NEON
#define IC_PIXELS_EXPERIMENTAL_NEON 0
#endif
#if IC_PIXELS_EXPERIMENTAL_NEON
#include "pixels_simd/ic_pixels_simd.h"
#if !defined(__linux__) || !defined(__arm__) || defined(__ARM_PCS_VFP)
#error "Experimental NEON requires Linux ARM32 base AAPCS"
#endif
#if defined(__ARM_NEON)
#error "Compile ic_pixels.c with baseline flags, not NEON"
#endif
#endif

static int clip(int n) { return n < 0 ? 0 : n > 255 ? 255 : n; }
static int floor256(int n) { return n >= 0 ? (n >> 8) : -(((-n) + 255) >> 8); }
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
#if IC_PIXELS_EXPERIMENTAL_NEON
    /* Per-call OS probe: no mutable cache or asynchronous ownership changes.
     * Unsupported/missing capability reporting uses the original scalar loop. */
    int use_neon = ic_pixels_neon_available();
#endif
    memset(dst, 0, len);
    /* ARM32 has no 64-bit divide: compute the column map once, never per pixel. */
    for (unsigned dx = 0; dx < w; ++dx)
        sx_tab[dx] = cx + (unsigned)((uint32_t)dx * cw / w);
    for (unsigned dy = 0; dy < h; ++dy) {
        unsigned sy = cy + (unsigned)((uint32_t)dy * ch / h);
        unsigned char *row = dst + ((size_t)(dy + oy) * IC_PIXELS_WIDTH + ox) * 2u;
#if IC_PIXELS_EXPERIMENTAL_NEON
        if (use_neon) {
            ic_pixels_gather_neon(f->y + (size_t)sy * f->y_stride,
                                 f->u + (size_t)(sy / 2u) * f->c_stride,
                                 f->v + (size_t)(sy / 2u) * f->c_stride,
                                 sx_tab, row, w);
            continue;
        }
#endif
        for (unsigned dx = 0; dx < w; ++dx) {
            unsigned sx = sx_tab[dx];
            size_t uv = (size_t)(sy / 2u) * f->c_stride + sx / 2u;
            int c = (int)f->y[(size_t)sy * f->y_stride + sx] - 16;
            int u = (int)f->u[uv] - 128, v = (int)f->v[uv] - 128;
            unsigned r = (unsigned)clip(floor256(298*c + 409*v + 128));
            unsigned g = (unsigned)clip(floor256(298*c - 100*u - 208*v + 128));
            unsigned b = (unsigned)clip(floor256(298*c + 516*u + 128));
            unsigned p = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
            row[2u*dx] = (unsigned char)p;
            row[2u*dx+1u] = (unsigned char)(p >> 8);
        }
    }
    return 0;
}
