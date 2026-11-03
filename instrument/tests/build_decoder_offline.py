#!/usr/bin/env python3
"""Host preflight test + ARMEL objects only. Never contacts a device.
SDK and glibc headers remain external. No proprietary SDK files are copied.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROBE = ROOT.parent / 'ic-vdec-probe-20261102'
DEFAULT_NDK = Path('/Users/liuchang/Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe-root', type=Path, default=DEFAULT_PROBE)
    parser.add_argument('--ndk-bin', type=Path, default=DEFAULT_NDK)
    parser.add_argument('--host-cc', default='clang')
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--asan', action='store_true', help='Also enable ASan (some host sandboxes hang before main)')
    args = parser.parse_args()
    out = ROOT / 'instrument/build/decoder'
    out.mkdir(parents=True, exist_ok=True)
    native = ROOT / 'instrument/native'
    commands = []

    def run(cmd, timeout=60):
        cmd = list(map(str, cmd))
        commands.append(cmd)
        result = subprocess.run(cmd, text=True, capture_output=True, timeout=timeout)
        print(result.stdout + result.stderr, end='')
        result.check_returncode()
        return result.stdout

    resource = run([args.ndk_bin / 'clang', '-print-resource-dir']).strip()
    sdk = args.probe_root / 'vendor/imx-vpu-sdk/native-evidence/source-5.4.16/vpu'
    sysroot = args.probe_root / 'vendor/armel-glibc-headers/sysroot'
    common = [args.ndk_bin / 'clang', '--target=arm-linux-gnueabi', '-march=armv7-a', '-mfloat-abi=soft',
              '-nostdinc', '-isystem', resource + '/include',
              '-isystem', sysroot / 'usr/include/arm-linux-gnueabi', '-isystem', sysroot / 'usr/include',
              '-isystem', sdk, '-I' + str(native), '-O2', '-g', '-std=c11', '-Wall', '-Wextra',
              '-Werror', '-Wpedantic', '-fno-stack-protector', '-fno-pie']
    objects = []
    for name in ['ic_decoder', 'ic_preflight']:
        obj = out / (name + '.o')
        run(common + ['-c', native / (name + '.c'), '-o', obj])
        run([args.ndk_bin / 'llvm-readelf', '-h', obj])
        objects.append({'path': str(obj.relative_to(ROOT)), 'sha256': hashlib.sha256(obj.read_bytes()).hexdigest()})
    symbols = run([args.ndk_bin / 'llvm-nm', '-u', out / 'ic_decoder.o'])
    assert 'vpu_DecStartOneFrame' in symbols and 'Reset' not in symbols and 'avcodec' not in symbols
    test = out / 'test_preflight'
    run([args.host_cc, '-std=c11', '-O1', '-g', '-Wall', '-Wextra', '-Werror', '-pedantic',
         '-fsanitize=' + ('address,undefined' if args.asan else 'undefined'), '-I' + str(native), native / 'ic_preflight.c',
         ROOT / 'instrument/tests/test_preflight.c', '-o', test])
    host = run([test] + ([args.fixture.resolve()] if args.fixture else []), timeout=30)
    report = {'objects': objects, 'sdk_header': str(sdk / 'vpu_lib.h'),
              'sdk_header_sha256': hashlib.sha256((sdk / 'vpu_lib.h').read_bytes()).hexdigest(),
              'host_result': host, 'sanitizers': ['undefined'] + (['address'] if args.asan else []), 'commands': commands,
              'device_access': False, 'hardware_decode_tested': False}
    (out / 'offline-validation.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
