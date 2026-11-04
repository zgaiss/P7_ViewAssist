#define _GNU_SOURCE
#include "../../native/pixels_simd/ic_pixels_simd.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <unistd.h>
#include <asm/hwcap.h>
static unsigned char *alloc_pages(size_t page) {
    unsigned char *p=mmap(0,3*page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);assert(!mprotect(p+page,page,PROT_READ|PROT_WRITE));return p;
}
int main(void) {
    if(!(getauxval(AT_HWCAP)&HWCAP_NEON)){puts("SKIP: NEON missing");return 77;}
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    assert(page>=4096);
    unsigned char *maps[4];for(unsigned i=0;i<4;++i)maps[i]=alloc_pages(page);
    unsigned char expected[1140];unsigned cases=0;
    for(unsigned n=0;n<=570;++n)for(unsigned side=0;side<2;++side)for(unsigned pad=0;pad<2;++pad) {
        unsigned char *y=maps[0]+page+(side?page-n:0);
        unsigned char *u=maps[1]+page+(side?page-n:0);
        unsigned char *v=maps[2]+page+(side?page-n:0);
        unsigned char *out=maps[3]+page+(side?page-2*n-pad:pad);
        memset(maps[3]+page,0xa5,page);
        for(unsigned i=0;i<n;++i){y[i]=(unsigned char)i;u[i]=(unsigned char)(7*i);v[i]=(unsigned char)(13*i);}
        ic_pixels_row_scalar(y,u,v,expected,n);
        ic_pixels_row_neon(y,u,v,out,n);
        assert(!memcmp(expected,out,2*n));
        for(size_t i=0;i<page;++i) {unsigned char *at=maps[3]+page+i;if(at<out||at>=out+2*n)assert(*at==0xa5);}
        for(unsigned i=0;i<n;++i)assert(y[i]==(unsigned char)i&&u[i]==(unsigned char)(7*i)&&v[i]==(unsigned char)(13*i));
        ++cases;
    }
    for(unsigned i=0;i<4;++i)assert(!munmap(maps[i],3*page));
    printf("PASS guard pages: %u cases; lengths 0..570; before/after protected input and output pages; aligned/unaligned output; sources unchanged\n",cases);
    return 0;
}
