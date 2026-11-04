#!/usr/bin/env python3
"""Host-only semantic comparison. No device or network access.
Requires cc, NumPy and an existing libyuv.so.0; no package installation.
"""
from pathlib import Path
import ctypes as C, json, subprocess, hashlib
import numpy as np
ROOT=Path(__file__).resolve().parent
N=ROOT.parents[1]/'native'
subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-shared','-fPIC','-I',str(N),str(N/'ic_pixels.c'),'-o',str(ROOT/'ic_pixels_host.so')],check=True)
old=C.CDLL(str(ROOT/'ic_pixels_host.so'))
yuv=C.CDLL('libyuv.so.0')
P=C.POINTER(C.c_uint8)
class Frame(C.Structure):
 _fields_=[('y',P),('u',P),('v',P),('width',C.c_uint),('height',C.c_uint),('y_stride',C.c_uint),('c_stride',C.c_uint)]
old.ic_pixels_rgb565.argtypes=[C.POINTER(Frame),C.c_uint,C.c_uint,C.c_uint,C.c_uint,P,C.c_size_t]
yuv.I420Scale.argtypes=[P,C.c_int,P,C.c_int,P,C.c_int,C.c_int,C.c_int,P,C.c_int,P,C.c_int,P,C.c_int,C.c_int,C.c_int,C.c_int]
yuv.I420ToRGB565.argtypes=[P,C.c_int,P,C.c_int,P,C.c_int,P,C.c_int,C.c_int,C.c_int]
yuv.MaskCpuFlags(-1)
def ptr(a,offset=0): return C.cast(a.ctypes.data+offset,P)
def cmp(a,b):
 a=a.astype(np.int32);b=b.astype(np.int32)
 return dict(changed_pixels=int(np.count_nonzero(a!=b)),max_native_channel_delta=[int(np.max(np.abs((a>>11)-(b>>11)))),int(np.max(np.abs(((a>>5)&63)-((b>>5)&63)))),int(np.max(np.abs((a&31)-(b&31))))])
def case(sw,sh,cx,cy,cw,ch,pattern):
 ys=sw+32;cs=(sw+1)//2+16
 yy,xx=np.indices((sh,ys));cyy,cxx=np.indices(((sh+1)//2,cs))
 if pattern=='luma':
  Y=((xx*3+yy*7)%220+16).astype(np.uint8);U=np.full(cyy.shape,128,np.uint8);V=U.copy()
 elif pattern=='chroma':
  Y=np.full(yy.shape,128,np.uint8);U=((cxx*5+cyy*3)%225+16).astype(np.uint8);V=((cxx*7+cyy*11)%225+16).astype(np.uint8)
 else:
  Y=((xx*3+yy*7)%220+16).astype(np.uint8);U=((cxx*5+cyy*3)%225+16).astype(np.uint8);V=((cxx*7+cyy*11)%225+16).astype(np.uint8)
 f=Frame(ptr(Y),ptr(U),ptr(V),sw,sh,ys,cs)
 ref=np.zeros((315,560),'<u2');out=ref.copy()
 assert old.ic_pixels_rgb565(C.byref(f),cx,cy,cw,ch,ptr(ref),ref.nbytes)==0
 if cw*315>=ch*560:w=560;h=max(1,ch*w//cw)
 else:h=315;w=max(1,cw*h//ch)
 ox=(560-w)//2;oy=(315-h)//2
 dy=np.zeros((h,w),np.uint8);du=np.zeros(((h+1)//2,(w+1)//2),np.uint8);dv=du.copy()
 assert yuv.I420Scale(ptr(Y,cy*ys+cx),ys,ptr(U,(cy//2)*cs+cx//2),cs,ptr(V,(cy//2)*cs+cx//2),cs,cw,ch,ptr(dy),w,ptr(du),du.shape[1],ptr(dv),du.shape[1],w,h,0)==0
 assert yuv.I420ToRGB565(ptr(dy),w,ptr(du),du.shape[1],ptr(dv),du.shape[1],ptr(out,2*(oy*560+ox)),1120,w,h)==0
 mapped_y=Y[cy+(np.arange(h)*ch//h)[:,None],cx+(np.arange(w)*cw//w)[None,:]]
 result=dict(source=[sw,sh],crop=[cx,cy,cw,ch],pattern=pattern,fit=[w,h],offset=[ox,oy],**cmp(ref,out),luma_sample_differences=int(np.count_nonzero(mapped_y!=dy)))
 print(json.dumps(result));return result
results=[]
for shape in [(1280,720,0,0,1280,720),(320,240,0,0,320,240),(1280,720,0,0,640,360),(7,5,3,1,3,3),(7,5,6,4,1,1)]:
 for pattern in ('luma','chroma'):results.append(case(*shape,pattern))
# Exhaust all 256^3 input Y,U,V combinations, independently of scaling.
# A 512-pixel 4:2:0 row duplicates each V value, so pair chroma is exact.
maxd=np.zeros(3,np.int32);changed=0;sample=None
Y=np.empty((1,512),np.uint8);U=np.empty((1,256),np.uint8);V=np.arange(256,dtype=np.uint8).reshape(1,256);RGB=np.empty((1,512),'<u2')
vs=np.arange(256,dtype=np.int32)-128
for y in range(256):
 Y.fill(y)
 for u in range(256):
  U.fill(u)
  assert yuv.I420ToRGB565(ptr(Y),512,ptr(U),256,ptr(V),256,ptr(RGB),1024,512,1)==0
  c=y-16;ud=u-128
  r=np.clip((298*c+409*vs+128)//256,0,255);g=np.clip((298*c-100*ud-208*vs+128)//256,0,255);b=np.full(256,np.clip((298*c+516*ud+128)//256,0,255),np.int32)
  ref=((r>>3)<<11)|((g>>2)<<5)|(b>>3);out=RGB[0,::2].astype(np.int32)
  changed+=int(np.count_nonzero(ref!=out))
  d=np.array([np.max(np.abs((ref>>11)-(out>>11))),np.max(np.abs(((ref>>5)&63)-((out>>5)&63))),np.max(np.abs((ref&31)-(out&31)))])
  maxd=np.maximum(maxd,d)
  if sample is None and np.any(ref!=out):
   v=int(np.flatnonzero(ref!=out)[0]);sample=dict(y=y,u=u,v=v,original=f'{ref[v]:04x}',libyuv=f'{out[v]:04x}')
colors=dict(input_combinations=256**3,changed_combinations=changed,max_native_channel_delta=maxd.tolist(),first_difference=sample)
print('COLOR_ONLY '+json.dumps(colors))
(ROOT/'comparison-results.json').write_text(json.dumps(dict(geometry=results,colors=colors),indent=2)+'\n')
