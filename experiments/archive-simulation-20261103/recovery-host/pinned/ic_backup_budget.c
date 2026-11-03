#define _GNU_SOURCE
#include "ic_backup_budget.h"
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#ifndef BUDGET_FAULT
#define BUDGET_FAULT(stage) 0
#endif
/* Trusted local filesystem with durable fsync and flock required. Root must be
 * absolute with no symlink components. Same-uid/root malicious edits, rollback,
 * noncooperating writers and reboot-lost tmpfs are NOT an integrity boundary. */
static int private_dir(int fd) {
 struct stat s;return fstat(fd,&s)||!S_ISDIR(s.st_mode)||s.st_uid!=geteuid()||(s.st_mode&0777)!=0700?-1:0;
}
static int root_open(const char *path){
 if(!path||path[0]!='/'||strlen(path)>4095)return -1;
 char copy[4096];strcpy(copy,path);int fd=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);if(fd<0)return -1;
 char *save=NULL,*p=strtok_r(copy,"/",&save);
 if(!p){close(fd);return -1;}
 while(p){if(!strcmp(p,".")||!strcmp(p,"..")){close(fd);return -1;}
 int next=openat(fd,p,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);close(fd);if(next<0)return -1;fd=next;p=strtok_r(NULL,"/",&save);}
 if(private_dir(fd)){close(fd);return -1;}return fd;
}
static int regular(struct stat *s){return S_ISREG(s->st_mode)&&s->st_uid==geteuid()&&s->st_nlink==1&&(s->st_mode&0777)==0600;}
static int inspect_session(int root,const char *name){
 static const char *names[]={"fg.original.var","fg.original.fix","fg.original.bgvar","fg.original.bgfix","fg.original.dp","fg.original.modes","fg.original.pixels"};
 static const off_t limits[]={160,68,160,68,12,14,16777216};
 int fd=openat(root,name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;
 if(private_dir(fd)){close(fd);return -1;}DIR *d=fdopendir(fd);if(!d){close(fd);return -1;}
 int rc=0;struct dirent *e;errno=0;
 while((e=readdir(d))){if(!strcmp(e->d_name,".")||!strcmp(e->d_name,".."))continue;
 unsigned i;for(i=0;i<7;i++)if(!strcmp(e->d_name,names[i]))break;
 struct stat s;if(i==7||fstatat(fd,e->d_name,&s,AT_SYMLINK_NOFOLLOW)||!regular(&s)||s.st_size<0||s.st_size>limits[i]){rc=-1;break;}errno=0;}
 if(errno)rc=-1;
 closedir(d);return rc;
}
/* Bound header/slots before conversion; maximum offset is 1,000,127,
 * representable by signed 32-bit off_t. Never mix signed off_t and unsigned
 * slots in arithmetic: ARM32 promotes that expression to unsigned. */
static int ledger_size_matches(off_t size,int header,unsigned slots){
 if(size<0||header<=0||header>=128||!slots||slots>1000000)return 0;
 return (uint64_t)size==(uint64_t)header+(uint64_t)slots;
}
int ic_backup_admit(const char *root,uint64_t bytes,unsigned slots,char *out,size_t capacity){
 int rd=-1,ld=-1,rc=-1;unsigned char *state=NULL;DIR *scan=NULL;
 /* Bounded parser memory and identifier width; policy is explicit, never guessed. */
 if(!out||!root||!slots||slots>1000000||bytes<IC_BACKUP_UNIT||slots>bytes/IC_BACKUP_UNIT)goto done;
 char header[128];int hn=snprintf(header,sizeof(header),"ICBB1 %llu %u %llu\n",(unsigned long long)bytes,slots,(unsigned long long)IC_BACKUP_UNIT);
 rd=root_open(root);if(rd<0)goto done;
 ld=openat(rd,"backup.budget",O_RDWR|O_NOFOLLOW|O_CLOEXEC);if(ld<0||flock(ld,LOCK_EX))goto done;
 struct stat s,t;if(fstat(ld,&s)||!regular(&s)||!ledger_size_matches(s.st_size,hn,slots)||fstatat(rd,"backup.budget",&t,AT_SYMLINK_NOFOLLOW)||s.st_dev!=t.st_dev||s.st_ino!=t.st_ino)goto done;
 char got[128];if(pread(ld,got,(size_t)hn,0)!=hn||memcmp(got,header,(size_t)hn))goto done;
 state=malloc(slots);if(!state||pread(ld,state,slots,hn)!=(ssize_t)slots)goto done;
 unsigned selected=slots;for(unsigned i=0;i<slots;i++){if(state[i]!='0'&&state[i]!='R')goto done;if(state[i]=='0'&&selected==slots)selected=i;}
 /* Scan also on exhaustion: no implicit migration of legacy snapshots. */
 int dupfd=dup(rd);if(dupfd<0)goto done;scan=fdopendir(dupfd);if(!scan){close(dupfd);goto done;}
 struct dirent *e;errno=0;while((e=readdir(scan))){
 if(!strcmp(e->d_name,".")||!strcmp(e->d_name,"..")||!strcmp(e->d_name,"backup.budget"))continue;
 if(!strcmp(e->d_name,"display.owner.lock")){if(fstatat(rd,e->d_name,&t,AT_SYMLINK_NOFOLLOW)||!regular(&t))goto done;continue;}
 unsigned n=0;
 if(strlen(e->d_name)!=21||strncmp(e->d_name,"session-budget-",15))goto done;
 for(unsigned k=15;k<21;k++){unsigned char c=(unsigned char)e->d_name[k];if(c<'0'||c>'9')goto done;n=n*10u+(unsigned)(c-'0');}
 if(n>=slots||state[n]!='R'||inspect_session(rd,e->d_name))goto done;
 errno=0;
 }if(errno||selected==slots)goto done;
 char name[64];snprintf(name,sizeof(name),"session-budget-%06u",selected);
 if(snprintf(out,capacity,"%s/%s",root,name)>=(int)capacity)goto done;
 /* Reservation committed BEFORE mkdir, fork, device open or display mutation.
  * Any failure after this point burns the slot forever, even with no files. */
 if(BUDGET_FAULT(1)||pwrite(ld,"R",1,(off_t)hn+(off_t)selected)!=1)goto done;
 if(BUDGET_FAULT(2)||fsync(ld))goto done;
 if(BUDGET_FAULT(3)||mkdirat(rd,name,0700))goto done;
 if(BUDGET_FAULT(4)||fsync(rd))goto done;
 rc=0;
done:
 if(scan)closedir(scan);
 free(state);
 if(ld>=0)close(ld);
 if(rd>=0)close(rd);
 if(rc&&out&&capacity)out[0]=0;
 return rc;
}
