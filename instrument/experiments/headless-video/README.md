# Headless video diagnostic: host preparation only

**Status: real VPU execution disabled. This is not a deployable video receiver.**
Based on `cab703b15f3de4b968abc814af17558a13d9774a`, which already integrates the
experimental CPU NEON converter behind a default-off gate. Existing receiver,
decoder, display and build behavior are unchanged.

## What is tested

The fixture generator creates a short synthetic Baseline H.264 stream with
complete AUD-delimited AUs, no B frames and repeated parameter sets. Host
FFmpeg software-decodes it to I420; independent Python arithmetic creates each
expected full-frame RGB565LE checksum. These are fixtures, not VPU output.

The C diagnostic consumes framed AUs, runs the **unchanged real preflight**, then
uses an explicitly fake backend to replay the corresponding software-decoded
I420 frame into the **unchanged default-off converter**. Conversion finishes
synchronously before the fake releases and overwrites its source planes. The
owned RGB output is checked again after that overwrite. The diagnostic only
hashes/discards RGB; there is no display/link/guardian/FB/IPU path.

A scalar gather oracle also compares each complete output against the branch's
scalar SIMD-tail row arithmetic. That is useful scalar parity evidence, **not
execution of the NEON row or ARM runtime dispatcher**. This environment has no
ARM compiler/QEMU; those paths were not newly tested here. Earlier branch
results remain separate evidence rather than being relabeled as this run.

The fake has immediate and one-frame-delay modes. It accepts the first AU once,
allows conversion during that first submission, retains at most two accepted
AUs, and never submits EOS/drains on close. Delay mode intentionally reports
one undrained AU, reproducing an important interface limitation without claiming
that firmware always behaves this way. This is an interface model, not a mock
linked against the real hardware wrapper; it cannot validate wrapper SDK calls.

## Run locally

Requires existing Linux host tools: Python 3, a C11 compiler, FFmpeg with
libx264, `nm`, and `readelf`. No automatic installs or network access are used.
From the repository root:

```
python3 instrument/experiments/headless-video/run_host_tests.py
```

The script creates an exclusive temporary output directory and prints its
`report.json` path. Optional `--out NEW_DIRECTORY` selects a new output path;
optional `--asan` adds AddressSanitizer to UBSan (LeakSanitizer disabled and not
claimed). For fast reruns, `--fixture720 TRUSTED_FIXTURE_DIRECTORY` reuses a
previously generated fixture after checking its manifest hashes.

It builds only four allowlisted C sources: headless, preflight, pixels, and
scalar row. It does **not** compile/link `ic_decoder.c`, receiver, display,
libvpu, IPU, graphics, networking, or FFmpeg libraries. FFmpeg runs separately
and explicitly software-decodes only the locally generated synthetic input.
`O_PATH` pins input objects without invoking device opens; only regular files
are reopened for reading. Symlinks, FIFOs and nonregular input objects are
rejected. No device paths are supplied by the runner.

## Results and metrics

The JSON report contains every case, return code, counts, output hashes, stage
nanoseconds, compiler, source/binary SHA-256, and direct ELF dependency/symbol
inspection. It distinguishes AU records read, AUs accepted, frames converted,
frames released and undrained AUs. Failure modes do not retry/reopen.

Measured stages are preflight, fake submit, fixture I420 file read, CPU
conversion, parity oracle, checksum and total host wall time. Conversion also
reports nearest-rank p50/p95/p99/max with sample count. A 30-frame sample is a
correctness smoke test, not a statistically strong benchmark. Total includes
fixture I/O, checks, source poisoning and post-release hashing; sanitizer and
parity overhead are intentional. No stage is named VPU decode latency because
no VPU decoding occurs. Do not translate any metric into car FPS or speedup.

The package's small saved evidence is a snapshot; rerunning regenerates fixtures
and report. Exact x264 output can vary with FFmpeg/libx264 version, so each run
records the command/tool version and fixture hashes and regenerates its own
software-decoded expectations. Known local files are trusted input; the narrow
header preflight is not a full entropy decoder or hostile-stream boundary.

## Hardware gate

Read [VPU_SAFETY.md](VPU_SAFETY.md). A timeout or killing a process cannot prove
that DMA stopped. Missing exact target driver cleanup evidence is a blocker,
not a test to perform on a car. `--backend=vpu` fails with exit 78, and
`P7_ENABLE_REAL_VPU=1` fails compilation. No target binary/deployment command is
provided. The next hardware step remains conditional on resolving that
lifecycle contract and receiving separate authorization.

## Validation snapshot, 2026-11-04

- GCC 14.2.0 x86-64 strict warnings: 33 host cases pass under UBSan, then the
  same 33 pass under ASan+UBSan. Leak detection is disabled, not a leak-check
  pass. Both runs also pass the branch's original pixel suite.
- 30 distinct 1280x720 frames and 6 distinct 320x240 aspect-fit frames match
  independently computed Python RGB565LE checksums. One-frame-delay mode leaves
  one undrained AU as intended. Replayed 1- and 120-frame boundary containers
  pass; those boundary sequences reuse the six-frame fixture, not 120 distinct
  independently encoded frames.
- Submit/decode/callback/close faults stop at the expected counts. Header,
  shape, size, syntax, truncated/trailing fixture, raw corruption, symlink and
  FIFO cases reject as expected. A test-only build raises actual SIGINT and
  SIGTERM after the third fake decode; both stop between calls and close once.
  This deliberately does not simulate interrupting/hanging a real SDK call.
- Disabling the scalar parity oracle leaves all output hashes unchanged.
  Reports distinguish parity enabled from the number of frames actually
  checked, including zero-frame rejection cases.
- Dynamic-symbol/dependency checks find no VPU, display, IPU, graphics, device
  ioctl/mmap or dynamic-loader entry points. Runtime VPU selection and the
  compile-time VPU opt-in are rejected with their expected diagnostics.
- Original cab703b input Git blob hashes were rechecked after tests: unchanged.

Saved reports and fixture/tool manifests are in `evidence/`. Only source and
small text evidence belong in the archival change. Generated binaries and
large I420/H.264 fixtures are excluded and can be reproduced locally.
