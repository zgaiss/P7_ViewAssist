#!/usr/bin/env python3
"""Host-only build-planning regressions; no SDK, link, device or network access."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

BUILD = Path(__file__).resolve().parents[1] / 'build_local.py'
spec = importlib.util.spec_from_file_location('build_local', BUILD)
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class CpuSimdBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.native = self.root / 'native'
        self.native.mkdir()
        for name in ['ic_pixels.c', 'ic_receiver.c', 'ic_decoder.c']:
            (self.native / name).write_text('/* planning fixture */\n')
        simd = self.native / 'pixels_simd'
        simd.mkdir()
        for name in ['dispatch_gather.c', 'row_scalar.c', 'row_neon.c']:
            (simd / name).write_text('/* planning fixture */\n')

    def plan(self, hardware=False, simd=False):
        return build.target_build_plan(
            self.root / 'ndk', '/resource', self.root / 'sysroot',
            self.root / 'sdk', self.root / 'live', self.native,
            self.root / 'out', hardware, simd)

    def test_default_is_baseline_single_step_scalar(self):
        plan = self.plan()
        self.assertEqual(plan['objects'], [])
        self.assertEqual(plan['sources'], sorted(self.native.glob('*.c')))
        args = list(map(str, plan['link_command']))
        self.assertIn('-mfloat-abi=soft', args)
        self.assertIn('-DIC_VPU_ABI_CONFIRMED=0', args)
        self.assertFalse(any('pixels_simd' in arg or 'EXPERIMENTAL_NEON' in arg
                             or arg == '-fno-lto' for arg in args))
        self.assertFalse(any(arg.endswith('.o') and '/out/' in arg for arg in args))

    def test_enabled_separate_objects_and_neon_isolation(self):
        plan = self.plan(simd=True)
        self.assertEqual(len(plan['objects']), 6)
        for entry in plan['objects']:
            flags = list(map(str, entry['flags']))
            self.assertIn('-fno-lto', flags)
            self.assertIn('-DIC_PIXELS_EXPERIMENTAL_NEON=1', flags)
            self.assertIn('-c', entry['command'])
            self.assertEqual(entry['command'].count(entry['source']), 1)
            self.assertNotIn('-mfloat-abi=hard', flags)
            self.assertIn('--target=arm-linux-gnueabi', flags)
            if entry['source'].name == 'row_neon.c':
                self.assertIn('-mfloat-abi=softfp', flags)
                self.assertIn('-mfpu=neon', flags)
                self.assertNotIn('-mfloat-abi=soft', flags)
            else:
                self.assertIn('-mfloat-abi=soft', flags)
                self.assertNotIn('-mfloat-abi=softfp', flags)
                self.assertNotIn('-mfpu=neon', flags)
        link = list(map(str, plan['link_command']))
        self.assertIn('-fno-lto', link)
        self.assertIn('-mfloat-abi=soft', link)
        self.assertNotIn('-mfpu=neon', link)
        self.assertFalse(any(arg.endswith('.c') for arg in link))
        for entry in plan['objects']:
            self.assertIn(str(entry['object']), link)

    def test_hardware_gate_is_independent(self):
        for hardware in (False, True):
            for simd in (False, True):
                plan = self.plan(hardware, simd)
                self.assertEqual(bool(plan['objects']), simd)
                self.assertIn('-DIC_VPU_ABI_CONFIRMED=' + str(int(hardware)),
                              plan['link_command'])

    def test_link_contract_preserved(self):
        for simd in (False, True):
            link = list(map(str, self.plan(simd=simd)['link_command']))
            for arg in ['--target=arm-linux-gnueabi', '-march=armv7-a',
                        '-Wl,--dynamic-linker=/lib/ld-linux.so.3',
                        '-Wl,-z,noexecstack', '-Wl,--allow-shlib-undefined']:
                self.assertIn(arg, link)
            for name in ['crt1.o', 'crti.o', 'crtn.o', 'libvpu.so.4',
                         'libc-2.13.so', 'libpthread-2.13.so', 'libc_nonshared.a']:
                self.assertEqual(sum(Path(arg).name == name for arg in link), 1)
            self.assertFalse(any('gnueabihf' in arg for arg in link))

    def test_soft_and_softfp_abi_accepted(self):
        build.check_armel_abi('Machine: ARM\nFlags: 0x5000200, Version5 EABI, soft-float ABI')
        build.check_armel_abi('Machine: ARM\nTag_Advanced_SIMD_arch: NEONv1', allow_neon=True)

    def test_hard_float_and_unguarded_neon_rejected(self):
        for detail in ['hard-float ABI', 'Tag_ABI_VFP_args: VFP registers',
                       'EF_ARM_ABI_FLOAT_HARD', 'AAPCS VFP']:
            with self.assertRaises(ValueError):
                build.check_armel_abi('Machine: ARM\n' + detail, allow_neon=True)
        with self.assertRaises(ValueError):
            build.check_armel_abi('Machine: ARM\nTag_Advanced_SIMD_arch: NEONv1')
        with self.assertRaises(ValueError):
            build.check_armel_abi('Machine: AArch64')

    def test_help_exposes_independent_opt_in(self):
        result = subprocess.run([sys.executable, str(BUILD), '--help'],
                                text=True, capture_output=True, check=True)
        self.assertIn('--experimental-cpu-simd', result.stdout)
        self.assertIn('--native-abi-confirmed', result.stdout)


if __name__ == '__main__':
    unittest.main()
