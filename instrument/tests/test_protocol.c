#define _POSIX_C_SOURCE 200809L
#include "ic_protocol.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
static void be(unsigned char*p,unsigned n){p[0]=n>>24;p[1]=n>>16;p[2]=n>>8;p[3]=n;}
int main(int argc,char**argv){
 unsigned char key[32],hello[60]={0};for(unsigned i=0;i<32;i++)key[i]=(unsigned char)((i%8)*0x22+1); /* replaced below by exact Java fixture */
 const unsigned char k[]={0x01,0x23,0x45,0x67,0x89,0xab,0xcd,0xef};for(unsigned i=0;i<32;i++)key[i]=k[i%8];
 if(argc==2){FILE*f=fopen(argv[1],"rb");assert(f);assert(fread(hello,1,60,f)==60);assert(fgetc(f)==EOF);fclose(f);}
 else{memcpy(hello,"P7IC0001",8);memcpy(hello+8,key,32);be(hello+40,2);be(hello+44,640);be(hello+48,360);be(hello+52,640);be(hello+56,360);}
 struct ic_session s;assert(!ic_parse_hello(hello,key,&s));assert(s.side==2&&s.crop_x==640&&s.crop_y==360&&s.crop_w==640&&s.crop_h==360);
 hello[8]^=1;assert(ic_parse_hello(hello,key,&s));hello[8]^=1;
 be(hello+44,0xffffffffu);assert(ic_parse_hello(hello,key,&s));
 unsigned char packet[16]={0};size_t n;uint64_t pts;be(packet,1);be(packet+4,100);be(packet+12,12345);
 assert(!ic_parse_packet(packet,&n,&pts)&&n==100&&pts==12345);be(packet+4,IC_MAX_AU+1);assert(ic_parse_packet(packet,&n,&pts));
 int fd[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,fd));assert(fcntl(fd[0],F_SETFL,O_NONBLOCK)==0);assert(fcntl(fd[1],F_SETFL,O_NONBLOCK)==0);
 assert(!ic_write_exact(fd[0],"hello",5,100));char b[6]={0};assert(!ic_read_exact(fd[1],b,5,100));assert(!strcmp(b,"hello"));
 uint64_t start=ic_now_ms();assert(ic_read_exact(fd[1],b,1,20)<0&&errno==ETIMEDOUT);assert(ic_now_ms()-start<500);
 close(fd[0]);assert(ic_read_exact(fd[1],b,1,100)<0);close(fd[1]);puts("C protocol bounds/auth/IO/Java fixture PASS");return 0;
}
