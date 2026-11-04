#!/usr/bin/env python3
"""Actual candidate source; host-only decoder/display stubs. No device access."""
from pathlib import Path
import os, socket, struct, subprocess, tempfile, time
N=Path(__file__).resolve().parents[1]/'native'
T=N.parent/'tests'
WRAP=r'''
#include <pthread.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
int __wrap_pthread_join(pthread_t t, void **p) {
 (void)t; (void)p;
 int flags=fcntl(2,F_GETFL); fcntl(2,F_SETFL,flags|O_NONBLOCK);
 char b[4096]={0}; while(write(2,b,sizeof b)>0){}
 while(write(2,b,1)>0){}
 fcntl(2,F_SETFL,flags); return EINVAL;
}
'''
with tempfile.TemporaryDirectory(prefix='p7-host-join-') as td:
 d=Path(td); wrap=d/'wrap.c';wrap.write_text(WRAP)
 token=d/'token';key=bytes(range(32));token.write_text(key.hex());token.chmod(0o600)
 for prof in (0,1):
  binary=d/f'receiver-{prof}';log=d/f'events-{prof}';state=d/f'state-{prof}'
  subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-pthread','-fsanitize=undefined','-DIC_VPU_ABI_CONFIRMED=1',f'-DIC_PROFILE={prof}',f'-DIC_STATE_ROOT="{state}"','-I',str(N),str(N/'ic_receiver.c'),str(N/'ic_protocol.c'),str(T/'receiver_stubs.c'),str(wrap),'-Wl,--wrap=pthread_join','-o',str(binary)],check=True)
  with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
  p=subprocess.Popen([str(binary),'--parked-experimental-ack','127.0.0.1',str(port),str(token),'4','--accept-fg-disable-origin-risk'],env=dict(os.environ,IC_TEST_LOG=str(log)),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  s=None
  def events():return log.read_text().splitlines() if log.exists() else []
  try:
   deadline=time.monotonic()+2
   while time.monotonic()<deadline:
    try:s=socket.create_connection(('127.0.0.1',port),timeout=.2);break
    except (ConnectionRefusedError,TimeoutError):time.sleep(.01)
   assert s is not None
   s.settimeout(2);s.sendall(b'P7IC0001'+key+struct.pack('>IIIII',1,0,0,1280,720));assert s.recv(1)==b'\1'
   s.sendall(struct.pack('>IIQ',1,1,1)+b'a')
   deadline=time.monotonic()+2
   while 'frame' not in events() and time.monotonic()<deadline:time.sleep(.01)
   assert 'frame' in events(),events()
   s.close();s=None
   assert p.wait(timeout=2)==74
   deadline=time.monotonic()+2
   while 'restore' not in events() and time.monotonic()<deadline:time.sleep(.01)
   assert 'restore' in events(),events()
   _,err=p.communicate(timeout=3)
   assert b'IC_SESSION' not in err
   assert b'runtime error:' not in err
   print(f'PASS profile={prof}: exit 74 with full blocking stderr; independent guardian stub restored before stderr drained; events={events()}')
  finally:
   if s:s.close()
   if p.poll() is None:p.kill()
   p.communicate(timeout=5)
