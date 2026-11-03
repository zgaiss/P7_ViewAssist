/* The wrapper changes only the test path and attaches the explicit virtual
 * backend. It does not infer an admission from a file or stale marker. */
ic_display *ic_display_open(unsigned side,const char *prefix){
 if(!hw&&attach_state(0)){errno=EPERM;return NULL;}
 const char *root=getenv("P7_STATE_ROOT");if(!root||!prefix||strncmp(prefix,root,strlen(root))||prefix[strlen(root)]!='/'){errno=EINVAL;return NULL;}
 if(strlen(prefix)>=sizeof bridge_prefix){errno=EINVAL;return NULL;}
 strcpy(bridge_prefix,prefix);admission=0;
 char fake[2048];if(snprintf(fake,sizeof fake,"/tmp/p7-viewassist%s",prefix+strlen(root))>=(int)sizeof fake){errno=EINVAL;return NULL;}
 return host_production_display_open(side,fake);
}
#ifdef BRIDGE_DRIVER
static void layout(void){
 #define FIELD(n) printf("\"%s\":{\"offset\":%zu,\"size\":%zu},",#n,offsetof(struct bridge_state,n),sizeof(((struct bridge_state*)0)->n))
 printf("{\"size\":%zu,\"fields\":{",sizeof(struct bridge_state));
 FIELD(magic);FIELD(admission_epoch);FIELD(owner_pid);FIELD(fg_var);FIELD(bg_var);FIELD(fg_fix);FIELD(bg_fix);FIELD(dp);FIELD(framebuffer);FIELD(initial_pixels);FIELD(blank);FIELD(maps);FIELD(wide);FIELD(mutation_count);FIELD(fail_at);FIELD(faulted);FIELD(unsafe);FIELD(enable_count);
 printf("\"origin_count\":{\"offset\":%zu,\"size\":%zu}}}\n",offsetof(struct bridge_state,origin_count),sizeof(((struct bridge_state*)0)->origin_count));
 #undef FIELD
}
int main(int argc,char **argv){
 if(argc==2&&!strcmp(argv[1],"--layout")){layout();return 0;}
 if(argc==2&&!strcmp(argv[1],"--init")){
  if(attach_state(1))return 2;fbblank=4;setup_baseline();hw->magic=0x5037434f4d504f53ULL;
  if(msync(hw,sizeof *hw,MS_SYNC))return 3;return 0;
 }
 if(argc!=2||attach_state(0))return 4;
 ic_display*d=ic_display_open(1,argv[1]);if(!d){bridge_write("{\"event\":\"open_failed\"}\n");return 5;}
 unsigned char rgb[IC_PIXELS_RGB565_BYTES];memset(rgb,0xa5,sizeof rgb);
 if(ic_display_frame(d,rgb,sizeof rgb)){bridge_write("{\"event\":\"frame_failed\"}\n");ic_display_close(d);return 6;}
 bridge_write("{\"event\":\"frame\"}\n");char cmd[6];size_t pos=0;int fd=atoi(getenv("P7_BRIDGE_FD"));
 while(pos<sizeof cmd){ssize_t n=read(fd,cmd+pos,sizeof cmd-pos);if(n<0&&errno==EINTR)continue;if(n<=0)_exit(111);pos+=(size_t)n;}
 if(memcmp(cmd,"CLOSE\n",6))_exit(112);
 int rc=ic_display_close(d);bridge_write(rc?"{\"event\":\"close_failed\"}\n":"{\"event\":\"closed\"}\n");return rc?7:0;
}
#endif
