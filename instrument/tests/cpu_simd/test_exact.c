/* CPU-only correctness harness. Does not link receiver/decoder/display or
 * open any hardware. P7_TEST_NEON=1 explicitly requires an ARM NEON build. */
#include "../../native/pixels_simd/ic_pixels_simd.h"
#include "ic_pixels.h"
#include "ic_decoder.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef P7_TEST_NEON
#define P7_TEST_NEON 0
#endif
#if P7_TEST_NEON
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif
int ic_pixels_rgb565_reference(const struct ic_yuv_frame *,unsigned,unsigned,unsigned,unsigned,unsigned char *,size_t);
static int floor256(int x) { return x>=0 ? x>>8 : -(((-x)+255)>>8); }
static unsigned clip(int x) { return x<0?0u:x>255?255u:(unsigned)x; }
static unsigned reference(unsigned y,unsigned u,unsigned v) {
 int c=(int)y-16,d=(int)u-128,e=(int)v-128;
 unsigned r=clip(floor256(298*c+409*e+128));
 unsigned g=clip(floor256(298*c-100*d-208*e+128));
 unsigned b=clip(floor256(298*c+516*d+128));
 return ((r>>3)<<11)|((g>>2)<<5)|(b>>3);
}
static uint32_t seed=0x8f17a5c3u;
static uint32_t random32(void) {seed^=seed<<13;seed^=seed>>17;seed^=seed<<5;return seed;}
static void test_row(void (*row)(const unsigned char *,const unsigned char *,const unsigned char *,unsigned char *,unsigned)) {
 unsigned char y[570],u[570],v[570],out[1142];
 unsigned long checked=0;
 for (unsigned a=0;a<256;++a) for(unsigned b=0;b<256;++b) {
  memset(y,a,256);memset(u,b,256);
  for(unsigned c=0;c<256;++c)v[c]=(unsigned char)c;
  memset(out,0xA5,sizeof(out));row(y,u,v,out+1,256);
  assert(out[0]==0xA5&&out[513]==0xA5);
  for(unsigned c=0;c<256;++c){unsigned p=out[1+2*c]|((unsigned)out[2+2*c]<<8);assert(p==reference(a,b,c));++checked;}
 }
 for(unsigned n=0;n<=570;++n) {
  for(unsigned i=0;i<n;++i){y[i]=(unsigned char)random32();u[i]=(unsigned char)random32();v[i]=(unsigned char)random32();}
  memset(out,0xA5,sizeof(out));row(y,u,v,out+1,n);
  assert(out[0]==0xA5&&out[2*n+1]==0xA5);
  for(unsigned i=0;i<n;++i)assert((out[1+2*i]|((unsigned)out[2+2*i]<<8))==reference(y[i],u[i],v[i]));
 }
 /* Exact-size allocations let ASan catch read-past-tail in the tested path. */
 for(unsigned n=0;n<=570;++n) {
  unsigned char *a=malloc(n?n:1),*b=malloc(n?n:1),*c=malloc(n?n:1),*d=malloc(2*n+2);
  assert(a&&b&&c&&d);
  for(unsigned i=0;i<n;++i){a[i]=(unsigned char)i;b[i]=(unsigned char)(i*7);c[i]=(unsigned char)(i*13);}
  memset(d,0xA5,2*n+2);row(a,b,c,d+1,n);
  assert(d[0]==0xA5&&d[2*n+1]==0xA5);
  for(unsigned i=0;i<n;++i){assert(a[i]==(unsigned char)i&&b[i]==(unsigned char)(i*7)&&c[i]==(unsigned char)(i*13));assert((d[1+2*i]|((unsigned)d[2+2*i]<<8))==reference(a[i],b[i],c[i]));}
  free(a);free(b);free(c);free(d);
 }
 printf("PASS row: %lu exhaustive YUV triples; lengths 0..570, exact-size tails and unaligned destination\n",checked);
}
static void test_frames(void) {
 unsigned char *y=malloc(1312u*720),*u=malloc(672u*360),*v=malloc(672u*360);
 unsigned char *a=malloc(IC_PIXELS_RGB565_BYTES+2),*b=malloc(IC_PIXELS_RGB565_BYTES+2);
 assert(y&&u&&v&&a&&b);
 for(unsigned n=0;n<1000;++n) {
  unsigned w=1+random32()%1280,h=1+random32()%720;
  unsigned ys=w+random32()%33,cs=(w+1)/2+random32()%33;
  unsigned cx=random32()%w,cy=random32()%h,cw=1+random32()%(w-cx),ch=1+random32()%(h-cy);
  for(unsigned i=0;i<ys*h;++i)y[i]=(unsigned char)random32();
  for(unsigned i=0;i<cs*((h+1)/2);++i){u[i]=(unsigned char)random32();v[i]=(unsigned char)random32();}
  struct ic_yuv_frame f={y,u,v,w,h,ys,cs};
  memset(a,0xA5,IC_PIXELS_RGB565_BYTES+2);memset(b,0xA5,IC_PIXELS_RGB565_BYTES+2);
  assert(!ic_pixels_rgb565_reference(&f,cx,cy,cw,ch,a+1,IC_PIXELS_RGB565_BYTES));
  assert(!ic_pixels_rgb565(&f,cx,cy,cw,ch,b+1,IC_PIXELS_RGB565_BYTES));
  assert(!memcmp(a,b,IC_PIXELS_RGB565_BYTES+2));
  assert(a[0]==0xA5&&a[IC_PIXELS_RGB565_BYTES+1]==0xA5);
 }
 free(y);free(u);free(v);free(a);free(b);
 puts("PASS frames: 1000 deterministic randomized source dimensions/strides/crops, all output bytes equal exact baseline");
}
int main(void) {
#if P7_TEST_NEON
 if (!(getauxval(AT_HWCAP)&HWCAP_NEON)) {fputs("SKIP: OS does not report NEON; no NEON instruction executed\n",stderr);return 77;}
 test_row(ic_pixels_row_neon);
#else
 test_row(ic_pixels_row_scalar);
#endif
 test_frames();
 puts(P7_TEST_NEON?"NEON correctness run complete (not a playback test)":"SCALAR ROW PATH; integrated frame dispatch tested separately");
 return 0;
}
