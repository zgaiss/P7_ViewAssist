#!/usr/bin/env python3
"""Reproducible CPU-only validation using a saved, hash-pinned target runtime.
No downloads, installation, vehicle access, receiver hardware execution or deployment.
"""
from pathlib import Path
import argparse,hashlib,json,os,re,subprocess,resource,sys
P=Path(__file__).resolve().parent; R=P.parents[2]; N=R/'instrument/native'
parser=argparse.ArgumentParser(description=__doc__)
for option in ('toolchain','headers-sysroot','target-runtime','host-libs','output'):
    parser.add_argument('--'+option,required=True,type=Path)
a=parser.parse_args()
T=a.toolchain.resolve(); S=a.headers_sysroot.resolve(); V=a.target_runtime.resolve(); H=a.host_libs.resolve(); B=a.output.resolve()
if B.exists(): parser.error('--output must be a new, nonexisting directory')
if B == P or P in B.parents or B == N or N in B.parents: parser.error('--output cannot be inside test or native sources')
for directory in (T,S,V,H):
    if not directory.is_dir(): parser.error('Missing input directory: '+str(directory))
cc=T/'usr/bin/arm-linux-gnueabi-gcc'; qemu=T/'usr/bin/qemu-arm'
for executable in (cc,qemu,T/'usr/bin/arm-linux-gnueabi-readelf',T/'usr/bin/arm-linux-gnueabi-objdump',T/'usr/bin/arm-linux-gnueabi-nm'):
    if not executable.is_file() or not os.access(executable,os.X_OK): parser.error('Missing executable: '+str(executable))
versions=sorted((T/'usr/lib/gcc-cross/arm-linux-gnueabi').glob('*/crtbegin.o'))
if len(versions)!=1: parser.error('Expected exactly one GCC ARMEL installation under toolchain/usr/lib/gcc-cross/arm-linux-gnueabi')
G=versions[0].parent; C=S/'usr/lib/arm-linux-gnueabi'
for artifact in (G/'crtend.o',C/'crt1.o',C/'crti.o',C/'crtn.o',C/'libc_nonshared.a'):
    if not artifact.is_file(): parser.error('Missing link input: '+str(artifact))
provenance=json.loads((P/'provenance.json').read_text())
baseline_source=P/'baseline_c2ebfa/ic_pixels.c'; baseline_bytes=baseline_source.read_bytes()
if hashlib.sha1(b'blob '+str(len(baseline_bytes)).encode()+b'\0'+baseline_bytes).hexdigest() != '1cdd0d13a589c08a74097f6e6404c63e009a425a': parser.error('Fixed c2ebfa baseline Git blob mismatch')
if hashlib.sha256(baseline_bytes).hexdigest() != provenance['baseline_sha256']: parser.error('Baseline SHA256 mismatch')
B.mkdir(parents=True,exist_ok=False); O=B/'build'; O.mkdir(); (B/'logs').mkdir()
env={'PATH':'/usr/bin:/bin','LC_ALL':'C','LD_LIBRARY_PATH':str(H)}
qenv={'PATH':'/usr/bin:/bin','LC_ALL':'C'}
resource.setrlimit(resource.RLIMIT_CORE,(0,0))
expected={'ld-linux.so.3':'a53fa4ff1ca1c5d2042e4c8eda0fe70db8a5cd4284053f1eb4555d895821c36a','libc.so.6':'4c3c72b8a7a17b40304e2de17fb65865146acb32a8b018d9608ee1ccfb24e18a','libgcc_s.so.1':'fd9ccc15be619233395ea4987a8abdbea4bb80ed301ed67d5a3a10a3068a14f6'}
for name,sha in expected.items():
    if hashlib.sha256((V/'lib'/name).read_bytes()).hexdigest()!=sha:raise ValueError('Runtime hash mismatch: '+name)
records=[]
def run(label,cmd,accept=(0,),runtime=False,timeout=180):
    cmd=list(map(str,cmd));p=subprocess.run(cmd,capture_output=True,text=True,env=qenv if runtime else env,timeout=timeout)
    rec=dict(label=label,command=cmd,exit=p.returncode,accepted=list(accept),stdout=p.stdout,stderr=p.stderr)
    records.append(rec);(B/'results.json').write_text(json.dumps(records,indent=2)+'\n')
    (B/'logs'/(label+'.log')).write_text(json.dumps(cmd)+'\n'+p.stdout+p.stderr+'\nEXIT '+str(p.returncode)+'\n')
    print(label,p.returncode,flush=True)
    if p.returncode not in accept:raise RuntimeError(p.stdout+p.stderr)
    return p.stdout+p.stderr
flags=[f'--sysroot={S}','-march=armv7-a','-mfloat-abi=soft','-O2','-std=c11','-Wall','-Wextra','-Werror','-fno-lto','-fno-stack-protector','-fno-pie','-fstack-usage','-nostdinc','-isystem',G/'include','-isystem',S/'usr/include/arm-linux-gnueabi','-isystem',S/'usr/include','-I',N,'-I',P]
def compile(name,source,extra=(),accept=(0,),contains=None):
    out=O/(name+'.o');text=run('compile_'+name,[cc]+flags+list(extra)+['-c',source,'-o',out],accept)
    if contains and contains not in text:raise RuntimeError('Expected compiler gate not observed: '+name)
    if contains:
        records[-1]['required_diagnostics']=[contains]
        records[-1]['diagnostics_passed']=True
        (B/'results.json').write_text(json.dumps(records,indent=2)+'\n')
    return out
def link(name,objects,extra=()):
    out=O/name
    run('link_'+name,[cc,f'--sysroot={S}','-march=armv7-a','-mfloat-abi=soft','-nostdlib','-no-pie','-Wl,-z,noexecstack','-Wl,--dynamic-linker=/lib/ld-linux.so.3','-Wl,--build-id=sha1',C/'crt1.o',C/'crti.o',G/'crtbegin.o']+list(objects)+list(extra)+['-Wl,--start-group',V/'lib/libc.so.6',V/'lib/libgcc_s.so.1',C/'libc_nonshared.a',V/'lib/ld-linux.so.3','-Wl,--end-group',G/'crtend.o',C/'crtn.o','-o',out]);return out
def execute(label,binary,cpu='cortex-a9',args=(),accept=(0,),trace=False):
    command=[qemu,'-cpu',cpu,'-L',V]
    if trace:command+=['-strace']
    command += [V/'lib/ld-linux.so.3','--inhibit-cache','--library-path',V/'lib',binary]+list(args)
    text=run(label,command,accept,runtime=True)
    if label.startswith('exact_') and accept==(0,):
        required=['PASS row: 16777216 exhaustive YUV triples','PASS frames: 1000 deterministic']
    elif label=='exact_neon_off': required=['SKIP: OS does not report NEON; no NEON instruction executed']
    elif label.startswith('original_') or label=='syscall_audit_original': required=['720p PASS']
    elif label=='guard_rows_on': required=['PASS guard pages: 2284 cases']
    elif label=='direct_neon_on': required=['Direct NEON instruction probe passed']
    elif label=='direct_neon_off_sigill': required=['Illegal instruction']
    elif label.startswith('dispatch_'):
        required=['PASS integrated dispatch:','errno_preserved=1 invalid_frame_not_probed=1']
        required += ['gather_calls=315 neon_rows=315' if label=='dispatch_neon_on' else 'gather_calls=0 neon_rows=0']
    else: raise RuntimeError('Missing diagnostic expectations: '+label)
    for marker in required:
        if marker not in text: raise RuntimeError('Expected diagnostic not observed for '+label+': '+marker)
    records[-1]['required_diagnostics']=required
    records[-1]['diagnostics_passed']=True
    (B/'results.json').write_text(json.dumps(records,indent=2)+'\n')
    return text
off='cortex-a9,neon=off,vfp-d32=off'
baseline=compile('baseline',baseline_source)
default=compile('default',N/'ic_pixels.c')
explicit_off=compile('explicit_off',N/'ic_pixels.c',['-DIC_PIXELS_EXPERIMENTAL_NEON=0'])
if baseline.read_bytes()!=default.read_bytes() or baseline.read_bytes()!=explicit_off.read_bytes():raise RuntimeError('Default-off ARM object changed from baseline')
print('PASS default-off ARM object byte identity',flush=True)
reference=compile('reference',baseline_source,['-Dic_pixels_rgb565=ic_pixels_rgb565_reference'])
enabled=compile('enabled',N/'ic_pixels.c',['-DIC_PIXELS_EXPERIMENTAL_NEON=1'])
dispatch=compile('dispatch',N/'pixels_simd/dispatch_gather.c')
absent=compile('dispatch_absent',N/'pixels_simd/dispatch_gather.c',['-Dgetauxval=ic_test_absent_getauxval'])
scalar=compile('row_scalar',N/'pixels_simd/row_scalar.c')
neon=compile('row_neon',N/'pixels_simd/row_neon.c',['-mfloat-abi=softfp','-mfpu=neon'])
rename=[]
exact=compile('exact',P/'test_exact.c',rename+['-DP7_TEST_NEON=1'])
exact_scalar=compile('exact_scalar',P/'test_exact.c',rename)
original=compile('original',N/'tests/test_pixels.c')
guard=compile('guard',P/'guard_rows.c',rename)
direct=compile('direct',P/'direct_neon_probe.c',rename)
probe=compile('probe',P/'dispatch_probe.c')
simd=[enabled,dispatch,scalar,neon]
binaries={
    'exact_neon':link('exact_neon',simd+[reference,exact]),
    'exact_scalar':link('exact_scalar',simd+[reference,exact_scalar]),
    'original_enabled':link('original_enabled',simd+[original]),
    'original_default':link('original_default',[default,original]),
    'guard_rows':link('guard_rows',[guard,scalar,neon]),
    'direct_neon':link('direct_neon',[direct,scalar,neon]),
    'dispatch_probe':link('dispatch_probe',simd+[probe],['-Wl,--wrap=getauxval','-Wl,--wrap=ic_pixels_gather_neon','-Wl,--wrap=ic_pixels_row_neon']),
    'absent_probe':link('absent_probe',[enabled,absent,scalar,neon,probe],['-Wl,--wrap=getauxval','-Wl,--wrap=ic_pixels_gather_neon','-Wl,--wrap=ic_pixels_row_neon'])}
metadata_checks={}
def save_metadata(label,checks):
    metadata_checks[label]=checks
    records[-1]['metadata_checks']=checks
    (B/'results.json').write_text(json.dumps(records,indent=2)+'\n')
for name,obj in [('default',default),('enabled',enabled),('dispatch',dispatch),('row_scalar',scalar),('row_neon',neon)]:
    run('elf_'+name,[T/'usr/bin/arm-linux-gnueabi-readelf','-h','-A',obj])
    disassembly=run('disassembly_'+name,[T/'usr/bin/arm-linux-gnueabi-objdump','-d',obj])
    # Parse instruction lines, not symbols/comments: all unified ARM VFP/NEON
    # instruction mnemonics start with v. Unknown/empty output must not pass.
    mnemonics=re.findall(r'^\s*[0-9a-f]+:\s+[0-9a-f]{8}\s+([a-z][a-z0-9.]*)\b',disassembly,re.M)
    if not mnemonics: raise RuntimeError('No ARM instruction disassembly parsed: '+name)
    vector=sorted({m for m in mnemonics if m.startswith('v')})
    if name!='row_neon':
        if vector: raise RuntimeError('Unexpected VFP/NEON in baseline object '+name+': '+str(vector))
        save_metadata('disassembly_'+name,{'no_vfp_neon':True,'instruction_count':len(mnemonics)})
    else:
        required={'vld1','vmull','vqshrun','vst2'}
        missing=required-{m.split('.')[0] for m in mnemonics}
        if missing: raise RuntimeError('Missing required NEON instructions: '+str(sorted(missing)))
        save_metadata('disassembly_'+name,{'required_neon_mnemonics':sorted(required),'passed':True})
    run('symbols_'+name,[T/'usr/bin/arm-linux-gnueabi-nm','-u',obj])
for name,binary in binaries.items():
    elf=run('elf_binary_'+name,[T/'usr/bin/arm-linux-gnueabi-readelf','-h','-l','-d','-V','-A',binary])
    if not re.search(r'Class:\s+ELF32\b',elf) or not re.search(r'Machine:\s+ARM\b',elf) or 'little endian' not in elf:
        raise RuntimeError('Expected ARM32 little-endian ELF: '+name)
    interpreters=re.findall(r'Requesting program interpreter: ([^\]]+)',elf)
    if interpreters!=['/lib/ld-linux.so.3']: raise RuntimeError('Unexpected interpreter: '+name+' '+str(interpreters))
    if 'soft-float ABI' not in elf or re.search(r'hard[- ]?float|armhf|Tag_ABI_VFP_args:\s*VFP registers',elf,re.I):
        raise RuntimeError('Expected ARMEL/base AAPCS binary, no hardfloat: '+name)
    needed=re.findall(r'\(NEEDED\).*Shared library: \[([^\]]+)\]',elf)
    allowed={'libc.so.6','libgcc_s.so.1','ld-linux.so.3'}
    if not needed or set(needed)-allowed: raise RuntimeError('Unexpected DT_NEEDED: '+name+' '+str(needed))
    versions=set(re.findall(r'\bGLIBC_([A-Za-z0-9_.]+)',elf))
    if not versions or any(not re.fullmatch(r'\d+\.\d+(?:\.\d+)?',v) for v in versions):
        raise RuntimeError('Missing or unsupported GLIBC version requirements: '+name+' '+str(versions))
    maximum=max(tuple(map(int,v.split('.')))+(0,)*(3-len(v.split('.'))) for v in versions)
    if maximum>(2,19,0): raise RuntimeError('GLIBC requirement exceeds 2.19: '+name+' '+str(maximum))
    save_metadata('elf_binary_'+name,{'armel_loader':interpreters[0],'base_aapcs':True,'needed':needed,'max_glibc':'.'.join(map(str,maximum)),'passed':True})
execute('exact_neon_on',binaries['exact_neon'])
execute('exact_scalar_on',binaries['exact_scalar'])
execute('exact_scalar_off',binaries['exact_scalar'],off)
execute('exact_neon_off',binaries['exact_neon'],off,accept=(77,))
execute('original_enabled_on',binaries['original_enabled'])
execute('original_enabled_off',binaries['original_enabled'],off)
execute('original_default_on',binaries['original_default'])
execute('original_default_off',binaries['original_default'],off)
execute('guard_rows_on',binaries['guard_rows'])
execute('direct_neon_on',binaries['direct_neon'])
execute('direct_neon_off_sigill',binaries['direct_neon'],off,accept=(-4,132))
execute('dispatch_neon_on',binaries['dispatch_probe'])
execute('dispatch_neon_off',binaries['dispatch_probe'],off)
execute('dispatch_report_missing',binaries['dispatch_probe'],args=['missing'])
execute('dispatch_symbol_absent',binaries['absent_probe'],args=['absent'])
execute('dispatch_symbol_absent_off',binaries['absent_probe'],off,args=['absent'])
execute('syscall_audit_original',binaries['original_enabled'],trace=True)
compile('reject_neon_hardfloat',N/'pixels_simd/row_neon.c',['-ffreestanding','-mfloat-abi=hard','-mfpu=neon'],accept=(1,),contains='Hard-float ABI is incompatible')
compile('reject_neon_without_flag',N/'pixels_simd/row_neon.c',['-ffreestanding'],accept=(1,),contains='This file must be separately compiled for ARM32 NEON')
compile('reject_dispatch_neon',N/'pixels_simd/dispatch_gather.c',['-mfloat-abi=softfp','-mfpu=neon'],accept=(1,),contains='Compile the dispatcher with baseline flags')
compile('reject_main_neon',N/'ic_pixels.c',['-DIC_PIXELS_EXPERIMENTAL_NEON=1','-mfloat-abi=softfp','-mfpu=neon'],accept=(1,),contains='Compile ic_pixels.c with baseline flags')
manifest={'baseline_commit':provenance['baseline_commit'],'test_source_sha256':{str(p.relative_to(P)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(P.rglob('*')) if p.is_file()},'tool_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (cc,qemu)},'target_runtime_sha256':expected,'source_sha256':{str(p.relative_to(R)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(N.rglob('*')) if p.is_file()},'binary_sha256':{name:hashlib.sha256(p.read_bytes()).hexdigest() for name,p in binaries.items()},'metadata_checks':metadata_checks,'default_off_arm_object_identical':True,'command_count':len(records),'hardware_run':False,'qemu_performance_is_not_target_performance':True}
(B/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('ALL INTEGRATED ARM CHECKS PASSED',len(records))
