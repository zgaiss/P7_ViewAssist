#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <errno.h>
#include <assert.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <linux/fb.h>
static int s_open(const char*,int,...), s_close(int), s_ioctl(int,unsigned long,...), s_munmap(void*,size_t);
static ssize_t s_read(int,void*,size_t),s_write(int,const void*,size_t);
static void *s_mmap(void*,size_t,int,int,int,off_t);
#define open s_open
#define close s_close
#define ioctl s_ioctl
#define mmap s_mmap
#define munmap s_munmap
#define read s_read
#define write s_write
#ifndef DISPLAY_SOURCE
#define DISPLAY_SOURCE "production_host.c"
#endif
#define ic_display_open host_production_display_open
#include DISPLAY_SOURCE
#undef ic_display_open
#undef open
#undef close
#undef ioctl
#undef mmap
#undef munmap
#undef read
#undef write
#include "bridge_state.inc"
static int after_error;
static int finish(int r){if(after_error){after_error=0;errno=EIO;return -1;}return r;}
static int mutate(void){if(bridge_admit())return -1;mutations++;if(mutations==failat){failed=1;errno=EIO;if(getenv("FG_FAIL_AFTER_EFFECT")){after_error=1;return 0;}return -1;}return 0;}
static int s_open(const char*p,int f,...){if(!strcmp(p,"/dev/fb1")){const char*d=getenv("FG_OPEN_DELAY_MS");if(d)usleep(atoi(d)*1000);return 1001;}if(!strcmp(p,"/dev/fb0"))return 1002;if(!strcmp(p,"/dev/mem"))return 1003;if(strstr(p,"/sys/class/graphics/fb1/")){if(strstr(p,"virtual_size"))return 1006;if(strstr(p,"modes"))return 1004;return 1005;}if(!strcmp(p,"/tmp/p7-viewassist"))p=getenv("P7_STATE_ROOT");mode_t m=0;if(f&O_CREAT){va_list a;va_start(a,f);m=va_arg(a,int);va_end(a);}return open(p,f,m);}
static int s_close(int f){return f>=1000?0:close(f);}
static ssize_t s_read(int f,void*p,size_t n){if(f==1004){const char*s=modewide?MODE_OLD MODE_NEW:MODE_OLD;size_t z=strlen(s);assert(n>=z);memcpy(p,s,z);return z;}return read(f,p,n);}
static ssize_t s_write(int f,const void*p,size_t n){if(f<1000)return write(f,p,n);if(mutate())return -1;if(mapped)unsafe_transitions++;if(f==1004){modewide=n==112;return finish(n);}if(f==1005){int w=!memcmp(p,MODE_NEW,n);fv.xres=w?560:240;fv.yres=w?315:320;ff.line_length=w?1120:480;return finish(n);}if(f==1006){int w=((char*)p)[0]=='5';fv.xres_virtual=w?560:240;fv.yres_virtual=w?945:960;ff.smem_len=ff.line_length*fv.yres_virtual;return finish(n);}abort();}
static int s_ioctl(int f,unsigned long op,...){va_list a;va_start(a,op);void*p=va_arg(a,void*);va_end(a);if(op==FBIOGET_VSCREENINFO){memcpy(p,f==1001?&fv:&bv,sizeof(fv));return 0;}if(op==FBIOGET_FSCREENINFO){memcpy(p,f==1001?&ff:&bf,sizeof(ff));return 0;}if(op==GET_BLANK){*(unsigned*)p=f==1001?fbblank:0;return 0;}if(mutate())return -1;if(op==FBIOBLANK){assert(f==1001);int v=(int)(uintptr_t)p;if(v==4){if(fbblank==0)transient_origin++;regbank[2]=0;regbank[0]&=~1u;}else{regbank[0]|=1;enables++;}fbblank=v;return finish(0);}if(op==SET_POS){struct position*q=p;regbank[2]=((uint32_t)q->x<<16)|q->y;return finish(0);}if(op==DP_ALPHA){int32_t*q=p;regbank[0]=(regbank[0]&~6u)|(f==1001?6:4);regbank[1]=(regbank[1]&0xffffff)|((uint32_t)q[1]<<24);return finish(0);}abort();}
static void*s_mmap(void*a,size_t n,int prot,int flags,int f,off_t o){(void)a;(void)prot;(void)flags;(void)o;if(f==1003)return regbank;if(f==1001){assert(n<=sizeof(pixels));mapped++;return pixels;}abort();}
static int s_munmap(void*p,size_t n){(void)n;if(p==pixels){assert(mapped>0);mapped--;}else assert(p==regbank);return 0;}
static void setup_baseline(void){fv=(struct fb_var_screeninfo){.xres=240,.yres=320,.xres_virtual=240,.yres_virtual=960,.bits_per_pixel=16,.red={11,5,0},.green={5,6,0},.blue={0,5,0}};ff=(struct fb_fix_screeninfo){.id="DISP3 FG",.smem_len=460800,.line_length=480};bv=(struct fb_var_screeninfo){.xres=1920,.yres=720,.bits_per_pixel=32};bf=(struct fb_fix_screeninfo){.id="DISP3 BG - DI1"};regbank[0]=4;regbank[1]=0x80000000;for(size_t i=0;i<sizeof(original);i++)original[i]=pixels[i]=(unsigned char)(i*37);}

#include "bridge_entry.inc"
