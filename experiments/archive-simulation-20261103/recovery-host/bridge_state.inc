/* HOST-ONLY cooperative shared-memory device, not physical hardware.
 * Mappings survive writer SIGKILL because the observing parent owns the file.
 * No automatic initialization occurs on attach or process restart. */
#include <stddef.h>
struct bridge_state {
 uint64_t magic;
 uint64_t admission_epoch;
 int owner_pid;
 struct fb_var_screeninfo fg_var,bg_var;
 struct fb_fix_screeninfo fg_fix,bg_fix;
 uint32_t dp[1024];
 unsigned char framebuffer[1100000];
 unsigned char initial_pixels[460800];
 int blank, maps, wide, mutation_count, fail_at, faulted, unsafe, enable_count, origin_count;
};
static struct bridge_state *hw;
#define fv (hw->fg_var)
#define bv (hw->bg_var)
#define ff (hw->fg_fix)
#define bf (hw->bg_fix)
#define regbank (hw->dp)
#define pixels (hw->framebuffer)
#define original (hw->initial_pixels)
#define fbblank (hw->blank)
#define mapped (hw->maps)
#define modewide (hw->wide)
#define mutations (hw->mutation_count)
#define failat (hw->fail_at)
#define failed (hw->faulted)
#define unsafe_transitions (hw->unsafe)
#define enables (hw->enable_count)
#define transient_origin (hw->origin_count)
static char bridge_prefix[2048];
static int admission=0;
static void bridge_write(const char *line){
 const char *v=getenv("P7_BRIDGE_FD");if(!v)_exit(109);int fd=atoi(v);size_t n=strlen(line);
 while(n){ssize_t k=write(fd,line,n);if(k<0&&errno==EINTR)continue;if(k<=0)_exit(110);line+=k;n-=(size_t)k;}
}
static int bridge_admit(void){
 if(admission>0)return 0;
 if(admission<0){errno=EPERM;return -1;}
 admission=-1;
 uint64_t previous_epoch=hw->admission_epoch;hw->owner_pid=(int)getpid();
 char line[2300];snprintf(line,sizeof line,"{\"event\":\"ready\",\"pid\":%ld,\"prefix\":\"%s\"}\n",(long)getpid(),bridge_prefix);bridge_write(line);
 const char *v=getenv("P7_BRIDGE_FD");if(!v){errno=EPERM;return -1;}int fd=atoi(v);char reply[6];size_t pos=0;
 while(pos<sizeof reply){ssize_t n=read(fd,reply+pos,sizeof reply-pos);if(n<0&&errno==EINTR)continue;if(n<=0){errno=EPERM;return -1;}pos+=(size_t)n;}
 if(memcmp(reply,"ADMIT\n",6)||hw->admission_epoch<=previous_epoch||hw->owner_pid!=(int)getpid()){errno=EPERM;return -1;}
 admission=1;return 0;
}
static int attach_state(int create){
 const char *p=getenv("P7_HW_STATE");if(!p)return -1;
 int fd=open(p,O_RDWR|O_NOFOLLOW|O_CLOEXEC|(create?(O_CREAT|O_EXCL):0),0600);if(fd<0)return -1;
 struct stat st;if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_uid!=geteuid()||st.st_nlink!=1||(st.st_mode&0777)!=0600){close(fd);return -1;}
 if(create&&ftruncate(fd,sizeof *hw)){close(fd);return -1;}
 if(fstat(fd,&st)||st.st_size!=(off_t)sizeof *hw){close(fd);return -1;}
 hw=mmap(NULL,sizeof *hw,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);close(fd);
 if(hw==MAP_FAILED){hw=NULL;return -1;}
 if(!create&&hw->magic!=0x5037434f4d504f53ULL){munmap(hw,sizeof *hw);hw=NULL;return -1;}
 return 0;
}
