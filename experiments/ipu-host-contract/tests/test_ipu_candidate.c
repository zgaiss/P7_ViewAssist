#include "ic_ipu_candidate.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
struct fake {
    unsigned char *mem[2], *borrowed;
    const unsigned char *published_ptr;
    unsigned allocs, frees, checks, queues, syncs, published;
    int fault;
    struct ic_ipu_plan p;
};
static int allocate(void *v,size_t n,struct ic_ipu_buffer *b) {
    struct fake *f=v; unsigned i=f->allocs++; assert(i<2);
    if (f->fault==8 && i==1) return -1;
    f->mem[i]=malloc(n); assert(f->mem[i]); memset(f->mem[i],0xcd,n);
    b->map=f->mem[i]; b->bytes=n; b->handle=i+1; return 0;
}
static void release(void *v,struct ic_ipu_buffer *b) {
    struct fake *f=v; assert(b->handle>=1 && b->handle<=2); assert(f->mem[b->handle-1]);
    free(f->mem[b->handle-1]); f->mem[b->handle-1]=NULL; f->frees++;
}
static int check(void *v,struct ic_ipu_task *t) {
    struct fake *f=v; f->checks++; if(f->fault==1)return -1;
    if(f->fault==2)t->plan.out_h-=8;
    if(f->fault==9)t->input=99;
    return 0;
}
static int sync_input(void *v,struct ic_ipu_buffer *b) {
    struct fake *f=v; f->syncs++; assert(b->handle==1); return f->fault==3?-1:0;
}
static enum ic_ipu_completion queue(void *v,const struct ic_ipu_task *t) {
    struct fake *f=v; size_t y=(size_t)f->p.width*f->p.height,c=y/4;
    f->queues++; assert(t->input==1 && t->output==2);
    /* Release/reuse the borrowed source now. The backend may see only its
     * independently owned packed staging snapshot, never borrowed pointers. */
    memset(f->borrowed,0xe7,((size_t)f->p.width+16)*f->p.height*3/2);
    for(size_t i=0;i<y;i++)assert(f->mem[0][i]==17);
    for(size_t i=0;i<c;i++){assert(f->mem[0][y+i]==31);assert(f->mem[0][y+c+i]==47);}
    memset(f->mem[1],0x5a,f->p.output_bytes); /* Synthetic pixels, not a color converter. */
    if(f->fault==4)return IC_IPU_FAILED_IDLE;
    if(f->fault==5)return IC_IPU_UNKNOWN;
    return IC_IPU_COMPLETE;
}
static int sync_output(void *v,struct ic_ipu_buffer *b) {
    struct fake *f=v; f->syncs++; assert(b->handle==2);return f->fault==6?-1:0;
}
static int publish(void *v,const unsigned char *rgb,size_t n) {
    struct fake *f=v; assert(n==IC_IPU_RGB_BYTES); f->published++; f->published_ptr=rgb;
    for(unsigned y=0;y<315;y++)for(unsigned x=0;x<560;x++){
        unsigned char want=(x>=f->p.dst_x && x<f->p.dst_x+f->p.out_w &&
            y>=f->p.dst_y && y<f->p.dst_y+f->p.out_h)?0x5a:0;
        assert(rgb[((size_t)y*560+x)*2]==want);assert(rgb[((size_t)y*560+x)*2+1]==want);
    }
    return f->fault==7?-1:0;
}
static void run_case(int fault,unsigned w,unsigned h) {
    struct fake f={0}; f.fault=fault; assert(!ic_ipu_plan_make(&f.p,w,h,0,0,w,h));
    struct ic_ipu_backend b={&f,allocate,release,check,sync_input,queue,sync_output};
    size_t ys=(size_t)(w+16)*h, cs=ys/4;
    f.borrowed=malloc(ys+cs*2); assert(f.borrowed);
    memset(f.borrowed,17,ys);memset(f.borrowed+ys,31,cs);memset(f.borrowed+ys+cs,47,cs);
    struct ic_yuv_frame frame={f.borrowed,f.borrowed+ys,f.borrowed+ys+cs,w,h,w+16,(w+16)/2};
    struct ic_ipu_candidate *s=ic_ipu_open_test(&f.p,&b,publish,&f);
    if(fault==8){assert(!s);assert(f.frees==1);free(f.borrowed);return;}
    assert(s);
    int rc=ic_ipu_frame_callback(&frame,s);
    if(fault)assert(rc);else assert(!rc);
    if(fault==5 || fault==6){
        assert(ic_ipu_quarantined(s));assert(ic_ipu_close(s));assert(f.frees==0);
        unsigned q=f.queues;assert(ic_ipu_frame_callback(&frame,s));assert(f.queues==q);
        assert(f.published==0);
        /* Deliberate retention is the tested contract. Child exits without
         * cleanup; no claim of target driver exit/reclamation follows. */
        _exit(0);
    }
    assert(!ic_ipu_quarantined(s));assert(!ic_ipu_close(s));assert(f.frees==2);
    if(fault && fault!=7)assert(f.published==0);
    else assert(f.published==1);
    if(fault==1 || fault==2 || fault==3 || fault==9)assert(!f.queues);
    free(f.borrowed);
}
static void alias_inputs(void) {
    /* Every source plane is rejected against every owned buffer before any
     * copy, check, sync or queue. Include the previously published packing
     * buffer to prevent recursive reuse of a borrowed published pointer. */
    for (unsigned buffer=0;buffer<3;buffer++) for(unsigned plane=0;plane<3;plane++) {
        struct fake f={0};
        assert(!ic_ipu_plan_make(&f.p,320,240,0,0,320,240));
        struct ic_ipu_backend b={&f,allocate,release,check,sync_input,queue,sync_output};
        size_t ys=336u*240u,cs=ys/4;
        f.borrowed=malloc(ys+2*cs);assert(f.borrowed);
        memset(f.borrowed,17,ys);memset(f.borrowed+ys,31,cs);
        memset(f.borrowed+ys+cs,47,cs);
        struct ic_yuv_frame frame={f.borrowed,f.borrowed+ys,f.borrowed+ys+cs,320,240,336,168};
        struct ic_ipu_candidate *state=ic_ipu_open_test(&f.p,&b,publish,&f);assert(state);
        if(buffer==2)assert(!ic_ipu_frame_callback(&frame,state));
        const unsigned char *alias=(buffer==2?f.published_ptr:f.mem[buffer])+1;
        if(plane==0)frame.y=alias;else if(plane==1)frame.u=alias;else frame.v=alias;
        unsigned checks=f.checks,queues=f.queues,syncs=f.syncs,published=f.published;
        errno=0;assert(ic_ipu_frame_callback(&frame,state));assert(errno==EINVAL);
        assert(f.checks==checks && f.queues==queues && f.syncs==syncs && f.published==published);
        assert(!ic_ipu_quarantined(state));assert(!ic_ipu_close(state));assert(f.frees==2);
        free(f.borrowed);
    }
}
static void invalid_inputs(void){
    struct ic_ipu_plan p;
    assert(ic_ipu_plan_make(&p,1280,720,0,0,1280,720)==0);
    assert(p.out_w==512 && p.out_h==288 && p.dst_x==24 && p.dst_y==13);
    assert(!ic_ipu_plan_make(&p,320,240,0,0,320,240));
    assert(p.out_w==416 && p.out_h==312 && p.dst_x==72 && p.dst_y==1);
    assert(ic_ipu_plan_make(&p,1280,720,0,0,560,315));
    assert(ic_ipu_plan_make(&p,1280,720,1,0,640,360));
    assert(ic_ipu_plan_make(&p,1280,720,0,0,1288,720));
    assert(ic_ipu_plan_make(&p,UINT32_MAX,720,0,0,1280,720));
    assert(ic_ipu_plan_make(&p,1280,720,1280,0,8,8));
    assert(ic_ipu_plan_make(&p,1280,720,0,0,1272,712)); /* no exact aligned fit */
    assert(!ic_ipu_plan_make(&p,1280,720,0,0,1280,720));
    errno=0;assert(!ic_ipu_open_target(&p,publish,NULL));assert(errno==ENOTSUP);
    assert(ic_ipu_frame_callback(NULL,NULL));assert(!ic_ipu_close(NULL));
}
int main(void){
    invalid_inputs();alias_inputs();run_case(0,320,240);run_case(0,1280,720);
    for(int f=1;f<=9;f++){
        if(f==5||f==6){pid_t p=fork();assert(p>=0);if(!p)run_case(f,320,240);
            int st;assert(waitpid(p,&st,0)==p);assert(WIFEXITED(st)&&WEXITSTATUS(st)==0);}
        else run_case(f,320,240);
    }
    puts("PASS: aligned geometry, default target refusal, owned snapshot, source alias rejection, complete black-bar packing, CHECK mutation/rejection, sync failures, idle failure, unknown quarantine/no retry, publisher failure, partial allocation cleanup");
    return 0;
}
