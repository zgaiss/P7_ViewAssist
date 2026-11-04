#include "../../native/pixels_simd/ic_pixels_simd.h"
#include <stdio.h>
/* Deliberately ungated CPU-only negative control. Never a production entry. */
int main(void) {
    unsigned char y[8]={16,16,16,16,16,16,16,16};
    unsigned char u[8]={128,128,128,128,128,128,128,128};
    unsigned char v[8]={128,128,128,128,128,128,128,128}, out[16];
    ic_pixels_row_neon(y,u,v,out,8);
    for (unsigned i=0;i<16;++i) if(out[i]) return 1;
    puts("Direct NEON instruction probe passed");
    return 0;
}
