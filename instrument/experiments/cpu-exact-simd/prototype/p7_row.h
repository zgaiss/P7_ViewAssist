#ifndef P7_ROW_H
#define P7_ROW_H
/* Per-output-pixel samples, not I420 chroma planes. No ownership transfer. */
void p7_rgb565_row_scalar(const unsigned char *y, const unsigned char *u,
                          const unsigned char *v, unsigned char *dst, unsigned n);
/* Call only after OS-reported NEON support. Compiled in a separate softfp TU. */
void p7_rgb565_row_neon(const unsigned char *y, const unsigned char *u,
                        const unsigned char *v, unsigned char *dst, unsigned n);
#endif
