#ifndef IC_PROFILE_H
#define IC_PROFILE_H
/* Opt-in aggregate diagnostics only. No allocation, locks or per-frame I/O.
 * Enable with -DIC_PROFILE=1. Never a capture-age or target-FPS measurement. */
#ifndef IC_PROFILE
#define IC_PROFILE 0
#endif
#if IC_PROFILE != 0 && IC_PROFILE != 1
#error IC_PROFILE must be 0 or 1
#endif
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <time.h>
#include <errno.h>
struct ic_profile_stat {uint64_t count, total_ns, max_ns, failed, invalid;};
static inline uint64_t ic_profile_add(uint64_t a,uint64_t b){return b>UINT64_MAX-a?UINT64_MAX:a+b;}
static inline uint64_t ic_profile_now(void){
#if IC_PROFILE
    struct timespec t;int saved=errno;
    int rc=clock_gettime(CLOCK_MONOTONIC,&t);errno=saved;
    if(rc||t.tv_sec<0||(uint64_t)t.tv_sec> (UINT64_MAX-999999999u)/1000000000u)return UINT64_MAX;
    return (uint64_t)t.tv_sec*1000000000u+(uint64_t)t.tv_nsec;
#else
    return 0;
#endif
}
static inline void ic_profile_record(struct ic_profile_stat*s,uint64_t a,uint64_t b,int rc){
    if(!IC_PROFILE)return;
    s->count=ic_profile_add(s->count,1);
    if(rc)s->failed=ic_profile_add(s->failed,1);
    if(a==UINT64_MAX||b==UINT64_MAX||b<a){s->invalid=ic_profile_add(s->invalid,1);return;}
    uint64_t d=b-a;s->total_ns=ic_profile_add(s->total_ns,d);if(d>s->max_ns)s->max_ns=d;
}
static inline void ic_profile_report(const char*stage,const struct ic_profile_stat*s){
    if(IC_PROFILE)fprintf(stderr,"IC_PROFILE stage=%s attempts=%"PRIu64" failed=%"PRIu64" invalid_clock=%"PRIu64" total_ns=%"PRIu64" max_ns=%"PRIu64"\n",stage,s->count,s->failed,s->invalid,s->total_ns,s->max_ns);
}
#endif
