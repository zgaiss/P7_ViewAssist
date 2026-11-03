/* Parked experimental receiver. No deployment/RPC/autostart code.
 * Network/VPU worker owns NO display fd; independent-session guardian owns FG.
 * Guardian restores after complete-frame lease expiry or parent/pipe death.
 * This does not make a stuck ioctl, guardian death or BSP disable quirk safe. */
#define _GNU_SOURCE
#include "ic_protocol.h"
#include "ic_decoder.h"
#include "ic_display.h"
#include "ic_pixels.h"
#include "ic_backup_budget.h"
#include "ic_dma_budget.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdatomic.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#ifndef IC_STATE_ROOT
#define IC_STATE_ROOT "/tmp/p7-viewassist"
#endif
#ifndef IC_VPU_ABI_CONFIRMED
#define IC_VPU_ABI_CONFIRMED 0
#endif
/* Signal handler and pthread workers share this flag. volatile sig_atomic_t
 * only supplies signal-access guarantees, not inter-thread synchronization.
 * Refuse targets where C11 int atomics may require a non-signal-safe lock. */
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "stopping requires always-lock-free atomic int");
static atomic_int stopping = ATOMIC_VAR_INIT(0);
static void on_signal(int n){(void)n;stopping=1;}
static int has_idr(const unsigned char *p,size_t n){
    size_t i;
    for(i=0;i+3<n;i++){
        if(p[i]==0&&p[i+1]==0&&p[i+2]==1){
            if((p[i+3]&31)==5)return 1;
            i+=2;
        }else if(i+3<n&&p[i]==0&&p[i+1]==0&&p[i+2]==0&&p[i+3]==1){
            if(i+4<n&&(p[i+4]&31)==5)return 1;
            i+=3;
        }
    }
    return 0;
}
static int nonblock(int fd){int f=fcntl(fd,F_GETFL);return f<0||fcntl(fd,F_SETFL,f|O_NONBLOCK)<0?-1:0;}
static int token_file(const char *path,unsigned char key[32]){
    int fd=open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);struct stat st;char text[66];
    if(fd<0)return -1;
    if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_uid!=geteuid()||(st.st_mode&077)||st.st_size<64||st.st_size>65){close(fd);return -1;}
    ssize_t n=read(fd,text,sizeof(text));close(fd);
    if(n!=64&&(n!=65||text[64]!='\n'))return -1;
    for(unsigned i=0;i<32;i++){unsigned v=0;for(unsigned j=0;j<2;j++){char c=text[2*i+j];unsigned x=c>='0'&&c<='9'?(unsigned)(c-'0'):c>='a'&&c<='f'?(unsigned)(c-'a'+10):c>='A'&&c<='F'?(unsigned)(c-'A'+10):99;if(x>15)return -1;v=(v<<4)|x;}key[i]=(unsigned char)v;}
    return 0;
}
static int private_root(void){
    const char *p=IC_STATE_ROOT;if(mkdir(p,0700)&&errno!=EEXIST)return -1;
    struct stat s;return lstat(p,&s)||!S_ISDIR(s.st_mode)||s.st_uid!=geteuid()||(s.st_mode&077)?-1:0;
}
static int guardian(int fd,unsigned side,const char *prefix,pid_t owner){
    /* Preserve inherited or pre-entry stop: retirement must never be reset. */
    if(getppid()!=owner||setsid()<0)return 1;
    unsigned char *rgb=malloc(IC_RGB_BYTES);if(!rgb)return 1;
    ic_display *display=NULL;int result=0,n=0;long long rd_sum=0,ds_sum=0;
    /* Every read is one COMPLETE CPU-owned RGB frame. No VPU DMA reaches scanout. */
    while(!stopping){
        long long a=ic_now_ms();
        if(ic_read_exact(fd,rgb,IC_RGB_BYTES,1000))break;
        long long b=ic_now_ms();
        if(!display){display=ic_display_open(side,prefix);if(!display){result=1;break;}}
        long long c=ic_now_ms();
        /* Host-tested guard: a slow open must not publish the held old RGB.
         * This bounds only read-completion to publish-entry, not capture age.
         * It cannot interrupt blocked open/close/frame ioctls or repair death. */
        if(stopping||getppid()!=owner||c-b>=350){fprintf(stderr,"GUARD stale or retired after display open age_ms=%lld owner_alive=%d stopping=%d; close without publish\n",c-b,getppid()==owner,(int)stopping);result=1;break;}
        if(ic_display_frame(display,rgb,IC_RGB_BYTES)){result=1;break;}
        long long d=ic_now_ms();rd_sum+=b-a;ds_sum+=d-c;++n;
        if(n%30==0)fprintf(stderr,"IC_GUARD n=%d read_avg=%lld display_avg=%lld\n",n,rd_sum/n,ds_sum/n);
    }
    if(display&&ic_display_close(display))result=1;
    free(rgb);close(fd);fprintf(stderr,"guardian restored result=%d (allocation/activate may differ; physical transient unverified)\n",result);return result;
}
/* Host-evaluated candidate, not deployed. Owns CPU I420 copies before callback
 * return. Exactly one pending frame plus one in flight; no compressed-AU drops.
 * Output remains complete RGB565 pipe frames and existing350ms write deadline.
 * This addresses backpressure, NOT capture freshness, scanout/DMA safety or BSP.
 */
struct owned_frame {struct ic_yuv_frame frame;unsigned char *storage;};
struct output {int fd;struct ic_session session;unsigned char *rgb;
 pthread_mutex_t mutex;pthread_cond_t changed;pthread_t worker;int started,stop,abort,failed;
 struct owned_frame *pending;unsigned copied,replaced,written;};
static void owned_free(struct owned_frame*f){if(f){free(f->storage);free(f);}}
static struct owned_frame*owned_copy(const struct ic_yuv_frame*f){
 if(!f||!f->y||!f->u||!f->v||!f->width||!f->height||f->width>1280||f->height>720||f->y_stride<f->width||f->c_stride<(f->width+1)/2)return NULL;
 unsigned cw=(f->width+1)/2,ch=(f->height+1)/2;
 /* Preserve target ARM32 representability even in the64-bit host harness. */
 if(f->y_stride>UINT32_MAX/f->height||f->c_stride>UINT32_MAX/ch||f->y_stride>SIZE_MAX/f->height||f->c_stride>SIZE_MAX/ch)return NULL;
 size_t src_y=(size_t)f->y_stride*f->height,src_c=(size_t)f->c_stride*ch;
 if((uintptr_t)f->y>UINTPTR_MAX-src_y||(uintptr_t)f->u>UINTPTR_MAX-src_c||(uintptr_t)f->v>UINTPTR_MAX-src_c)return NULL;
 size_t yn=(size_t)f->width*f->height,cn=(size_t)cw*ch;
 struct owned_frame*o=calloc(1,sizeof(*o));if(!o)return NULL;o->storage=malloc(yn+2*cn);if(!o->storage){free(o);return NULL;}
 for(unsigned y=0;y<f->height;y++)memcpy(o->storage+(size_t)y*f->width,f->y+(size_t)y*f->y_stride,f->width);
 for(unsigned y=0;y<ch;y++){memcpy(o->storage+yn+(size_t)y*cw,f->u+(size_t)y*f->c_stride,cw);memcpy(o->storage+yn+cn+(size_t)y*cw,f->v+(size_t)y*f->c_stride,cw);}
 o->frame=(struct ic_yuv_frame){o->storage,o->storage+yn,o->storage+yn+cn,f->width,f->height,f->width,cw};return o;
}
static void*output_worker(void*opaque){struct output*o=opaque;for(;;){
 pthread_mutex_lock(&o->mutex);while(!o->pending&&!o->stop)pthread_cond_wait(&o->changed,&o->mutex);
 if(o->stop&&(!o->pending||o->abort)){owned_free(o->pending);o->pending=NULL;pthread_mutex_unlock(&o->mutex);break;}
 struct owned_frame*f=o->pending;o->pending=NULL;pthread_mutex_unlock(&o->mutex);
 struct ic_session*s=&o->session;int rc=ic_pixels_rgb565(&f->frame,s->crop_x,s->crop_y,s->crop_w,s->crop_h,o->rgb,IC_RGB_BYTES);
 if(!rc)rc=ic_write_exact(o->fd,o->rgb,IC_RGB_BYTES,350);
 owned_free(f);pthread_mutex_lock(&o->mutex);
 if(rc){o->failed=1;o->stop=1;owned_free(o->pending);o->pending=NULL;}else o->written++;
 pthread_mutex_unlock(&o->mutex);if(rc)break;
 }return NULL;}
static int output_start(struct output*o){int r=pthread_mutex_init(&o->mutex,NULL);if(r)return -1;r=pthread_cond_init(&o->changed,NULL);if(r){pthread_mutex_destroy(&o->mutex);return -1;}
 if(pthread_create(&o->worker,NULL,output_worker,o)){pthread_cond_destroy(&o->changed);pthread_mutex_destroy(&o->mutex);return -1;}
 o->started=1;return 0;}
static int output_stop(struct output*o,int abort){if(!o->started)return 0;pthread_mutex_lock(&o->mutex);o->stop=1;o->abort=abort;pthread_cond_broadcast(&o->changed);pthread_mutex_unlock(&o->mutex);
 if(pthread_join(o->worker,NULL)){fprintf(stderr,"FATAL writer join unconfirmed; host fail-stop before dependent teardown\n");_exit(74);}
 fprintf(stderr,"IC_MAILBOX copied=%u replaced=%u written=%u failed=%d\n",o->copied,o->replaced,o->written,o->failed);
 pthread_cond_destroy(&o->changed);pthread_mutex_destroy(&o->mutex);o->started=0;return o->failed?-1:0;}
static int frame_callback(const struct ic_yuv_frame*frame,void*opaque){struct output*o=opaque;if(stopping)return -1;struct owned_frame*f=owned_copy(frame);if(!f)return -1;
 pthread_mutex_lock(&o->mutex);if(o->stop||o->failed){pthread_mutex_unlock(&o->mutex);owned_free(f);return -1;}
 if(o->pending){owned_free(o->pending);o->replaced++;}o->pending=f;o->copied++;pthread_cond_signal(&o->changed);pthread_mutex_unlock(&o->mutex);return 0;}

/* Reap guardian before any subsequent session. Never SIGKILL a restoring guardian. */
static int reap_guardian(pid_t pid){
    uint64_t end=ic_now_ms()+5000;int status;
    while(ic_now_ms()<end){pid_t r=waitpid(pid,&status,WNOHANG);if(r==pid)return WIFEXITED(status)&&WEXITSTATUS(status)==0?0:-1;
        if(r<0&&errno!=EINTR)return -1;
        poll(NULL,0,20);
    }
    fprintf(stderr,"FATAL guardian cleanup not confirmed; no retry; do not kill guardian blindly\n");return -1;
}
#define AQ_CAP 12
struct au { unsigned char *p; size_t n; };
struct auq { pthread_mutex_t m; pthread_cond_t c; struct au item[AQ_CAP]; int head,tail,count,done,need_idr,dropped; };
static void auq_init(struct auq*q){memset(q,0,sizeof(*q));pthread_mutex_init(&q->m,NULL);pthread_cond_init(&q->c,NULL);}
static int auq_dropped(struct auq*q){pthread_mutex_lock(&q->m);int n=q->dropped;pthread_mutex_unlock(&q->m);return n;}
static void auq_clear(struct auq*q){while(q->count){free(q->item[q->head].p);q->head=(q->head+1)%AQ_CAP;q->count--;}}
static void auq_push(struct auq*q,unsigned char*p,size_t n,int idr){
    pthread_mutex_lock(&q->m);
    if(q->need_idr&&!idr){free(p);}
    else if(q->count==AQ_CAP){auq_clear(q);q->need_idr=1;q->dropped++;
        if(idr){q->item[q->tail].p=p;q->item[q->tail].n=n;q->tail=(q->tail+1)%AQ_CAP;q->count=1;q->need_idr=0;}
        else free(p);
    } else {q->item[q->tail].p=p;q->item[q->tail].n=n;q->tail=(q->tail+1)%AQ_CAP;q->count++;if(idr)q->need_idr=0;}
    pthread_cond_signal(&q->c);pthread_mutex_unlock(&q->m);
}
static int auq_pop(struct auq*q,struct au*b,int wait_ms){
    struct timespec ts;clock_gettime(CLOCK_REALTIME,&ts);ts.tv_nsec+=(long)wait_ms*1000000L;
    while(ts.tv_nsec>=1000000000L){ts.tv_sec++;ts.tv_nsec-=1000000000L;}
    pthread_mutex_lock(&q->m);
    while(!q->count&&!q->done&&wait_ms>0){if(pthread_cond_timedwait(&q->c,&q->m,&ts)==ETIMEDOUT)break;}
    if(q->count){*b=q->item[q->head];q->head=(q->head+1)%AQ_CAP;q->count--;pthread_mutex_unlock(&q->m);return 1;}
    int d=q->done;pthread_mutex_unlock(&q->m);return d?-1:0;
}
static void auq_finish(struct auq*q){pthread_mutex_lock(&q->m);q->done=1;pthread_cond_broadcast(&q->c);pthread_mutex_unlock(&q->m);}
struct reader_arg {int client;uint64_t end;struct auq*q;};
static void *reader_main(void *v){
    struct reader_arg*r=v;unsigned char header[16];uint64_t prev=0;int have=0;
    while(!stopping&&ic_now_ms()<r->end){
        size_t len;uint64_t pts;
        if(ic_read_exact(r->client,header,sizeof(header),1000))break;
        if(ic_parse_packet(header,&len,&pts)||(have&&pts<=prev))break;
        unsigned char*p=malloc(len);if(!p)break;
        if(ic_read_exact(r->client,p,len,500)){free(p);break;}
        prev=pts;have=1;auq_push(r->q,p,len,has_idr(p,len));
    }
    auq_finish(r->q);return NULL;
}
static int session(int client,int listener,const unsigned char key[32],uint64_t end){
    unsigned char hello[IC_HELLO_BYTES],ack=1;struct ic_session settings;
    int rcvbuf=128*1024;setsockopt(client,SOL_SOCKET,SO_RCVBUF,&rcvbuf,sizeof(rcvbuf));
    if(ic_read_exact(client,hello,sizeof(hello),1500)||ic_parse_hello(hello,key,&settings))return 0;
    /* Reject disabled/malformed policy before reservation, ACK and fork.
     * Geometry/firmware-dependent sufficiency remains decoder admission. */
    uint64_t dma_budget;
    if(ic_dma_request_budget_read(&dma_budget))return -1;
    char dir[4096],prefix[4104];
    if(ic_backup_admit(IC_STATE_ROOT,IC_BACKUP_BYTES,IC_BACKUP_SESSIONS,dir,sizeof(dir)))return -1;
    snprintf(prefix,sizeof(prefix),"%s/fg",dir);
    if(ic_write_exact(client,&ack,1,500))return -1;
    int pipefd[2];if(pipe(pipefd))return -1;
    if(nonblock(pipefd[0])||nonblock(pipefd[1])){close(pipefd[0]);close(pipefd[1]);return -1;}
    pid_t owner=getpid();pid_t child=fork();if(child<0){close(pipefd[0]);close(pipefd[1]);return -1;}
    if(!child){close(pipefd[1]);close(client);close(listener);int r=guardian(pipefd[0],settings.side,prefix,owner);_exit(r);}
    close(pipefd[0]);struct output output={.fd=pipefd[1],.session=settings};
    output.rgb=malloc(IC_RGB_BYTES);int fatal=!output.rgb;
    if(!fatal&&output_start(&output))fatal=1;
    struct auq q;auq_init(&q);
    struct reader_arg ra={client,end,&q};pthread_t reader;int reader_started=0;
    if(!fatal){if(pthread_create(&reader,NULL,reader_main,&ra))fatal=1;else reader_started=1;}
    ic_decoder*decoder=NULL;struct au batch[AQ_CAP];long long batch_sum=0;int batch_n=0;
    while(!fatal){
        int n=0,got=0;
        while(n<AQ_CAP){got=auq_pop(&q,&batch[n],n==0?50:0);if(got==1){n++;continue;}break;}
        if(!n){if(got<0)break;continue;}
        long long t0=ic_now_ms();
        int start=0;
        if(!decoder){decoder=ic_decoder_open(batch[0].p,batch[0].n,frame_callback,&output);if(!decoder)fatal=1;else start=1;}
        for(int i=start;!fatal&&i<n;i++)if(ic_decoder_push(decoder,batch[i].p,batch[i].n))fatal=1;
        if(!fatal&&decoder&&n>start&&ic_decoder_decode(decoder))fatal=1;
        long long dt=ic_now_ms()-t0;batch_sum+=dt;batch_n++;
        if(batch_n%10==0)fprintf(stderr,"IC_FEED batch_n=%d last_batch=%d avg_ms=%lld queue_max=%d dropped=%d\n",batch_n,n,batch_sum/batch_n,AQ_CAP,auq_dropped(&q));
        for(int i=0;i<n;i++)free(batch[i].p);
    }
    if(fatal)stopping=1;
    shutdown(client,SHUT_RDWR);
    if(reader_started&&pthread_join(reader,NULL)){fprintf(stderr,"FATAL reader join unconfirmed; host fail-stop before dependent teardown\n");_exit(74);}
    fprintf(stderr,"IC_SESSION batches=%d dropped=%d\n",batch_n,auq_dropped(&q));
    if(output_stop(&output,fatal||stopping))fatal=1;
    close(pipefd[1]);
    if(decoder&&ic_decoder_close(decoder))fatal=1;
    if(reap_guardian(child))fatal=1;
    auq_clear(&q);
    if(pthread_cond_destroy(&q.c)||pthread_mutex_destroy(&q.m))fatal=1;
    free(output.rgb);return fatal?-1:0;
}

int main(int argc,char **argv){
    if(argc!=7||strcmp(argv[1],"--parked-experimental-ack")){
        fprintf(stderr,"Usage: %s --parked-experimental-ack BIND_IPV4 PORT TOKEN_FILE SECONDS --accept-fg-disable-origin-risk\nNo deployment performed. Max600s. Token plaintext authentication, NOT encryption.\n",argv[0]);return 64;
    }
    uint64_t dma_budget;
    if(ic_dma_request_budget_read(&dma_budget)){fprintf(stderr,"DMA request budget disabled/invalid; no admission\n");return 78;}
    if(!IC_BACKUP_BYTES||!IC_BACKUP_SESSIONS){fprintf(stderr,"Backup budget disabled; no hardware access\n");return 78;}
    if(!IC_VPU_ABI_CONFIRMED){fprintf(stderr,"Native ABI gate disabled at build time; no hardware access\n");return 78;}
    if(strcmp(argv[6],"--accept-fg-disable-origin-risk"))return 64;
    char *tail;long port=strtol(argv[3],&tail,10);if(*tail||port<1024||port>65535)return 64;
    long seconds=strtol(argv[5],&tail,10);if(*tail||seconds<1||seconds>600)return 64;
    struct sockaddr_in addr;memset(&addr,0,sizeof(addr));addr.sin_family=AF_INET;addr.sin_port=htons((unsigned short)port);
    if(inet_pton(AF_INET,argv[2],&addr.sin_addr)!=1||addr.sin_addr.s_addr==INADDR_ANY)return 64;
    unsigned char key[32];if(token_file(argv[4],key)||private_root()){fprintf(stderr,"Private token/root invalid\n");return 1;}
    struct sigaction sa;memset(&sa,0,sizeof(sa));sa.sa_handler=on_signal;sigemptyset(&sa.sa_mask);sigaction(SIGINT,&sa,NULL);sigaction(SIGTERM,&sa,NULL);signal(SIGPIPE,SIG_IGN);
    int listener=socket(AF_INET,SOCK_STREAM,0);if(listener<0)return 1;
    if(fcntl(listener,F_SETFD,FD_CLOEXEC)){close(listener);return 1;}
    if(bind(listener,(struct sockaddr*)&addr,sizeof(addr))||listen(listener,1)||nonblock(listener)){perror("listener");close(listener);return 1;}
    fprintf(stderr,"EXPERIMENTAL: FG disable can transiently move to (0,0); not driving-approved.\n");
    uint64_t end=ic_now_ms()+(uint64_t)seconds*1000u;int result=0;
    while(!stopping&&ic_now_ms()<end){
        struct pollfd p={listener,POLLIN,0};int ready=poll(&p,1,200);if(ready<0&&errno==EINTR)continue;if(ready<0){result=1;break;}if(!ready)continue;
        int client=accept(listener,NULL,NULL);if(client<0){if(errno==EAGAIN||errno==EINTR)continue;result=1;break;}
        if(nonblock(client)){close(client);result=1;break;}
        int r=session(client,listener,key,end);close(client);if(r){result=1;break;}
    }
    close(listener);memset(key,0,sizeof(key));return result;
}
