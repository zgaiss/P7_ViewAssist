# Reproducible CPU SIMD checks

These tests validate only CPU pixel conversion under ARM user-mode QEMU. They do
not build or run the receiver, decoder or display, contact hardware, install
software, download anything, deploy, or measure target performance. No runtime
libraries, proprietary libraries or generated executables belong in this folder.
Repository LICENSE applies; upstream copyright: Hades-Heyds,
https://github.com/Hades-Heyds/P7_ViewAssist.

## Inputs and invocation

Use Python 3 on Linux and an already available GCC ARMEL toolchain (including
qemu-arm, readelf, objdump and nm), glibc 2.19 development sysroot, saved exact-kit
runtime, and directory containing the toolchain's host shared libraries:

```sh
python3 instrument/tests/cpu_simd/run_qemu.py \
  --toolchain /path/to/toolchain \
  --headers-sysroot /path/to/glibc219/sysroot \
  --target-runtime /path/to/P7-QEMU-minimal-kit-a1a9bb-20261104/runtime \
  --host-libs /path/to/host-libs \
  --output /path/to/new-results-directory
```

All options are required. Output must not exist, and cannot be inside these test
sources or native sources. The GCC installation is discovered under
`usr/lib/gcc-cross/arm-linux-gnueabi` and must have exactly one version. The
sysroot must provide ARMEL CRT objects, headers and libc_nonshared.a. Tests retain
assertions. Runtime ld-linux.so.3, libc.so.6 and libgcc_s.so.1 are checked against
fixed SHA256 hashes in the runner. A different runtime is intentionally rejected.
The runner invokes the saved target loader explicitly with `--inhibit-cache` and
`--library-path`; guest runs receive a clean environment with no inherited LD_*
or QEMU_* settings. Host library overrides apply only to compiler/binutils.

Output contains build products, per-command logs, results.json with complete
commands and diagnostics, and manifest.json with source/runtime/tool/binary
hashes. Expected compiler failures require their intended diagnostic text.
Unexpected failures stop the suite; retained results identify the last command.
Never treat a partially populated output directory as a passing run. Successful
completion prints `ALL INTEGRATED ARM CHECKS PASSED` and writes manifest.json.

## Source provenance and fixed reference

`baseline_c2ebfa/ic_pixels.c` is an **unmodified, fixed scalar reference**, not the
current production implementation. Its Git blob SHA1 is
`1cdd0d13a589c08a74097f6e6404c63e009a425a`, verified before copying against
`source-base-manifest.json` for commit
`c2ebfa26572f23da1de41d965cf2109cb2f42d52`. The fixture bytes were sourced from the
prior saved validation source tree, where that identical blob also appears at
commit `a1a9bb2c7a04fb1647d65cb70cfe05ea4f8e1754`. The runner verifies the fixed
blob and SHA256 on every run, independently of git checkout availability.
The filename remains ic_pixels.c so ELF file symbols do not invalidate the
byte-for-byte default-off object comparison.

`test_exact.c` comes from
https://github.com/zgaiss/P7_ViewAssist/blob/a1a9bb2c7a04fb1647d65cb70cfe05ea4f8e1754/instrument/experiments/cpu-exact-simd/prototype/test_exact.c.
Modifications: include the actual integrated SIMD header using a relative path,
replace prototype p7_rgb565_row_* names with ic_pixels_row_* names, and clarify
the scalar-row completion text (the integrated frame dispatcher is also tested).
The deterministic exhaustive row and randomized-frame algorithms are unchanged.

`guard_rows.c` and `direct_neon_probe.c` were authored during the prior CPU-only
QEMU validation, not taken from a pinned upstream commit. Changes here are only
the actual integrated header and row symbol names. `dispatch_probe.c` is copied
unchanged from this integration's validation/arm/dispatch_probe.c. The runner is
adapted from validation/arm/run_qemu_integration.py: parameterized input roots,
exclusive output, input checks, fixed bundled baseline, direct integrated test
symbols, and diagnostic verification. Original source hashes and the original
baseline manifest entry are recorded in provenance.json.

## Coverage

- Exact default and explicitly disabled ARM object identity versus fixed baseline.
- All 16,777,216 YUV triples for each row implementation, lengths 0..570,
  unaligned output, deterministic 1,000-frame comparisons with the scalar baseline.
- Original pixel regression tests in enabled and default configurations, with
  NEON on and off; scalar-row harness also executes with NEON off.
- 2,284 protected-page cases: input/output boundaries, tails, odd alignment,
  canaries and unchanged sources.
- Dispatch instrumentation: real NEON, NEON disabled, missing HWCAP report and
  absent weak getauxval symbol; checks errno preservation and invalid-frame order.
- Intentional ungated direct-NEON negative control must report SIGILL with NEON
  disabled. The gated exhaustive NEON test must return 77 with skip text.
- Hard-float, missing NEON flags, NEON dispatcher and NEON main compilation gates
  must reject the build with the specific expected diagnostics.
- ELF attributes, dynamic dependencies, disassembly, unresolved symbols and one
  syscall trace are retained for review. A successful QEMU run is not receiver,
  playback, vehicle-safety, ABI-on-device or performance acceptance.

## Enforced object and binary checks

The runner parses disassembled ARM instructions and rejects any VFP/NEON
mnemonic in default, enabled main, dispatcher, or scalar-row objects. It requires
vld1, vmull, vqshrun and vst2 in the separately compiled NEON object. Every linked
binary must be ELF32 little-endian ARM with `/lib/ld-linux.so.3`, soft-float/base
AAPCS rather than hard-float/armhf, only libc.so.6/libgcc_s.so.1/ld-linux.so.3
DT_NEEDED entries, and numeric GLIBC requirements no newer than 2.19. Empty or
unrecognized metadata fails closed. Parsed checks are saved in results.json and
manifest.json; full tool output remains available for review.

The absent weak getauxval symbol test deliberately compiles a dispatcher variant
with getauxval renamed to an unresolved weak test symbol. This tests its fallback
control flow against the pinned saved runtime. It does **not** demonstrate
compatibility with arbitrary older libc releases or their versioned symbols.
