#!/usr/bin/env python3
"""Explicit host-only source allowlist; no device, remote, SDK or receiver path."""
import argparse, hashlib, json, os, platform, re, shutil, struct, subprocess, tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
NATIVE=ROOT/'instrument/native'
SOURCES=[HERE/'headless.c',NATIVE/'ic_preflight.c',NATIVE/'ic_pixels.c',NATIVE/'pixels_simd/row_scalar.c']

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',type=Path)
    ap.add_argument('--fixture720',type=Path,help='Reuse already generated trusted fixture directory')
    ap.add_argument('--asan',action='store_true',help='Supplemental ASan+UBSan; leak checking explicitly disabled')
    a=ap.parse_args()
    out=a.out or Path(tempfile.mkdtemp(prefix='p7-headless-host-'))
    if a.out: out.mkdir(parents=True,exist_ok=False)
    cc=os.environ.get('CC','cc')
    flags=['-std=c11','-O2','-g','-Wall','-Wextra','-Werror','-fno-sanitize-recover=all','-fsanitize='+('address,undefined' if a.asan else 'undefined'),'-I',str(NATIVE)]
    env=dict(os.environ)
    if a.asan:env['ASAN_OPTIONS']='detect_leaks=0:halt_on_error=1'
    report={'scope':'HOST_FIXTURE_REPLAY_NOT_VPU','hardware_run':False,'asan':a.asan,'leak_check':False,
        'machine':platform.machine(),'platform':platform.platform(),'compiler':subprocess.check_output([cc,'--version'],text=True).splitlines()[0],
        'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCES},'cases':[]}
    def run(cmd,expected=0):
        p=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,env=env,timeout=90)
        if p.returncode!=expected:
            raise RuntimeError(f'{cmd}: expected {expected}, got {p.returncode}\n{p.stdout}\n{p.stderr}')
        return p
    binary=out/'headless-host'
    build=[cc,*flags,*SOURCES,'-o',binary]
    report['build_command']=list(map(str,build));run(build)
    report['binary_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    nm=run(['nm','-u',binary]).stdout;elf=run(['readelf','-d',binary]).stdout
    (out/'undefined-symbols.txt').write_text(nm);(out/'dynamic-dependencies.txt').write_text(elf)
    if re.search(r'\b(vpu_\w*|IOGet\w*|IOFree\w*|ic_display\w*|ioctl|mmap|dlopen)\b',nm):raise AssertionError('Hardware/display API found')
    if re.search(r'lib(vpu|GAL|g2d|EGL|GLES|drm|avcodec)',elf,re.I):raise AssertionError('Unexpected hardware/media library')
    negative=subprocess.run([cc,*flags,'-DP7_ENABLE_REAL_VPU=1','-c',str(HERE/'headless.c'),'-o',str(out/'forbidden.o')],capture_output=True,text=True)
    assert negative.returncode!=0 and 'Real VPU backend disabled' in negative.stderr
    report['compile_time_vpu_gate']='rejected with expected diagnostic'
    fixtures=[]
    for width,height,n in [(1280,720,30),(320,240,6)]:
        path=a.fixture720 if width==1280 and a.fixture720 else out/f'fixture-{width}x{height}'
        if not (width==1280 and a.fixture720):run(['python3',HERE/'make_fixture.py',path,'--width',width,'--height',height,'--frames',n])
        manifest=json.loads((path/'manifest.json').read_text())
        assert (manifest['width'],manifest['height'],manifest['frames'])==(width,height,n)
        for name,entry in manifest['files'].items(): assert hashlib.sha256((path/name).read_bytes()).hexdigest()==entry['sha256']
        fixtures.append((path,manifest))
    def case(name,fixture=0,options=(),code=0,status='ok',override=None,expect=None):
        path,m=fixtures[fixture];paths={'aus':path/'fixture.p7au','yuv':path/'fixture.i420'}
        if override:paths.update(override)
        p=run([binary,'--backend=fixture','--aus',paths['aus'],'--yuv',paths['yuv'],*options],expected=code)
        d=json.loads(p.stdout);assert d['scope']=='HOST_FIXTURE_REPLAY_NOT_VPU' and d['status']==status,(name,d)
        if expect:
            for k,v in expect.items():assert d[k]==v,(name,k,d[k],v)
        report['cases'].append({'name':name,'exit':p.returncode,'result':d,'stderr':p.stderr})
        return d
    for idx,(path,m) in enumerate(fixtures):
        n=m['frames']
        d=case(f'continuous-{m["width"]}',fixture=idx,expect={'accepted_aus':n,'converted_frames':n,'released_frames':n,'undrained_aus':0,'close_attempts':1})
        assert d['hashes']==m['rgb_fnv1a64']
        d=case(f'delayed-no-eos-{m["width"]}',fixture=idx,options=['--delay-one'],expect={'accepted_aus':n,'converted_frames':n-1,'released_frames':n-1,'undrained_aus':1,'close_attempts':1})
        assert d['hashes']==m['rgb_fnv1a64'][:-1]
    for name,args,status,expect in [
      ('submit-first',['--fail-submit','1'],'fake_submit_error',{'accepted_aus':0,'converted_frames':0,'close_attempts':1}),
      ('submit-third',['--fail-submit','3'],'fake_submit_error',{'accepted_aus':2,'converted_frames':2,'close_attempts':1}),
      ('decode-first',['--fail-decode','1'],'fake_decode_or_callback_error',{'accepted_aus':1,'converted_frames':0,'undrained_aus':1,'close_attempts':1}),
      ('decode-third',['--fail-decode','3'],'fake_decode_or_callback_error',{'accepted_aus':3,'converted_frames':2,'undrained_aus':1,'close_attempts':1}),
      ('callback-reject',['--reject-callback','3'],'fake_decode_or_callback_error',{'converted_frames':3,'released_frames':3,'close_attempts':1}),
      ('close-failure',['--fail-close'],'fake_close_error',{'converted_frames':30,'close_attempts':1})]:
        case(name,options=args,code=1,status=status,expect=expect)
    # Closed host fixture mutation cases. These are not hostile H.264 validation.
    p=fixtures[0][0]; data=(p/'fixture.p7au').read_bytes(); raw=(p/'fixture.i420').read_bytes()
    mutations={'truncated-header':data[:19],'bad-magic':b'X'+data[1:],
      'too-many-frames':data[:16]+struct.pack('<I',121)+data[20:],
      'odd-width':data[:8]+struct.pack('<I',1279)+data[12:],
      'empty-au':data[:20]+struct.pack('<I',0)+data[24:],
      'oversize-au':data[:20]+struct.pack('<I',1024*1024+1)+data[24:],
      'truncated-au':data[:50], 'trailing-data':data+b'x',
      'dimension-size-check':data[:8]+struct.pack('<I',640)+data[12:]}
    statuses={'trailing-data':'trailing_fixture_data','empty-au':'fixture_record_error','oversize-au':'fixture_record_error','truncated-au':'fixture_record_error'}
    for name,b in mutations.items():
        f=out/(name+'.p7au');f.write_bytes(b)
        case(name,code=1,status=statuses.get(name,'fixture_header_error'),override={'aus':f})
    # Reach visible-dimension preflight check rather than raw length check.
    f=out/'wrong-dimensions.p7au';f.write_bytes(data[:8]+struct.pack('<I',640)+data[12:])
    y=out/'matching-wrong-size.i420';y.write_bytes(raw[:640*720*3//2*30])
    case('preflight-dimension-mismatch',code=1,status='preflight_rejected',override={'aus':f,'yuv':y},expect={'accepted_aus':0,'parity_checked_frames':0})
    baseline=case('no-parity-equivalence',options=['--no-parity'],expect={'converted_frames':30,'parity_checked_frames':0,'scalar_gather_parity_enabled':False})
    assert baseline['hashes']==fixtures[0][1]['rgb_fnv1a64']
    # Boundary containers reuse known valid 320x240 IDR-led fixture cycles.
    small,sm=fixtures[1]; sd=(small/'fixture.p7au').read_bytes(); sr=(small/'fixture.i420').read_bytes()
    records=[];at=20
    while at<len(sd):
        n=struct.unpack_from('<I',sd,at)[0];records.append(sd[at:at+12+n]);at+=12+n
    for count in (1,120):
        f=out/f'boundary-{count}.p7au';y=out/f'boundary-{count}.i420';size=320*240*3//2
        f.write_bytes(struct.pack('<8sIII',b'P7AU0001',320,240,count)+b''.join(records[i%6] for i in range(count)))
        y.write_bytes(b''.join(sr[(i%6)*size:(i%6+1)*size] for i in range(count)))
        result=case(f'frame-count-boundary-{count}',override={'aus':f,'yuv':y},expect={'declared_aus':count,'converted_frames':count,'released_frames':count,'parity_checked_frames':count})
        assert result['hashes']==[sm['rgb_fnv1a64'][i%6] for i in range(count)]
    signal_binary=out/'headless-host-signal-tests'
    run([cc,*flags,'-DP7_HOST_TESTING=1',*SOURCES,'-o',signal_binary])
    production=binary;binary=signal_binary
    for flag in ('--test-sigint','--test-sigterm'):
        case('cooperative '+flag,options=[flag],code=1,status='interrupted_between_calls',expect={'accepted_aus':3,'converted_frames':3,'released_frames':3,'close_attempts':1})
    binary=production
    report['signal_test_scope']='Test-only build raises real signal after third fake decode; proves handler/intercall stop only, never cancellation of an in-flight SDK call.'
    altered=bytearray(data);sps=altered.index(b'\x00\x00\x00\x01\x67');altered[sps+5]=100
    f=out/'high-profile.p7au';f.write_bytes(altered)
    case('high-profile-rejected',code=1,status='preflight_rejected',override={'aus':f},expect={'accepted_aus':0})
    f=out/'truncated.i420';f.write_bytes(raw[:-1])
    case('truncated-yuv',code=1,status='fixture_header_error',override={'yuv':f})
    changed=bytearray(raw);changed[0]^=255;f=out/'corrupted.i420';f.write_bytes(changed)
    case('checksum-detects-yuv-change',code=1,status='fake_decode_or_callback_error',override={'yuv':f})
    f=out/'symlink.p7au';f.symlink_to(p/'fixture.p7au')
    case('reject-symlink',code=1,status='fixture_header_error',override={'aus':f})
    f=out/'fifo.p7au';os.mkfifo(f)
    case('reject-fifo-without-opening-reader',code=1,status='fixture_header_error',override={'aus':f})
    for args,code in [(['--backend=vpu','--aus','/nonexistent','--yuv','/nonexistent'],78),(['--backend=unknown'],64),(['--fail-submit','not-a-number'],64)]:
        p=run([binary,*args],expected=code);report['cases'].append({'name':'CLI '+str(args),'exit':code,'stderr':p.stderr})
    pixels=out/'pixels-original-suite'
    run([cc,*flags,NATIVE/'ic_pixels.c',NATIVE/'tests/test_pixels.c','-o',pixels])
    report['original_pixels_suite']=run([pixels]).stdout
    report['result']='PASS';report['case_count']=len(report['cases'])
    report['limitations']=['Fixture backend does not decode H.264, call libvpu or model firmware timing.',
      'Actual unchanged preflight and default-off pixel converter are exercised; hardware decoder wrapper is not linked.',
      'Scalar gather parity is not NEON/ARM runtime parity. No target compiler or QEMU installed in this environment.',
      'Signal checks are cooperative between calls, never a hardware hang recovery claim.',
      'Host timings include checks/poisoning/I/O; never convert these into vehicle FPS or speedup.']
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'result':'PASS','cases':len(report['cases']),'report':str(out/'report.json')}))
if __name__=='__main__':main()
