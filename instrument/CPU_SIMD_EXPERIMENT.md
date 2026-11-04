# Experimental CPU SIMD integration

This is an **offline experiment**, not a vehicle-ready release. It integrates
the integer-exact CPU converter from experiment
`a1a9bb2c7a04fb1647d65cb70cfe05ea4f8e1754` into the receiver based on
`c2ebfa26572f23da1de41d965cf2109cb2f42d52`. No deployment, signing, device
execution or physical-display validation is part of this change.

## Gates and behavior

- The default remains the original scalar converter. The opt-in is
  `python3 instrument/build_local.py --experimental-cpu-simd` with the existing
  local SDK/path arguments. This is a build command, not a vehicle command.
- `IC_PIXELS_EXPERIMENTAL_NEON` defaults to `0`. Opt-in still requires the
  running Linux OS to report `HWCAP_NEON` through `getauxval(AT_HWCAP)` on every
  valid frame. A zero/missing report or unresolved weak symbol selects the
  original scalar loop and preserves the caller's `errno`.
- Only `pixels_simd/row_neon.c` uses `-mfpu=neon -mfloat-abi=softfp`. Every
  ordinary object and the dispatcher remain ARMEL `-mfloat-abi=soft`.
  Integer/pointer interfaces use base AAPCS. Enabled builds disable LTO.
- The build rejects hard-float objects and unexpected NEON attributes in
  ordinary objects. The source rejects unsupported enabled targets and
  accidentally NEON-compiled dispatch/converter objects.
- This opt-in does **not** enable `IC_VPU_ABI_CONFIRMED`, acknowledge runtime
  hardware warnings, change frame ownership or make the receiver asynchronous.
  The receiver, decoder, display, guardian and Android sources are unchanged.
- The gather helper is synchronous and does not retain source pointers. Input
  validation, absolute chroma coordinates, aspect fit, RGB565 little-endian
  bytes, zero-filled borders, scalar tails and error returns are preserved.

## Fresh cloud validation, 2026-11-04

CPU programs were linked against the saved target loader/libc/libgcc from the
authorized Mac kit and executed only under QEMU 10.0.13, Cortex-A9 emulation.
The saved libc identifies itself as glibc 2.19 despite its historical
`libc-2.13.so` filename. Cloud compilation used GCC 14.2 and archived Debian
2.19 headers/CRT, so this is **not** the target-SDK receiver build.

- Default-off ARM object: byte-identical to the verified c2ebfa original with
  the tested non-debug GCC flags, including explicit macro `0`.
- All 16,777,216 Y/U/V triples: NEON and scalar arithmetic equal an independent
  integer reference. Lengths 0–570, unaligned destination and exact-size tails
  pass. 1,000 deterministic randomized frame/crop/stride cases equal c2ebfa.
- Original pixel suite passes with NEON enabled, NEON unavailable and the
  default-off build. 2,284 protected-page cases pass.
- Instrumented dispatch confirms 315 NEON rows when supported and zero when
  the OS reports no NEON, the HWCAP query reports missing data, or a deliberately
  renamed weak symbol is unresolved. Invalid frames return before the probe.
- Direct, intentionally ungated NEON negative control faults with SIGILL when
  QEMU disables NEON. The gated production converter instead passes by scalar
  fallback. Compile-time ABI/flag rejection is verified by the intended error
  diagnostics, not merely a nonzero compiler exit.
- Disassembly contains VFP/NEON instructions only in the NEON row object.
  Tested target-linked CPU executables use the ARMEL loader and need no newer
  glibc symbol than `GLIBC_2.16`. A weak symbol does **not** promise loading on
  arbitrary older libc; final ELF version requirements still apply.
- Supplemental ASan+UBSan builds pass under QEMU with modern glibc, including
  enabled and fallback paths. Leak detection was disabled. Those modern-runtime
  binaries are not target-deployable and are separate from target-runtime tests.
- Host tests pass: actual Java protocol and Java-to-C Hello fixture; C protocol
  and pixels UBSan; seven build-planning checks; profile on/off; four reader
  lifecycle cases; blocked-stderr shutdown in both modes; five receiver
  **HOST-STUB** fault cases. These stubs do not prove physical restoration.
- Strict host GCC preflight test compilation hits an existing
  `maybe-uninitialized` warning in the test fixture. With only that warning
  downgraded, unchanged preflight tests pass UBSan, 10,000 incremental AUs,
  5,000 fuzz cases and 30 freshly encoded 1280×720 Baseline AUs. The original
  compile failure is retained in evidence rather than represented as a clean
  strict-GCC pass.

## Target-SDK compile-only result, 2026-11-04

An isolated Mac copy applied the seven-file integration patch (ZIP SHA256
`56b31e03ac46edaa385c9e64350f2daa2620a177365d57975bac1cf6e926c17a`)
and built the untouched c2ebfa baseline, default-off receiver and opt-in SIMD
receiver against the saved target SDK. All three compiled and linked with the
hardware ABI gate at **0**. No produced receiver was executed.

- After stripping debug information and build-id, the complete default-off ELF
  was byte-identical to the unpatched baseline. Original debug-file hashes
  differ and are not represented as identical.
- All 15 inspected objects used ARMEL/base AAPCS. The 51 observed VFP/NEON
  instructions in the enabled build were confined to the independent NEON row
  conversion function.
- Default and enabled receivers had the same direct dependencies. Seven saved
  target runtime libraries satisfied the static symbol/version requirements;
  there were zero unresolved strong symbols in that check.
- **Unresolved ELF metadata issue:** with the existing CRT/link order, the
  final receiver advertises ARM v4T rather than the source/object ARMv7 target.
  A separate diagnostic placing objects before CRT changed this attribute to
  ARMv7, but that ordering change has not been adopted: it can also change code
  layout/entry placement and requires its own review and validation. This
  branch preserves the original ordering and does not claim that final
  architecture attributes are correct. The issue applies to the baseline
  linking approach, not just the added NEON row.

This result supports **source-only experimental archival**, not a deployment
release. The metadata issue and physical validation gaps remain open.

## Remaining limitations

- No target CPU timing, FPS, end-to-end latency, contention, temperature or
  long-running stability result is established. QEMU is an instruction/ABI
  test here, not a performance model of the vehicle.
- Enabled conversion adds row scratch and call depth. GCC ARM stack-usage
  reports are 2,320 B for the converter, 1,728 B for gather, 112 B for the NEON
  row and 48 B for its scalar tail, versus 2,304 B for the unchanged default
  converter. These are per-function reports, not a complete receiver stack
  bound; target-compiler output and real thread headroom remain relevant.
- The saved target runtime is a historical snapshot. It does not prove that a
  currently running car has identical libraries, kernel, HWCAP or firmware.
- The existing framebuffer/foreground transition and guardian limitations
  remain unresolved. In particular, emulation cannot prove restoration after
  a kernel/driver hang or guardian failure. Do not interpret default-off SIMD
  integration or compile success as approval for full receiver deployment.
- No proprietary VPU SDK, target runtime libraries, private keys or generated
  receiver binaries belong in this source change.

For CPU-only reproducibility, see `tests/cpu_simd/README.md`. The build-planning
unit tests can be run without an SDK using
`python3 instrument/tests/test_cpu_simd_build.py`.
