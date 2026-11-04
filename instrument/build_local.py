#!/usr/bin/env python3
"""Offline build/test only. No adb, RPC, remote network or vehicle execution.
Host-stub process tests use loopback TCP only (127.0.0.1).
External SDK/target libraries remain outside this repository (license/provenance).
Outputs use an exclusive new build directory; no existing results overwritten.
"""
from pathlib import Path
import argparse, hashlib, json, re, subprocess, sys, tempfile, zipfile
ROOT=Path(__file__).resolve().parents[1]
HOME=Path.home()
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def target_build_plan(ndk_bin, resource, sysroot, header, live, native, out,
                      native_abi_confirmed=False, experimental_cpu_simd=False):
    """Pure command planning: default keeps the baseline single-step scalar build."""
    crt=sysroot/'usr/lib/arm-linux-gnueabi'
    binary=out/'p7-ic-receiver-armel'
    sources=sorted(native.glob('*.c'))
    common=[ndk_bin/'clang','--target=arm-linux-gnueabi','-march=armv7-a','-mfloat-abi=soft','-nostdinc','-isystem',resource+'/include','-isystem',sysroot/'usr/include/arm-linux-gnueabi','-isystem',sysroot/'usr/include','-isystem',header,'-I',native,'-O2','-g','-std=c11','-DIC_VPU_ABI_CONFIRMED='+str(int(native_abi_confirmed)),'-Wall','-Wextra','-Werror','-Wno-extra-semi','-fno-stack-protector','-fno-pie']
    link=['-nostdlib','-fuse-ld=lld','-Wl,-no-pie','-Wl,-z,noexecstack','-Wl,--dynamic-linker=/lib/ld-linux.so.3','-Wl,--build-id=sha1','-Wl,--allow-shlib-undefined',crt/'crt1.o',crt/'crti.o']
    libraries=['-Wl,--start-group',live/'usr/lib/libvpu.so.4',*[live/'lib'/x for x in ['libc-2.13.so','libpthread-2.13.so','libdl-2.13.so','librt-2.13.so','libgcc_s.so.1']],crt/'libc_nonshared.a','-Wl,--end-group',crt/'crtn.o','-o',binary]
    objects=[]
    if experimental_cpu_simd:
        simd=native/'pixels_simd'
        sources += [simd/'dispatch_gather.c',simd/'row_scalar.c',simd/'row_neon.c']
        common += ['-DIC_PIXELS_EXPERIMENTAL_NEON=1','-fno-lto']
        for source in sources:
            flags=list(common)
            if source == simd/'row_neon.c':
                flags[flags.index('-mfloat-abi=soft')]='-mfloat-abi=softfp'
                flags += ['-mfpu=neon']
            obj=out/'objects'/source.relative_to(native).with_suffix('.o')
            objects.append({'source':source,'object':obj,
                            'flags':flags[1:], 'command':flags+['-c',source,'-o',obj]})
        inputs=[entry['object'] for entry in objects]
    else:
        inputs=sources
    return {'sources':sources,'objects':objects,'binary':binary,
            'link_command':common+link+inputs+libraries}

def check_armel_abi(report, allow_neon=False):
    """Reject hard-float calling conventions; softfp NEON keeps ARMEL arguments."""
    if not re.search(r'Machine:\s+ARM\b',report):
        raise ValueError('Expected an ARM ELF object or binary')
    if any(x in report for x in ['hard-float ABI','EF_ARM_ABI_FLOAT_HARD','VFP registers','AAPCS VFP']):
        raise ValueError('Hard-float calling convention is not compatible with ARMEL')
    if not allow_neon and ('Advanced_SIMD' in report or 'NEON' in report):
        raise ValueError('NEON instructions are restricted to row_neon.o')

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--experiment',type=Path,default=ROOT.parent/'ic-vdec-probe-20261102')
    ap.add_argument('--ndk-bin',type=Path,default=HOME/'Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin')
    ap.add_argument('--java-home',type=Path,default=Path('/opt/homebrew/opt/openjdk'))
    ap.add_argument('--android-tools',type=Path,default=HOME/'WorkBuddy/2026-10-10-17-55-18/tools/sdk')
    ap.add_argument('--android-jar',type=Path,default=HOME/'Library/Android/sdk/platforms/android-37.0/android.jar',help='Official Android platform stub jar (not Robolectric android-all)')
    ap.add_argument('--native-abi-confirmed',action='store_true',help='Enable hardware calls for reviewed 5.4.16 ABI; still requires runtime explicit acknowledgements')
    ap.add_argument('--experimental-cpu-simd',action='store_true',help='Opt in to runtime-gated CPU NEON conversion; independent of the VPU hardware ABI gate')
    ap.add_argument('--android',action='store_true',help='Also compile resources/Java/dex and assemble UNSIGNED APK')
    a=ap.parse_args()
    base=ROOT/'instrument/build';base.mkdir(parents=True,exist_ok=True)
    out=Path(tempfile.mkdtemp(prefix='local-',dir=base)); log=out/'commands.log'
    def run(cmd):
        cmd=[str(x) for x in cmd]
        p=subprocess.run(cmd,capture_output=True,text=True)
        with log.open('a') as f:f.write(json.dumps(cmd,ensure_ascii=False)+'\n'+p.stdout+p.stderr+'\nEXIT:'+str(p.returncode)+'\n')
        print(p.stdout+p.stderr,end='')
        if p.returncode:raise RuntimeError(f'Command failed; inspect {log}')
        return p.stdout
    native=ROOT/'instrument/native'; tests=ROOT/'instrument/tests'
    host=out/'test_protocol';javaclasses=out/'java-tests';javaclasses.mkdir()
    java=a.java_home/'bin/java';javac=a.java_home/'bin/javac'
    run([javac,'--release','11','-d',javaclasses,ROOT/'src/com/xiaopeng/qa/camera/ICLinkProtocol.java',tests/'ICLinkProtocolTest.java'])
    run([java,'-cp',javaclasses,'com.xiaopeng.qa.camera.ICLinkProtocolTest',out/'hello.bin'])
    run(['cc','-std=c11','-Wall','-Wextra','-Werror','-fsanitize=undefined','-I',native,native/'ic_protocol.c',tests/'test_protocol.c','-o',host])
    run([host,out/'hello.bin'])
    run(['cc','-std=c11','-Wall','-Wextra','-Werror','-fsanitize=undefined','-I',native,native/'ic_preflight.c',tests/'test_preflight.c','-o',out/'test_preflight'])
    run([out/'test_preflight'])
    run(['cc','-std=c11','-Wall','-Wextra','-Werror','-fsanitize=undefined','-I',native,native/'ic_pixels.c',native/'tests/test_pixels.c','-o',out/'test_pixels'])
    run([out/'test_pixels'])
    run([sys.executable,tests/'test_cpu_simd_build.py'])
    guardian_output=run([sys.executable,tests/'test_receiver_offline.py'])
    guardian_report=Path(guardian_output.strip().split('guardian host tests PASS: ')[-1])/'report.json'
    exp=a.experiment.resolve();sysroot=exp/'vendor/armel-glibc-headers/sysroot';live=exp/'vendor/instrument-20261103';header=exp/'vendor/imx-vpu-sdk/native-evidence/source-5.4.16/vpu'
    manifest=json.loads((live/'MANIFEST.json').read_text())
    for entry in manifest['files']:
        if sha(live/entry['path'])!=entry['sha256']:raise ValueError('External library integrity mismatch')
    resource=run([a.ndk_bin/'clang','-print-resource-dir']).strip()
    plan=target_build_plan(a.ndk_bin,resource,sysroot,header,live,native,out,
                           a.native_abi_confirmed,a.experimental_cpu_simd)
    sources=plan['sources'];binary=plan['binary'];object_evidence=[]
    for entry in plan['objects']:
        obj=entry['object'];obj.parent.mkdir(parents=True,exist_ok=True)
        run(entry['command'])
        abi=run([a.ndk_bin/'llvm-readelf','-h','-A',obj])
        check_armel_abi(abi,allow_neon=entry['source'].name=='row_neon.c')
        abi_path=obj.with_suffix('.elf.txt');abi_path.write_text(abi)
        object_evidence.append({'source':str(entry['source'].relative_to(ROOT)),
                                'source_sha256':sha(entry['source']),
                                'object':str(obj),'sha256':sha(obj),
                                'flags':[str(x) for x in entry['flags']],
                                'abi_report':str(abi_path)})
    run(plan['link_command'])
    elf=run([a.ndk_bin/'llvm-readelf','-h','-l','-d','-V','-A',binary]);(out/'receiver-elf.txt').write_text(elf)
    check_armel_abi(elf,allow_neon=a.experimental_cpu_simd)
    assert 'Machine:                           ARM' in elf and '/lib/ld-linux.so.3' in elf and 'libvpu.so.4' in elf
    assert all(x not in elf.lower() for x in ['android','libavcodec','libavutil','/users/'])
    result={'binary':str(binary),'bytes':binary.stat().st_size,'sha256':sha(binary),'abi_enabled':a.native_abi_confirmed,'hardware_run':False,'source_sha256':{str(p.relative_to(ROOT)):sha(p) for p in sources+sorted(native.glob('*.h'))},'external_header_sha256':sha(header/'vpu_lib.h'),'offline_tests':'Java protocol, cross-language handshake, C protocol IO/bounds, preflight, pixels, CPU SIMD build planning, HOST-STUB guardian fault cases','guardian_host_report':str(guardian_report)}
    result['experimental_cpu_simd']={'enabled':a.experimental_cpu_simd,'gate':'IC_PIXELS_EXPERIMENTAL_NEON','default':False,'runtime_dispatch':'OS HWCAP NEON required' if a.experimental_cpu_simd else 'baseline scalar only','objects':object_evidence,'link_command':[str(x) for x in plan['link_command']]}
    tracked_inputs=sorted(native.rglob('*.c'))+sorted(native.rglob('*.h'))+sorted(native.rglob('test*.c'))+sorted(tests.glob('*.*'))+sorted((ROOT/'src').rglob('*.java'))+sorted((ROOT/'res').rglob('*.xml'))+[ROOT/'AndroidManifest.xml',Path(__file__)]
    result['source_sha256']={str(p.relative_to(ROOT)):sha(p) for p in tracked_inputs}
    if a.android:
        tools=a.android_tools;jar=a.android_jar;aapt=tools/'bin/aapt2'
        compiled=out/'resources.zip';resapk=out/'resources.apk';generated=out/'generated';generated.mkdir();classes=out/'classes';classes.mkdir();dex=out/'dex';dex.mkdir()
        run([aapt,'compile','--dir',ROOT/'res','-o',compiled])
        run([aapt,'link','-I',jar,'--manifest',ROOT/'AndroidManifest.xml','--java',generated,'-o',resapk,compiled])
        javafiles=sorted((ROOT/'src').rglob('*.java'))+sorted(generated.rglob('*.java'))
        run([javac,'--release','11','-encoding','UTF-8','-cp',jar,'-d',classes,*javafiles])
        run([java,'-cp',tools/'r8.jar','com.android.tools.r8.D8','--lib',jar,'--min-api','29','--output',dex,*sorted(classes.rglob('*.class'))])
        apk=out/'P7_ViewAssist-instrument-experimental-unsigned.apk'
        with zipfile.ZipFile(resapk) as source,zipfile.ZipFile(apk,'w',zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():target.writestr(item,source.read(item.filename))
            for p in dex.glob('*.dex'):target.write(p,p.name)
        result['unsigned_apk']={'path':str(apk),'bytes':apk.stat().st_size,'sha256':sha(apk),'signed':False,'installed':False}
    (out/'manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
