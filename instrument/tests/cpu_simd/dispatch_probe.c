#include "ic_pixels.h"
#include "ic_decoder.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>
static unsigned gather_calls, row_calls, probe_calls;
static int force_missing;
unsigned long __real_getauxval(unsigned long);
unsigned long __wrap_getauxval(unsigned long type) {
    ++probe_calls;
    if (force_missing && type == AT_HWCAP) {errno=ENOENT;return 0;}
    return __real_getauxval(type);
}
void __real_ic_pixels_gather_neon(const unsigned char *,const unsigned char *,const unsigned char *,const unsigned *,unsigned char *,unsigned);
void __wrap_ic_pixels_gather_neon(const unsigned char *y,const unsigned char *u,const unsigned char *v,const unsigned *sx,unsigned char *d,unsigned n) {
    ++gather_calls; __real_ic_pixels_gather_neon(y,u,v,sx,d,n);
}
void __real_ic_pixels_row_neon(const unsigned char *,const unsigned char *,const unsigned char *,unsigned char *,unsigned);
void __wrap_ic_pixels_row_neon(const unsigned char *y,const unsigned char *u,const unsigned char *v,unsigned char *d,unsigned n) {
    ++row_calls; __real_ic_pixels_row_neon(y,u,v,d,n);
}
int main(int argc,char **argv) {
    int absent=argc>1&&!strcmp(argv[1],"absent");
    force_missing=argc>1&&!strcmp(argv[1],"missing");
    unsigned char y[16*9],u[8*5],v[8*5],dst[IC_PIXELS_RGB565_BYTES];
    memset(y,235,sizeof y);memset(u,128,sizeof u);memset(v,128,sizeof v);
    struct ic_yuv_frame f={y,u,v,16,9,16,8};
    errno=E2BIG;
    assert(!ic_pixels_rgb565(&f,0,0,16,9,dst,sizeof dst));
    assert(errno==E2BIG);
    for(unsigned i=0;i<sizeof dst;++i)assert(dst[i]==255);
    int has_neon=!!(__real_getauxval(AT_HWCAP)&HWCAP_NEON);
    int expect_neon=!absent&&!force_missing&&has_neon;
    assert(gather_calls==(expect_neon?315u:0u));
    assert(row_calls==gather_calls);
    assert(probe_calls==(absent?0u:1u));
    unsigned before=probe_calls;
    assert(ic_pixels_rgb565(NULL,0,0,16,9,dst,sizeof dst)==-1);
    assert(errno==EINVAL&&probe_calls==before);
    printf("PASS integrated dispatch: actual_neon=%d missing_report=%d absent_symbol=%d gather_calls=%u neon_rows=%u errno_preserved=1 invalid_frame_not_probed=1\n",has_neon,force_missing,absent,gather_calls,row_calls);
    return 0;
}
