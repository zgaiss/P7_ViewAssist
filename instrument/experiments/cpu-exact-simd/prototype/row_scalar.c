#include "p7_row.h"
static unsigned channel(int x) {
    /* clip(floor((value + 128)/256),0,255), avoiding signed shifts. */
    return x < 0 ? 0u : x > 65535 ? 255u : (unsigned)x >> 8;
}
void p7_rgb565_row_scalar(const unsigned char *y, const unsigned char *u,
                          const unsigned char *v, unsigned char *dst, unsigned n) {
    for (unsigned i=0;i<n;++i) {
        int c=(int)y[i]-16, d=(int)u[i]-128, e=(int)v[i]-128;
        unsigned r=channel(298*c+409*e+128);
        unsigned g=channel(298*c-100*d-208*e+128);
        unsigned b=channel(298*c+516*d+128);
        unsigned p=((r>>3)<<11)|((g>>2)<<5)|(b>>3);
        dst[2*i]=(unsigned char)p;dst[2*i+1]=(unsigned char)(p>>8);
    }
}
