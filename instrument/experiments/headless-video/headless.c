/* HOST fixture-replay diagnostic. Not a H.264 decoder, VPU backend or receiver. */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L
#if defined(P7_ENABLE_REAL_VPU) && P7_ENABLE_REAL_VPU
#error "Real VPU backend disabled: abnormal-exit DMA safety and execution approval unresolved"
#endif
#include "ic_decoder.h"
#include "ic_preflight.h"
#include "ic_pixels.h"
#include "pixels_simd/ic_pixels_simd.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#define MAX_FRAMES 120u
#define MAGIC "P7AU0001"
struct stats { uint64_t preflight, submit, replay, convert, parity, checksum, total; };
struct app {
    FILE *raw;
    unsigned width, height, accepted, displayed, released, pending, delay;
    unsigned callback_reject, submit_fail, decode_fail, close_fail;
    unsigned parity_enabled, parity_checked;
#if defined(P7_HOST_TESTING) && P7_HOST_TESTING
    int test_signal;
#endif
    uint64_t expected[MAX_FRAMES], hashes[MAX_FRAMES], conversion_ns[MAX_FRAMES];
    unsigned char *planes, *rgb, *reference;
    size_t plane_bytes;
    struct stats times;
};
static volatile sig_atomic_t stopped;
static void interrupt_handler(int sig) { (void)sig; stopped = 1; }
static uint64_t ns(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) { perror("clock"); exit(70); }
    return (uint64_t)t.tv_sec * UINT64_C(1000000000) + (uint64_t)t.tv_nsec;
}
static uint32_t le32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static uint64_t le64(const unsigned char *p) { return le32(p) | (uint64_t)le32(p+4)<<32; }
static uint64_t fnv(const unsigned char *p, size_t n) {
    uint64_t v=UINT64_C(14695981039346656037);
    for (size_t i=0;i<n;++i) { v^=p[i]; v*=UINT64_C(1099511628211); } return v;
}
static int exact(FILE *f, void *p, size_t n) { return fread(p,1,n,f)==n ? 0 : -1; }
static FILE *input(const char *path, size_t cap) {
    struct stat st;
    /* O_PATH does not invoke device-driver open. Pin an inode, reject anything
     * but a regular file, then reopen that exact pinned object read-only. */
    int pin=open(path,O_PATH|O_NOFOLLOW|O_CLOEXEC);
    if (pin<0) return NULL;
    if (fstat(pin,&st) || !S_ISREG(st.st_mode) || st.st_size<0 ||
        (uintmax_t)st.st_size>cap) { close(pin); return NULL; }
    char proc[64];
    int n=snprintf(proc,sizeof proc,"/proc/self/fd/%d",pin);
    if (n<0 || (size_t)n>=sizeof proc) { close(pin); return NULL; }
    int fd=open(proc,O_RDONLY|O_CLOEXEC); close(pin);
    if (fd<0) return NULL;
    FILE *f=fdopen(fd,"rb");
    if (!f) close(fd);
    return f;
}
static int compare_u64(const void *a,const void *b) {
    uint64_t x=*(const uint64_t *)a,y=*(const uint64_t *)b;
    return (x>y)-(x<y);
}
static uint64_t percentile(const uint64_t *v,unsigned n,unsigned pct) {
    uint64_t copy[MAX_FRAMES];
    if (!n) return 0;
    memcpy(copy,v,n*sizeof *v); qsort(copy,n,sizeof *v,compare_u64);
    return copy[(n*pct+99)/100-1]; /* nearest-rank, documented small sample */
}
/* A separate scalar-gather oracle exercises the row helper used by SIMD tails.
 * It is NOT NEON execution or evidence of the ARM dispatcher. */
static void gather_oracle(const struct ic_yuv_frame *f, unsigned char *out) {
    unsigned w,h,ox,oy;
    unsigned char y[560],u[560],v[560];
    if ((uint64_t)f->width*315 >= (uint64_t)f->height*560) { w=560; h=f->height*w/f->width; }
    else { h=315; w=f->width*h/f->height; }
    if (!w) w=1;
    if (!h) h=1;
    ox=(560-w)/2; oy=(315-h)/2;
    memset(out,0,IC_PIXELS_RGB565_BYTES);
    for(unsigned dy=0;dy<h;++dy) {
        unsigned sy=dy*f->height/h;
        for(unsigned dx=0;dx<w;++dx) {
            unsigned sx=dx*f->width/w;
            y[dx]=f->y[(size_t)sy*f->y_stride+sx];
            u[dx]=f->u[(size_t)(sy/2)*f->c_stride+sx/2];
            v[dx]=f->v[(size_t)(sy/2)*f->c_stride+sx/2];
        }
        ic_pixels_row_scalar(y,u,v,out+((size_t)(dy+oy)*560+ox)*2,w);
    }
}
static int consume(const struct ic_yuv_frame *f, struct app *a) {
    uint64_t t=ns(), h;
    int rc=ic_pixels_rgb565(f,0,0,f->width,f->height,a->rgb,IC_PIXELS_RGB565_BYTES);
    uint64_t elapsed=ns()-t;
    a->conversion_ns[a->displayed]=elapsed; a->times.convert+=elapsed;
    if (rc) return -1;
    if (a->parity_enabled) {
        t=ns(); gather_oracle(f,a->reference);
        rc=memcmp(a->rgb,a->reference,IC_PIXELS_RGB565_BYTES);
        a->times.parity+=ns()-t;
        if (rc) { fputs("scalar gather parity failed\n",stderr); return -1; }
        ++a->parity_checked;
    }
    t=ns(); h=fnv(a->rgb,IC_PIXELS_RGB565_BYTES); a->times.checksum+=ns()-t;
    if (h!=a->expected[a->displayed]) { fputs("fixture RGB checksum mismatch\n",stderr); return -1; }
    a->hashes[a->displayed]=h;
    ++a->displayed;
    return a->callback_reject==a->displayed ? -1 : 0;
}
static int fake_submit(struct app *a) {
    if (a->submit_fail==a->accepted+1 || a->pending>=2) return -1;
    ++a->accepted; ++a->pending; return 0;
}
static int fake_decode(struct app *a) {
    if (a->decode_fail==a->accepted) return -1;
    if (a->pending<=a->delay) return 0;
    uint64_t t=ns();
    if (exact(a->raw,a->planes,a->plane_bytes)) return -1;
    struct ic_yuv_frame f={a->planes,a->planes+(size_t)a->width*a->height,
        a->planes+(size_t)a->width*a->height*5/4,a->width,a->height,a->width,a->width/2};
    a->times.replay+=ns()-t;
    int rc=consume(&f,a); /* Borrowed planes must expire synchronously here. */
    memset(a->planes,0xa5,a->plane_bytes); ++a->released; --a->pending;
    /* Prove owned RGB remains valid after the fake releases/poisons its planes. */
    if (a->displayed && fnv(a->rgb,IC_PIXELS_RGB565_BYTES)!=a->hashes[a->displayed-1]) return -1;
    return rc;
}
static unsigned number(const char *s) {
    char *e; errno=0; unsigned long n=strtoul(s,&e,10);
    if (errno || !*s || *e || n>MAX_FRAMES) return MAX_FRAMES+1;
    return (unsigned)n;
}
int main(int argc,char **argv) {
    const char *aus=NULL,*raw=NULL;
    struct app a={0}; struct ic_preflight gate={0};
    unsigned char header[20],record[12],*au=NULL;
    unsigned declared=0,read_aus=0; int rc=1,closed=0; FILE *in=NULL;
    const char *status="argument_error";
    a.parity_enabled=1;
    for(int i=1;i<argc;++i) {
        const char *p=argv[i];
        if (!strcmp(p,"--backend=vpu")) { fputs("BLOCKED: no VPU backend is built; DMA exit safety and hardware authorization unresolved\n",stderr); return 78; }
        else if (!strcmp(p,"--backend=fixture")) { /* Only supported backend. */ }
        else if (!strcmp(p,"--aus") && i+1<argc) aus=argv[++i];
        else if (!strcmp(p,"--yuv") && i+1<argc) raw=argv[++i];
        else if (!strcmp(p,"--delay-one")) a.delay=1;
        else if (!strcmp(p,"--no-parity")) a.parity_enabled=0;
#if defined(P7_HOST_TESTING) && P7_HOST_TESTING
        else if (!strcmp(p,"--test-sigint")) a.test_signal=SIGINT;
        else if (!strcmp(p,"--test-sigterm")) a.test_signal=SIGTERM;
#endif
        else if (!strcmp(p,"--fail-submit") && i+1<argc) a.submit_fail=number(argv[++i]);
        else if (!strcmp(p,"--fail-decode") && i+1<argc) a.decode_fail=number(argv[++i]);
        else if (!strcmp(p,"--reject-callback") && i+1<argc) a.callback_reject=number(argv[++i]);
        else if (!strcmp(p,"--fail-close")) a.close_fail=1;
        else { fputs("Unknown/incomplete argument\n",stderr); return 64; }
    }
    if (!aus || !raw || a.submit_fail>MAX_FRAMES || a.decode_fail>MAX_FRAMES || a.callback_reject>MAX_FRAMES) return 64;
    signal(SIGINT,interrupt_handler); signal(SIGTERM,interrupt_handler);
    uint64_t began=ns();
    status="fixture_header_error";
    in=input(aus,(size_t)MAX_FRAMES*(IC_AU_MAX+12)+20);
    if (!in || exact(in,header,sizeof header) || memcmp(header,MAGIC,8)) goto done;
    a.width=le32(header+8); a.height=le32(header+12); declared=le32(header+16);
    if (!declared || declared>MAX_FRAMES || a.width<64 || a.width>1280 || a.height<64 || a.height>720 || (a.width&1) || (a.height&1)) goto done;
    a.plane_bytes=(size_t)a.width*a.height*3/2;
    a.raw=input(raw,a.plane_bytes*declared);
    if (!a.raw) goto done;
    struct stat raw_stat;
    if (fstat(fileno(a.raw),&raw_stat) || (uintmax_t)raw_stat.st_size!=a.plane_bytes*declared) goto done;
    a.planes=malloc(a.plane_bytes); a.rgb=malloc(IC_PIXELS_RGB565_BYTES); a.reference=malloc(IC_PIXELS_RGB565_BYTES); au=malloc(IC_AU_MAX);
    if (!a.planes || !a.rgb || !a.reference || !au) { status="allocation_error"; goto done; }
    for (unsigned i=0;i<declared;++i) {
        if (stopped) { status="interrupted_between_calls"; goto done; }
        status="fixture_record_error";
        if (exact(in,record,sizeof record)) goto done;
        uint32_t len=le32(record);
        if (!len || len>IC_AU_MAX || exact(in,au,len)) goto done;
        a.expected[i]=le64(record+4); ++read_aus;
        uint64_t t=ns(); int ok=ic_preflight_au(&gate,au,len);
        a.times.preflight+=ns()-t;
        if (ok || gate.visible_width!=a.width || gate.visible_height!=a.height) { status="preflight_rejected"; goto done; }
        t=ns(); ok=fake_submit(&a); a.times.submit+=ns()-t;
        if (ok) { status="fake_submit_error"; goto done; }
        if (fake_decode(&a)) { status="fake_decode_or_callback_error"; goto done; }
#if defined(P7_HOST_TESTING) && P7_HOST_TESTING
        if (a.test_signal && a.accepted==3) raise(a.test_signal);
#endif
    }
    if (fgetc(in)!=EOF || ferror(in)) { status="trailing_fixture_data"; goto done; }
    status="ok"; rc=0;
done:
    /* Only ordinary host malloc storage exists. This is NOT a VPU exit policy. */
    if (a.planes) { closed=1; if (a.close_fail) { status="fake_close_error"; rc=1; } }
    a.times.total=ns()-began;
    if (in) fclose(in);
    if (a.raw) fclose(a.raw);
    free(au); free(a.planes); free(a.rgb); free(a.reference);
    printf("{\"scope\":\"HOST_FIXTURE_REPLAY_NOT_VPU\",\"status\":\"%s\",\"declared_aus\":%u,\"read_aus\":%u,\"accepted_aus\":%u,\"converted_frames\":%u,\"released_frames\":%u,\"undrained_aus\":%u,\"close_attempts\":%d,\"scalar_gather_parity_enabled\":%s,\"parity_checked_frames\":%u,\"stage_ns\":{\"preflight\":%"PRIu64",\"fake_submit\":%"PRIu64",\"fixture_yuv_read\":%"PRIu64",\"conversion\":%"PRIu64",\"parity_oracle\":%"PRIu64",\"checksum\":%"PRIu64",\"total\":%"PRIu64"},\"hashes\":[",
      status,declared,read_aus,a.accepted,a.displayed,a.released,a.pending,closed,a.parity_enabled?"true":"false",a.parity_checked,a.times.preflight,a.times.submit,a.times.replay,a.times.convert,a.times.parity,a.times.checksum,a.times.total);
    for(unsigned i=0;i<a.displayed;++i) printf("%s\"%016"PRIx64"\"",i?",":"",a.hashes[i]);
    printf("],\"conversion_ns\":{\"samples\":%u,\"p50\":%"PRIu64",\"p95\":%"PRIu64",\"p99\":%"PRIu64",\"max\":%"PRIu64"}}\n",a.displayed,percentile(a.conversion_ns,a.displayed,50),percentile(a.conversion_ns,a.displayed,95),percentile(a.conversion_ns,a.displayed,99),percentile(a.conversion_ns,a.displayed,100));
    return rc;
}
