#ifndef IC_PROTOCOL_H
#define IC_PROTOCOL_H
#include <stddef.h>
#include <stdint.h>
#define IC_HELLO_BYTES 60u
#define IC_MAX_AU (1024u*1024u)
#define IC_RGB_BYTES (560u*315u*2u)
struct ic_session {unsigned side,crop_x,crop_y,crop_w,crop_h;};
uint64_t ic_now_ms(void);
int ic_read_exact(int fd,void *buf,size_t n,unsigned timeout_ms);
int ic_write_exact(int fd,const void *buf,size_t n,unsigned timeout_ms);
int ic_parse_hello(const unsigned char b[IC_HELLO_BYTES],const unsigned char key[32],struct ic_session *s);
int ic_parse_packet(const unsigned char b[16],size_t *len,uint64_t *pts);
#endif
