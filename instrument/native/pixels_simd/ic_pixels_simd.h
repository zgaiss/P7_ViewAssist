#ifndef IC_PIXELS_SIMD_H
#define IC_PIXELS_SIMD_H
/* Private, synchronous CPU-only helpers. All interfaces use base AAPCS.
 * No pointers are retained. Only row_neon.c is compiled with NEON enabled. */
int ic_pixels_neon_available(void);
/* Caller already validated the frame and computed the absolute column map.
 * n <= IC_PIXELS_WIDTH; y/u/v point at the selected source/chroma rows. */
void ic_pixels_gather_neon(const unsigned char *y, const unsigned char *u,
                           const unsigned char *v, const unsigned *sx,
                           unsigned char *dst, unsigned n);
void ic_pixels_row_scalar(const unsigned char *y, const unsigned char *u,
                          const unsigned char *v, unsigned char *dst, unsigned n);
/* Call only after OS-reported NEON support; separate softfp object. */
void ic_pixels_row_neon(const unsigned char *y, const unsigned char *u,
                        const unsigned char *v, unsigned char *dst, unsigned n);
#endif
