#define _POSIX_C_SOURCE 200809L
#include "ic_pixels.h"
#include "ic_decoder.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifndef P7_HAVE_NEON_OBJECT
#define P7_HAVE_NEON_OBJECT 0
#endif
#if P7_HAVE_NEON_OBJECT
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif
int ic_pixels_rgb565_reference(const struct ic_yuv_frame *,unsigned,unsigned,unsigned,unsigned,unsigned char *,size_t);
static uint64_t ns(clockid_t id) {struct timespec t;assert(!clock_gettime(id,&t));return (uint64_t)t.tv_sec*1000000000ull+(uint64_t)t.tv_nsec;}
static int cmp(const void *a,const void *b) {double x=*(const double*)a,y=*(const double*)b;return (x>y)-(x<y);}
static void bench(const char *name,int (*fn)(const struct ic_yuv_frame *,unsigned,unsigned,unsigned,unsigned,unsigned char *,size_t),const struct ic_yuv_frame *f,unsigned cx,unsigned cy,unsigned cw,unsigned ch,unsigned char *out) {
 enum {N=1000,WARM=60};double times[N];uint64_t cpu0=0,cpu1;unsigned long hash=0;
 for(unsigned i=0;i<N+WARM;++i) {
  if(i==WARM)cpu0=ns(CLOCK_PROCESS_CPUTIME_ID);
  uint64_t a=ns(CLOCK_MONOTONIC);assert(!fn(f,cx,cy,cw,ch,out,IC_PIXELS_RGB565_BYTES));uint64_t b=ns(CLOCK_MONOTONIC);
  if(i>=WARM)times[i-WARM]=(b-a)/1000.;
 }
 cpu1=ns(CLOCK_PROCESS_CPUTIME_ID);
 for(unsigned i=0;i<IC_PIXELS_RGB565_BYTES;++i)hash=hash*33+out[i];
 qsort(times,N,sizeof(*times),cmp);
 printf("%s source=%ux%u crop=%u,%u,%u,%u p50_us=%.3f p95_us=%.3f p99_us=%.3f max_us=%.3f process_cpu_us_per_frame=%.3f hash=%lx\n",name,f->width,f->height,cx,cy,cw,ch,times[N/2],times[N*95/100],times[N*99/100],times[N-1],(cpu1-cpu0)/1000./N,hash);
}
int main(void) {
 unsigned char *y=malloc(1312u*720),*u=malloc(672u*360),*v=malloc(672u*360),*out=malloc(IC_PIXELS_RGB565_BYTES);
 assert(y&&u&&v&&out);
 for(unsigned i=0;i<1312u*720;++i)y[i]=(unsigned char)(16+i*3%220);
 for(unsigned i=0;i<672u*360;++i){u[i]=(unsigned char)(16+i*5%225);v[i]=(unsigned char)(16+i*7%225);}
#if P7_HAVE_NEON_OBJECT
 printf("CPU-only ARM build: OS HWCAP_NEON=%d (no VPU, IPC, framebuffer, network or playback measured)\n",!!(getauxval(AT_HWCAP)&HWCAP_NEON));
#else
 puts("CPU-only HOST SCALAR BUILD; NOT A TARGET OR NEON BENCHMARK");
#endif
 const unsigned cases[][6]={{1280,720,0,0,1280,720},{1280,720,1,1,639,359},{320,240,0,0,320,240},{7,5,3,1,3,3}};
 for(unsigned i=0;i<sizeof(cases)/sizeof(cases[0]);++i) {
  const unsigned *c=cases[i];struct ic_yuv_frame f={y,u,v,c[0],c[1],1312,672};
  bench("baseline",ic_pixels_rgb565_reference,&f,c[2],c[3],c[4],c[5],out);
  bench("gather_dispatch",ic_pixels_rgb565,&f,c[2],c[3],c[4],c[5],out);
 }
 free(y);free(u);free(v);free(out);return 0;
}
