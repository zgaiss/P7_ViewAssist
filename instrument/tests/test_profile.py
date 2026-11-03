#!/usr/bin/env python3
"""Compile actual receiver with deterministic clocks/I/O; no hardware."""
from pathlib import Path
import subprocess,tempfile
N=Path(__file__).resolve().parents[1]/'native'
T=Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='p7-profile-') as d:
 for enabled in (0,1):
  exe=Path(d)/f'profile-{enabled}'
  # Section GC discards unused receiver entry/session, avoiding fake SDK linkage.
  cmd=['cc','-std=c11','-Wall','-Wextra','-Werror','-pthread','-fsanitize=undefined','-ffunction-sections','-fdata-sections',f'-DIC_PROFILE={enabled}','-I',str(N),str(T/'test_profile.c'),str(N/'ic_protocol.c'),'-Wl,--gc-sections','-o',str(exe)]
  subprocess.run(cmd,check=True)
  r=subprocess.run([str(exe)],capture_output=True,text=True,check=True)
  assert 'runtime error:' not in r.stderr,r.stderr
  if enabled:
   assert 'stage=guardian_read_wait attempts=2 failed=1 invalid_clock=0 total_ns=26 max_ns=13' in r.stderr,r.stderr
   assert 'stage=guardian_display_call attempts=1 failed=0 invalid_clock=0 total_ns=17 max_ns=17' in r.stderr,r.stderr
  else:assert 'IC_PROFILE' not in r.stderr,r.stderr
  print(f'IC_PROFILE={enabled}:',r.stdout.strip())
print('PASS profiling default-off/enabled deterministic host tests (UBSan)')
