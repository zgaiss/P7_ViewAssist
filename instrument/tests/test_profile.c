/* Deterministic actual-source host seam. No device, SDK or real timing claim. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <time.h>
#include <sys/types.h>
static uint64_t tick;
static int clock_fail,clock_calls,convert_rc,write_rc,read_calls,write_calls;
static int test_clock(clockid_t id,struct timespec*t){(void)id;clock_calls++;if(clock_fail){errno=EIO;return -1;}t->tv_sec=(time_t)(tick/1000000000u);t->tv_nsec=(long)(tick%1000000000u);return 0;}
#define clock_gettime test_clock
#define ic_pixels_rgb565 test_convert
#define ic_write_exact test_write
#define ic_read_exact test_read
#define ic_display_open test_open
#define ic_display_frame test_display
#define ic_display_close test_close
#define setsid test_setsid
#define main receiver_main
#include "ic_receiver.c"
#undef main
#undef clock_gettime
pid_t test_setsid(void){return 1;}
int test_convert(const struct ic_yuv_frame*f,unsigned x,unsigned y,unsigned w,unsigned h,unsigned char*p,size_t n){(void)f;(void)x;(void)y;(void)w;(void)h;(void)p;(void)n;tick+=7;return convert_rc;}
int test_write(int fd,const void*p,size_t n,unsigned ms){(void)fd;(void)p;(void)n;assert(ms==350);write_calls++;tick+=11;return write_rc;}
int test_read(int fd,void*p,size_t n,unsigned ms){(void)fd;(void)p;(void)n;assert(ms==1000);tick+=13;return read_calls++?-1:0;}
ic_display*test_open(unsigned side,const char*p){(void)side;(void)p;tick+=100;return (ic_display*)(uintptr_t)1;}
int test_display(ic_display*d,const unsigned char*p,size_t n){(void)d;(void)p;(void)n;tick+=17;return 0;}
int test_close(ic_display*d){(void)d;return 0;}
static unsigned char*au(void){unsigned char*p=malloc(1);assert(p);return p;}
int main(void){
 struct output o={0};struct ic_yuv_frame f={0};
 assert(frame_callback(&f,&o)==0);assert(write_calls==1);
 convert_rc=-1;assert(frame_callback(&f,&o)==-1);assert(write_calls==1);
 convert_rc=0;write_rc=-1;assert(frame_callback(&f,&o)==-1);assert(write_calls==2);
 stopping=1;assert(frame_callback(&f,&o)==-1);assert(write_calls==2);stopping=0;
 assert(o.convert.count==(IC_PROFILE?3u:0u));assert(o.convert.total_ns==(IC_PROFILE?21u:0u));
 assert(o.convert.failed==(IC_PROFILE?1u:0u));assert(o.pipe_write.count==(IC_PROFILE?2u:0u));
 assert(o.pipe_write.total_ns==(IC_PROFILE?22u:0u));assert(o.pipe_write.failed==(IC_PROFILE?1u:0u));
 assert(clock_calls==(IC_PROFILE?10:0));
 struct ic_profile_stat s={0};ic_profile_record(&s,20,19,0);ic_profile_record(&s,UINT64_MAX,30,1);
 assert(s.invalid==(IC_PROFILE?2u:0u));assert(s.failed==(IC_PROFILE?1u:0u));
 s.total_ns=UINT64_MAX-2;ic_profile_record(&s,0,7,0);assert(s.total_ns==(IC_PROFILE?UINT64_MAX:UINT64_MAX-2));
 errno=E2BIG;(void)ic_profile_now();assert(errno==E2BIG);
 clock_fail=1;errno=E2BIG;assert(ic_profile_now()==(IC_PROFILE?UINT64_MAX:0));assert(errno==E2BIG);clock_fail=0;
 struct auq q;auq_init(&q);
 for(int i=0;i<AQ_CAP;i++)auq_push(&q,au(),1,0);
 auq_push(&q,au(),1,0);assert(q.count==0&&q.need_idr==1&&q.dropped==1);
 auq_push(&q,au(),1,0);auq_push(&q,au(),1,1);
 assert(q.count==1&&q.need_idr==0);assert(q.dropped_aus==(IC_PROFILE?14u:0u));
 for(int i=1;i<AQ_CAP;i++)auq_push(&q,au(),1,0);
 auq_push(&q,au(),1,1);assert(q.count==1&&q.need_idr==0&&q.dropped==2);
 assert(q.dropped_aus==(IC_PROFILE?26u:0u));assert(q.high_water==(IC_PROFILE?AQ_CAP:0));
 auq_clear(&q);pthread_cond_destroy(&q.c);pthread_mutex_destroy(&q.m);
 /* Real guardian control flow, fake read/display/clock. No real fd closed. */
 assert(guardian(-1,1,"host-fixture")==0);assert(read_calls==2);
 puts("PASS deterministic callback stages, stop/failure paths, queue accounting, clock and guardian stages");
 return 0;
}
