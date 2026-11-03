#define _GNU_SOURCE
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include <string.h>
#include <sys/wait.h>
#include <errno.h>
void *__real_malloc(size_t);void *__real_calloc(size_t,size_t);void __real_free(void*);int __real_poll(struct pollfd*,nfds_t,int);void __real__exit(int) __attribute__((noreturn));
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static void *baseline[16384];static void *ptrs[16384];static size_t sizes[16384],count,bytes,basecount,basebytes;static unsigned audits;static int based;
static void fail(const char *s){fprintf(stderr,"OWNER_FAIL %s count=%zu bytes=%zu baseline=%zu/%zu\n",s,count,bytes,basecount,basebytes);abort();}
static void record(void *p,size_t n){if(!p)return;pthread_mutex_lock(&lock);size_t i;for(i=0;i<16384;i++)if(!ptrs[i])break;if(i==16384)fail("capacity");ptrs[i]=p;sizes[i]=n;count++;bytes+=n;pthread_mutex_unlock(&lock);}
void *__wrap_malloc(size_t n){void*p=__real_malloc(n);record(p,n);return p;}
void *__wrap_calloc(size_t n,size_t s){void*p=__real_calloc(n,s);record(p,n*s);return p;}
void __wrap_free(void*p){if(!p)return;pthread_mutex_lock(&lock);size_t i;for(i=0;i<16384;i++)if(ptrs[i]==p)break;if(i==16384)fail("unowned/double free");count--;bytes-=sizes[i];ptrs[i]=NULL;sizes[i]=0;pthread_mutex_unlock(&lock);__real_free(p);}
int __wrap_poll(struct pollfd *p,nfds_t n,int timeout){if(n==1&&timeout==200){pthread_mutex_lock(&lock);if(!based){based=1;basecount=count;basebytes=bytes;memcpy(baseline,ptrs,sizeof ptrs);}if(count!=basecount||bytes!=basebytes||memcmp(baseline,ptrs,sizeof ptrs))fail("session heap imbalance");pthread_mutex_unlock(&lock);errno=0;int status;pid_t child=waitpid(-1,&status,WNOHANG);if(child!=-1||errno!=ECHILD)fail("unreaped/live child");fprintf(stderr,"OWNER_BOUNDARY audit=%u heap=%zu bytes=%zu pid=%ld\n",++audits,count,bytes,(long)getpid());}return __real_poll(p,n,timeout);}
void __wrap__exit(int code){if(count!=basecount||bytes!=basebytes||memcmp(baseline,ptrs,sizeof ptrs))fail("guardian heap imbalance");fprintf(stderr,"GUARDIAN_OWNER_EXIT pid=%ld heap=%zu bytes=%zu code=%d\n",(long)getpid(),count,bytes,code);__real__exit(code);}
#include <sys/socket.h>
int __real_accept(int,struct sockaddr*,socklen_t*);int __real_close(int);
static int client_fd=-1;static pid_t receiver_pid;static unsigned retired;
int __wrap_accept(int fd,struct sockaddr*a,socklen_t*n){int r=__real_accept(fd,a,n);if(r>=0){client_fd=r;receiver_pid=getpid();}return r;}
int __wrap_close(int fd){int r=__real_close(fd);if(fd==client_fd&&getpid()==receiver_pid){client_fd=-1;if(!retired&&getenv("OWNER_NEGATIVE_CONTROL")){if(!strcmp(getenv("OWNER_NEGATIVE_CONTROL"),"heap")){void *lost=__wrap_malloc(17);if(!lost)abort();}else if(!strcmp(getenv("OWNER_NEGATIVE_CONTROL"),"fd")){int fds[2];if(pipe(fds))abort();}}fprintf(stderr,"CLIENT_RETIRED n=%u result=%d\n",++retired,r);}return r;}
