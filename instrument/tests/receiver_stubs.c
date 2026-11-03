/* TEST ONLY. Not linked into ARMEL builds. No device/SDK calls. */
#define _POSIX_C_SOURCE 200809L
#include "ic_decoder.h"
#include "ic_display.h"
#include "ic_pixels.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
struct ic_decoder{int unused;};struct ic_display{int unused;};
static struct ic_decoder decoder;static struct ic_display display;
static void event(const char *text){FILE*f=fopen(getenv("IC_TEST_LOG"),"a");if(!f)abort();fprintf(f,"%s\n",text);fclose(f);}
ic_decoder *ic_decoder_open(const unsigned char *au,size_t n,ic_frame_callback cb,void*ctx){
 if(!n||au[0]!='a')return NULL;
 struct ic_yuv_frame f={0};if(cb(&f,ctx))return NULL;return &decoder;
}
int ic_decoder_feed(ic_decoder*d,const unsigned char*au,size_t n){(void)d;
 if(n&&au[0]=='h'){event("busy");struct timespec delay={2,500000000};while(nanosleep(&delay,&delay)){}event("idle");}return 0;
}
int ic_decoder_push(ic_decoder*d,const unsigned char*au,size_t n){return ic_decoder_feed(d,au,n);}
int ic_decoder_decode(ic_decoder*d){(void)d;return 0;}
int ic_decoder_close(ic_decoder*d){(void)d;return 0;}
int ic_pixels_rgb565(const struct ic_yuv_frame*f,unsigned x,unsigned y,unsigned w,unsigned h,unsigned char*out,size_t n){(void)f;(void)x;(void)y;(void)w;(void)h;memset(out,0,n);return 0;}
ic_display *ic_display_open(unsigned side,const char*prefix){(void)side;(void)prefix;event("open");return &display;}
int ic_display_frame(ic_display*d,const unsigned char*b,size_t n){(void)d;(void)b;(void)n;event("frame");return 0;}
int ic_display_hide(ic_display*d){(void)d;return 0;}
int ic_display_close(ic_display*d){(void)d;event("restore");return 0;}
