/* Reuse only the host fake backend; never the physical display implementation. */
#define main contract_main
#include "test_ipu_candidate.c"
#undef main
#include "ic_protocol.h"
#include <fcntl.h>
static int pipe_publish(void *v,const unsigned char *rgb,size_t n) {
    int p[2]; assert(!pipe(p));
    assert(fcntl(p[0],F_SETFL,O_NONBLOCK)==0); assert(fcntl(p[1],F_SETFL,O_NONBLOCK)==0);
    pid_t child=fork(); assert(child>=0);
    if(!child){
        close(p[1]); unsigned char *copy=malloc(IC_IPU_RGB_BYTES); assert(copy);
        assert(!ic_read_exact(p[0],copy,IC_IPU_RGB_BYTES,1000));
        assert(!publish(v,copy,IC_IPU_RGB_BYTES));
        assert(!memcmp(copy,rgb,n));free(copy);close(p[0]);_exit(0);
    }
    close(p[0]); assert(!ic_write_exact(p[1],rgb,n,350));close(p[1]);
    int st;assert(waitpid(child,&st,0)==child);assert(WIFEXITED(st)&&WEXITSTATUS(st)==0);
    ((struct fake *)v)->published++;return 0;
}
int main(void){
    struct fake f={0};assert(!ic_ipu_plan_make(&f.p,320,240,0,0,320,240));
    struct ic_ipu_backend b={&f,allocate,release,check,sync_input,queue,sync_output};
    unsigned stride=336;size_t ys=(size_t)stride*240,cs=ys/4;
    f.borrowed=malloc(ys+cs*2);assert(f.borrowed);
    memset(f.borrowed,17,ys);memset(f.borrowed+ys,31,cs);memset(f.borrowed+ys+cs,47,cs);
    struct ic_yuv_frame frame={f.borrowed,f.borrowed+ys,f.borrowed+ys+cs,320,240,stride,stride/2};
    struct ic_ipu_candidate *s=ic_ipu_open_test(&f.p,&b,pipe_publish,&f);assert(s);
    assert(!ic_ipu_frame_callback(&frame,s));assert(f.published==1);assert(!ic_ipu_close(s));
    free(f.borrowed);puts("PASS: candidate synthetic RGB travels through exact production complete-frame pipe helpers (350ms write/1000ms read), independent child verifies bytes. No display/device called.");return 0;
}
