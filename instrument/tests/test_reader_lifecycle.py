#!/usr/bin/env python3
"""HOST STUB ONLY. Exercise actual receiver failure paths with linker wrappers.
No VPU, display device, SDK, deployment or target-runtime test.
"""
from pathlib import Path
import os, socket, struct, subprocess, tempfile, time
ROOT=Path(__file__).resolve().parents[2]
N=ROOT/'instrument/native'
WRAPPER=r'''
#include <pthread.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
void *__real_malloc(size_t);
int __real_pthread_create(pthread_t *, const pthread_attr_t *,void *(*)(void *),void *);
int __real_pthread_join(pthread_t,void **);
static int mode(const char *s){const char *m=getenv("IC_FAULT");return m&&!strcmp(m,s);}
void *__wrap_malloc(size_t n){if(mode("malloc")&&n==352800){fprintf(stderr,"FAULT malloc\n");return NULL;}return __real_malloc(n);}
int __wrap_pthread_create(pthread_t*t,const pthread_attr_t*a,void*(*f)(void*),void*v){
 if(mode("create")){fprintf(stderr,"FAULT create\n");return EAGAIN;}return __real_pthread_create(t,a,f,v);}
int __wrap_pthread_join(pthread_t t,void**v){fprintf(stderr,"JOIN attempted\n");
 if(mode("join")){fprintf(stderr,"FAULT join\n");return EINVAL;}return __real_pthread_join(t,v);}
'''
def main():
 with tempfile.TemporaryDirectory(prefix='p7-reader-lifecycle-') as td:
  out=Path(td);state=out/'state';wrap=out/'fault.c';wrap.write_text(WRAPPER)
  binary=out/'receiver'
  subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-pthread','-fsanitize=undefined','-DIC_VPU_ABI_CONFIRMED=1',f'-DIC_STATE_ROOT="{state}"','-I',str(N),str(N/'ic_receiver.c'),str(N/'ic_protocol.c'),str(Path(__file__).with_name('receiver_stubs.c')),str(wrap),'-Wl,--wrap=malloc,--wrap=pthread_create,--wrap=pthread_join','-o',str(binary)],check=True)
  # Public deterministic test fixture, never a deployment credential.
  key=bytes(range(32));token=out/'fixture-token';token.write_text(key.hex());token.chmod(0o600)
  for mode in ('malloc','create','join','normal'):
   with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
   p=subprocess.Popen([str(binary),'--parked-experimental-ack','127.0.0.1',str(port),str(token),'1','--accept-fg-disable-origin-risk'],env=dict(os.environ,IC_FAULT=mode,IC_TEST_LOG=str(out/'events')),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
   s=None
   try:
    deadline=time.monotonic()+2
    while time.monotonic()<deadline:
     try:s=socket.create_connection(('127.0.0.1',port),timeout=.2);break
     except (ConnectionRefusedError,TimeoutError):time.sleep(.01)
    assert s is not None,mode
    s.settimeout(2);s.sendall(b'P7IC0001'+key+struct.pack('>IIIII',1,0,0,1280,720));assert s.recv(1)==b'\x01'
    s.close();s=None
    stdout,stderr=p.communicate(timeout=7);text=stderr.decode()
    assert 'runtime error:' not in text,(mode,text)
    if mode in ('malloc','create'):assert p.returncode==1 and 'JOIN attempted' not in text,(mode,p.returncode,text)
    elif mode=='join':assert p.returncode==74 and 'FATAL reader join unconfirmed' in text and 'IC_SESSION' not in text,(mode,p.returncode,text)
    else:assert p.returncode==0 and text.count('JOIN attempted')==1,(mode,p.returncode,text)
    print('PASS',mode,'exit',p.returncode)
   finally:
    if s:s.close()
    if p.poll() is None:p.kill();p.communicate(timeout=7)
 print('PASS actual receiver reader lifecycle: 4 host fault/normal scenarios')
if __name__=='__main__':main()
