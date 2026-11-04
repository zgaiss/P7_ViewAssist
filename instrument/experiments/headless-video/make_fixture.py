#!/usr/bin/env python3
"""Generate trusted synthetic H.264 and software-decoded I420. Never uses devices."""
import argparse, hashlib, json, re, shutil, struct, subprocess
from pathlib import Path

def fnv(data):
    h=14695981039346656037
    for b in data: h=((h^b)*1099511628211)&0xffffffffffffffff
    return h

def rgb_reference(raw, width, height):
    if width*315>=height*560: w,h=560,max(1,height*560//width)
    else: w,h=max(1,width*315//height),315
    ox,oy=(560-w)//2,(315-h)//2
    out=bytearray(560*315*2)
    ysize=width*height; csize=ysize//4
    xs=[x*width//w for x in range(w)]
    for dy in range(h):
        sy=dy*height//h
        for dx,sx in enumerate(xs):
            c=raw[sy*width+sx]-16
            uv=(sy//2)*(width//2)+sx//2
            u=raw[ysize+uv]-128; v=raw[ysize+csize+uv]-128
            r=max(0,min(255,(298*c+409*v+128)//256))
            g=max(0,min(255,(298*c-100*u-208*v+128)//256))
            b=max(0,min(255,(298*c+516*u+128)//256))
            p=((r>>3)<<11)|((g>>2)<<5)|(b>>3)
            at=((dy+oy)*560+dx+ox)*2
            out[at]=p&255;out[at+1]=p>>8
    return out

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('out',type=Path);ap.add_argument('--width',type=int,default=1280)
    ap.add_argument('--height',type=int,default=720);ap.add_argument('--frames',type=int,default=30)
    a=ap.parse_args()
    if not (64<=a.width<=1280 and 64<=a.height<=720 and a.width%2==a.height%2==0 and 1<=a.frames<=120): ap.error('fixture bounds')
    a.out.mkdir(parents=True,exist_ok=False)
    exe=shutil.which('ffmpeg')
    if not exe: raise SystemExit('ffmpeg with libx264 required; nothing installed automatically')
    commands=[]
    def run(args):
        cmd=[exe,'-nostdin','-hide_banner','-loglevel','error',*map(str,args)]
        commands.append(cmd);subprocess.run(cmd,check=True)
    h264=a.out/'fixture.h264'; yuv=a.out/'fixture.i420';bundle=a.out/'fixture.p7au'
    run(['-f','lavfi','-i',f'testsrc2=size={a.width}x{a.height}:rate=10','-frames:v',a.frames,
         '-threads','1','-c:v','libx264','-profile:v','baseline','-level','3.1','-pix_fmt','yuv420p','-bf','0',
         '-x264-params','aud=1:repeat-headers=1:keyint=10:scenecut=0:threads=1','-f','h264',h264])
    run(['-hwaccel','none','-threads','1','-i',h264,'-pix_fmt','yuv420p','-f','rawvideo',yuv])
    data=h264.read_bytes();starts=[m.start() for m in re.finditer(b'\x00\x00\x00\x01\x09',data)]
    if len(starts)!=a.frames or starts[0]!=0: raise ValueError('Expected four-byte AUD at every AU')
    starts.append(len(data));pixels=yuv.read_bytes();size=a.width*a.height*3//2
    if len(pixels)!=size*a.frames:raise ValueError('Software decoder frame count mismatch')
    hashes=[]
    with bundle.open('xb') as out:
        out.write(struct.pack('<8sIII',b'P7AU0001',a.width,a.height,a.frames))
        for i in range(a.frames):
            au=data[starts[i]:starts[i+1]]
            if not 0<len(au)<=1024*1024:raise ValueError('AU bounds')
            h=fnv(rgb_reference(pixels[size*i:size*(i+1)],a.width,a.height));hashes.append(f'{h:016x}')
            out.write(struct.pack('<IQ',len(au),h));out.write(au)
    manifest={'scope':'synthetic host fixture, software decoded; not VPU output',
        'width':a.width,'height':a.height,'frames':a.frames,'reference':'independent Python BT.601 limited-range integer arithmetic, full-frame nearest aspect-fit RGB565LE',
        'ffmpeg':subprocess.check_output([exe,'-version'],text=True).splitlines()[0],
        'commands':commands,'rgb_fnv1a64':hashes,
        'files':{p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in [h264,yuv,bundle]}}
    (a.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps({'fixture':str(a.out),'frames':a.frames,'dimensions':[a.width,a.height]}))
if __name__=='__main__':main()
