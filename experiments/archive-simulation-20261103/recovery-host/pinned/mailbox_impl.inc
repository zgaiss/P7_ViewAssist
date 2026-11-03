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
