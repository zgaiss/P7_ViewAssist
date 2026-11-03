#define _POSIX_C_SOURCE 200809L
#include "ic_protocol.h"
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
uint64_t ic_now_ms(void){struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;return (uint64_t)t.tv_sec*1000u+(unsigned)t.tv_nsec/1000000u;}
static int transfer(int fd,void *buf,size_t n,unsigned ms,int writing){
    unsigned char *p=buf;uint64_t end=ic_now_ms()+ms;
    while(n){uint64_t now=ic_now_ms();if(now>=end){errno=ETIMEDOUT;return -1;}
        struct pollfd f={fd,writing?POLLOUT:POLLIN,0};int r=poll(&f,1,(int)(end-now));
        if(r<0&&errno==EINTR)continue;
        if(r<=0){if(!r)errno=ETIMEDOUT;return -1;}
        if(f.revents&(POLLERR|POLLNVAL)){errno=EIO;return -1;}
        ssize_t k=writing?write(fd,p,n):read(fd,p,n);
        if(k<0&&(errno==EINTR||errno==EAGAIN||errno==EWOULDBLOCK))continue;
        if(k<=0){if(!k)errno=EPIPE;return -1;}p+=k;n-=(size_t)k;
    }return 0;
}
int ic_read_exact(int fd,void*b,size_t n,unsigned ms){return transfer(fd,b,n,ms,0);}
int ic_write_exact(int fd,const void*b,size_t n,unsigned ms){return transfer(fd,(void*)b,n,ms,1);}
static uint32_t be32(const unsigned char*p){return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];}
int ic_parse_hello(const unsigned char*b,const unsigned char*key,struct ic_session*s){
    unsigned mismatch=0;for(unsigned i=0;i<32;i++)mismatch|=b[8+i]^key[i];
    if(memcmp(b,"P7IC0001",8)||mismatch)return -1;
    s->side=be32(b+40);s->crop_x=be32(b+44);s->crop_y=be32(b+48);s->crop_w=be32(b+52);s->crop_h=be32(b+56);
    if((s->side!=1&&s->side!=2)||!s->crop_w||!s->crop_h||s->crop_w>1280||s->crop_h>720||s->crop_x>1280-s->crop_w||s->crop_y>720-s->crop_h)return -1;
    return 0;
}
int ic_parse_packet(const unsigned char*b,size_t*len,uint64_t*pts){
    *len=be32(b+4);*pts=((uint64_t)be32(b+8)<<32)|be32(b+12);
    return be32(b)!=1||!*len||*len>IC_MAX_AU||(*pts>>63)?-1:0;
}
