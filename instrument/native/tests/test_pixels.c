/* Offline CPU tests only. No device open, VPU loading, adb or display writes. */
#include "ic_decoder.h"
#include "ic_pixels.h"
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char output[IC_PIXELS_RGB565_BYTES + 2u];
static unsigned char *const dst = output + 1;
static unsigned pixel(unsigned x, unsigned y) {
    size_t at = ((size_t)y * 560u + x) * 2u;
    return dst[at] | (unsigned)dst[at + 1] << 8;
}
static void guards(void) {
    assert(output[0] == 0xa5 && output[sizeof(output)-1] == 0x5a);
}
static void reset(void) {
    memset(output, 0x31, sizeof(output));
    output[0] = 0xa5; output[sizeof(output)-1] = 0x5a;
}
static void solid(unsigned char yv, unsigned char uv, unsigned char vv, unsigned want) {
    unsigned char y[16 * 9], u[8 * 5], v[8 * 5];
    memset(y, yv, sizeof(y)); memset(u, uv, sizeof(u)); memset(v, vv, sizeof(v));
    struct ic_yuv_frame f = { y, u, v, 16, 9, 16, 8 };
    reset();
    assert(!ic_pixels_rgb565(&f, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES));
    for (unsigned j = 0; j < 315; ++j)
        for (unsigned i = 0; i < 560; ++i) assert(pixel(i, j) == want);
    guards();
}
static void test_colors(void) {
    solid(16, 128, 128, 0x0000); /* legal black */
    solid(235, 128, 128, 0xffff); /* legal white */
    solid(81, 90, 240, 0xf800); /* BT.601 red */
    solid(145, 54, 34, 0x07e0); /* BT.601 green */
    solid(41, 240, 110, 0x001f); /* BT.601 blue */
    solid(0, 0, 0, 0x0420); /* negative coefficients floor and clamp */
    solid(255, 255, 255, 0xfbff); /* upper clamp */
}
static void test_fit_and_stride(void) {
    /* 4:3 image -> 420x315, horizontal black bars 70 pixels per side.
     * Padding is hostile: touching it would create a non-white pixel. */
    unsigned char y[12 * 6], u[7 * 3], v[7 * 3];
    memset(y, 0, sizeof(y)); memset(u, 0, sizeof(u)); memset(v, 255, sizeof(v));
    for (unsigned j = 0; j < 6; ++j) memset(y + j * 12, 235, 8);
    for (unsigned j = 0; j < 3; ++j) {
        memset(u + j * 7, 128, 4); memset(v + j * 7, 128, 4);
    }
    struct ic_yuv_frame f = { y, u, v, 8, 6, 12, 7 };
    reset();
    assert(!ic_pixels_rgb565(&f, 0, 0, 8, 6, dst, IC_PIXELS_RGB565_BYTES));
    for (unsigned j = 0; j < 315; ++j)
        for (unsigned i = 0; i < 560; ++i)
            assert(pixel(i, j) == (i >= 70 && i < 490 ? 0xffff : 0));
    guards();
    /* 16:4 explicit crop -> 560x140, top87/bottom88 opaque black. */
    unsigned char wide_y[16 * 8], wide_u[8 * 4], wide_v[8 * 4];
    memset(wide_y, 16, sizeof(wide_y));
    memset(wide_y + 16 * 2, 235, 16 * 4);
    memset(wide_u, 128, sizeof(wide_u)); memset(wide_v, 128, sizeof(wide_v));
    f = (struct ic_yuv_frame){wide_y, wide_u, wide_v, 16, 8, 16, 8};
    reset();
    assert(!ic_pixels_rgb565(&f, 0, 2, 16, 4, dst, IC_PIXELS_RGB565_BYTES));
    for (unsigned j = 0; j < 315; ++j)
        for (unsigned i = 0; i < 560; ++i)
            assert(pixel(i, j) == (j >= 87 && j < 227 ? 0xffff : 0));
    guards();
}
static void test_explicit_crop_and_chroma(void) {
    /* Odd 7x5 dimensions, padded planes, odd crop origin (3,1). No guessed
     * quadrant: chroma coordinates are floor(absolute source coordinate/2). */
    unsigned char y[11 * 5], u[7 * 3], v[7 * 3];
    memset(y, 81, sizeof(y)); memset(u, 128, sizeof(u)); memset(v, 128, sizeof(v));
    for (unsigned j = 0; j < 3; ++j) {
        u[j*7+1] = 90; v[j*7+1] = 240; /* x=2,3 -> red */
        u[j*7+2] = 240; v[j*7+2] = 110; /* x=4,5 -> blue-ish at Y81 */
    }
    struct ic_yuv_frame f = { y, u, v, 7, 5, 11, 7 };
    reset();
    assert(!ic_pixels_rgb565(&f, 3, 1, 3, 3, dst, IC_PIXELS_RGB565_BYTES));
    /* square fit is x=[122,437), y=[0,315); source x3 for first105 columns. */
    assert(pixel(121, 100) == 0 && pixel(437, 100) == 0);
    assert(pixel(122, 100) == 0xf800 && pixel(226, 100) == 0xf800);
    assert(pixel(227, 100) != 0xf800 && pixel(436, 100) != 0xf800);
    guards();
    /* Select last column/row legally; nearest-neighbour must never overread. */
    assert(!ic_pixels_rgb565(&f, 6, 4, 1, 1, dst, IC_PIXELS_RGB565_BYTES));
    guards();
}
static void rejected(const struct ic_yuv_frame *f, unsigned x, unsigned y,
                     unsigned w, unsigned h, unsigned char *out, size_t n) {
    reset(); errno = 0;
    assert(ic_pixels_rgb565(f, x, y, w, h, out, n) == -1);
    assert(errno == EINVAL);
    for (size_t i = 1; i < sizeof(output)-1; ++i) assert(output[i] == 0x31);
    guards();
}
static void test_invalid(void) {
    unsigned char y[16 * 10], u[8 * 5], v[8 * 5];
    struct ic_yuv_frame f = { y, u, v, 16, 10, 16, 8 }, bad;
    rejected(NULL, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 0, 0, 16, 9, NULL, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES - 1);
    rejected(&f, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES + 1);
    rejected(&f, 0, 0, 0, 9, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 0, 0, 16, 0, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 16, 0, 1, 9, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 0, 10, 16, 1, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 15, 0, 2, 9, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 0, 9, 16, 2, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, UINT_MAX, UINT_MAX, UINT_MAX, UINT_MAX, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.y = NULL;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.u = NULL;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.v = NULL;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.width = 0;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.height = 0;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.width = 1281;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.height = 721;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.y_stride = 15;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.c_stride = 7;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.y = dst;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.u = dst - 1;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.v = dst + IC_PIXELS_RGB565_BYTES - 1;
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    bad = f; bad.y = (const unsigned char *)(UINTPTR_MAX - 3);
    rejected(&bad, 0, 0, 16, 9, dst, IC_PIXELS_RGB565_BYTES);
    rejected(&f, 0, 0, 16, 9, (unsigned char *)(UINTPTR_MAX - 3), IC_PIXELS_RGB565_BYTES);
}
static void test_maximum(void) {
    const unsigned ys = 1296, cs = 656;
    unsigned char *y = malloc((size_t)ys * 720);
    unsigned char *u = malloc((size_t)cs * 360), *v = malloc((size_t)cs * 360);
    assert(y && u && v);
    memset(y, 235, (size_t)ys * 720);
    memset(u, 128, (size_t)cs * 360); memset(v, 128, (size_t)cs * 360);
    struct ic_yuv_frame f = {y, u, v, 1280, 720, ys, cs};
    reset();
    assert(!ic_pixels_rgb565(&f, 0, 0, 1280, 720, dst, IC_PIXELS_RGB565_BYTES));
    for (unsigned j = 0; j < 315; ++j)
        for (unsigned i = 0; i < 560; ++i) assert(pixel(i, j) == 0xffff);
    assert(!ic_pixels_rgb565(&f, 1279, 719, 1, 1, dst, IC_PIXELS_RGB565_BYTES));
    guards(); free(y); free(u); free(v);
}
int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    puts("pixels: start colors"); test_colors();
    puts("pixels: stride and fit"); test_fit_and_stride();
    puts("pixels: explicit crop and chroma"); test_explicit_crop_and_chroma();
    puts("pixels: invalid arguments"); test_invalid();
    puts("pixels: 720p maximum"); test_maximum();
    puts("pixels: colors, LE, stride, crop, odd chroma, aspect-fit, bounds, alias, overflow, 720p PASS");
    return 0;
}
