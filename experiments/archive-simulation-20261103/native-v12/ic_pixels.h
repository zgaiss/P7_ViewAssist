#ifndef IC_PIXELS_H
#define IC_PIXELS_H
#include <stddef.h>
#define IC_PIXELS_WIDTH 560u
#define IC_PIXELS_HEIGHT 315u
#define IC_PIXELS_RGB565_BYTES (IC_PIXELS_WIDTH * IC_PIXELS_HEIGHT * 2u)
struct ic_yuv_frame;
/* CPU-owned I420, BT.601 limited-range -> RGB565 little endian. Explicit crop,
 * nearest-neighbour aspect-fit, opaque black letterbox. No inferred quadrant.
 * len must be exactly IC_PIXELS_RGB565_BYTES. Source planes must have storage
 * for their declared strides/height and must not overlap dst. Odd crops and
 * odd image dimensions are supported (chroma dimensions round upward).
 * Invalid arguments fail before modifying dst; caller owns buffer lifetimes. */
int ic_pixels_rgb565(const struct ic_yuv_frame *frame,
                     unsigned crop_x, unsigned crop_y,
                     unsigned crop_w, unsigned crop_h,
                     unsigned char *dst, size_t len);
#endif
