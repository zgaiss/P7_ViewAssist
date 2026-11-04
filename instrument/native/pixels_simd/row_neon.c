/* Compile only with ARM32 -march=armv7-a -mfpu=neon -mfloat-abi=softfp.
 * Integer-only base-AAPCS interface; never link an armhf variant.
 * Requires runtime dispatcher and scalar tail. No device/display access.
 */
#include <arm_neon.h>
#include "ic_pixels_simd.h"
#if !defined(__arm__) || !defined(__ARM_NEON)
#error "This file must be separately compiled for ARM32 NEON"
#endif
#if defined(__ARM_PCS_VFP)
#error "Hard-float ABI is incompatible with the ARMEL receiver"
#endif
static uint8x8_t pack_channel(int32x4_t lo, int32x4_t hi) {
    /* Numerators already include +128. Signed arithmetic shift, saturate
     * to unsigned16, then unsigned8 exactly implements floor and clip. */
    return vqmovn_u16(vcombine_u16(vqshrun_n_s32(lo,8),vqshrun_n_s32(hi,8)));
}
void ic_pixels_row_neon(const unsigned char *y, const unsigned char *u,
                        const unsigned char *v, unsigned char *dst, unsigned n) {
    unsigned i=0;
    for (;n-i>=8;i+=8) {
        int16x8_t c=vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vld1_u8(y+i))),vdupq_n_s16(16));
        int16x8_t d=vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vld1_u8(u+i))),vdupq_n_s16(128));
        int16x8_t e=vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vld1_u8(v+i))),vdupq_n_s16(128));
        int32x4_t cl=vaddq_s32(vmull_n_s16(vget_low_s16(c),298),vdupq_n_s32(128));
        int32x4_t ch=vaddq_s32(vmull_n_s16(vget_high_s16(c),298),vdupq_n_s32(128));
        uint8x8_t r=pack_channel(vmlal_n_s16(cl,vget_low_s16(e),409),vmlal_n_s16(ch,vget_high_s16(e),409));
        uint8x8_t g=pack_channel(vmlsl_n_s16(vmlsl_n_s16(cl,vget_low_s16(d),100),vget_low_s16(e),208),vmlsl_n_s16(vmlsl_n_s16(ch,vget_high_s16(d),100),vget_high_s16(e),208));
        uint8x8_t b=pack_channel(vmlal_n_s16(cl,vget_low_s16(d),516),vmlal_n_s16(ch,vget_high_s16(d),516));
        uint16x8_t p=vorrq_u16(vorrq_u16(vshlq_n_u16(vmovl_u8(vshr_n_u8(r,3)),11),vshlq_n_u16(vmovl_u8(vshr_n_u8(g,2)),5)),vmovl_u8(vshr_n_u8(b,3)));
        /* Explicit low/high byte interleave preserves RGB565LE independent
         * of a uint16_t store's alignment and byte order. */
        uint8x8x2_t bytes;
        bytes.val[0]=vmovn_u16(p);bytes.val[1]=vshrn_n_u16(p,8);
        vst2_u8(dst+2*i,bytes);
    }
    ic_pixels_row_scalar(y+i,u+i,v+i,dst+2*i,n-i);
}
