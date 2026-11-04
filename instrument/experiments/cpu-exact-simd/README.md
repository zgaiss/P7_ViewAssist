# Exact-mapping CPU SIMD experiment

## Result and status

This is an isolated, unintegrated CPU conversion experiment, not hardware playback, a receiver build, deployment, or target-performance acceptance. The existing VPU decoder and framebuffer guardian are unchanged. The prototype is never added to the receiver's normal native source list.

The scalar gather path preserves the exact current RGB565LE conversion contract. Its host run passed all 16,777,216 Y/U/V triples, lengths 0..570 with exact-size buffers and unaligned output, 1,000 deterministic randomized images/crops/strides against the actual baseline, and the existing invalid-input/alias/fit/color suite under ASan+UBSan. Leak detection was explicitly disabled because this executor does not support LeakSanitizer; this is not a leak-check pass.

The separate ARM32 NEON row implementation has NOT been compiled or executed here. Its source uses widened 32-bit arithmetic, exact coefficients and rounding, explicit little-endian stores, scalar tails and a runtime HWCAP_NEON gate. Host correctness of its scalar counterpart is not proof of NEON implementation correctness. Do not enable this in a deployed receiver before target-compatible compilation, NEON execution tests and integration review.

## Pinned source

Repository: https://github.com/zgaiss/P7_ViewAssist

Baseline under investigation: c2ebfa26572f23da1de41d965cf2109cb2f42d52 (experiment/fail-closed-fps-20261103).

- Pixel converter: instrument/native/ic_pixels.c, Git blob 1cdd0d13a589c08a74097f6e6404c63e009a425a.
- Pixel contract: instrument/native/ic_pixels.h, Git blob 66dbdc5a2e79ffc9339d58d3498c4fb8b4dc1e87.
- Frame contract: instrument/native/ic_decoder.h, Git blob 728088f401dad00984bda69c0f31fed975622acd.
- Existing tests: instrument/native/tests/test_pixels.c, Git blob c1a023fd2573e4a0823613c1cf328644b6d66518.

The fetched exact-source copies were verified by recomputing their Git blob hashes. The prototype intentionally copies validation and geometry from that converter, then gathers one Y/U/V sample per output pixel into three 560-byte row buffers before applying the row function. It does not introduce asynchronous access to decoder planes. Plane pointers still expire at the synchronous callback return.

## Why a blind libyuv substitution changes output

1. Current conversion preserves aspect ratio and black bars. A 320×240 frame fills 420×315 inside the 560×315 output, with 70-pixel side bars. The previous generic libyuv host probe stretched 320×240 to 560×315 and therefore did not test receiver geometry.
2. Current nearest-neighbor mapping is floor(dx*crop_width/output_width), including the explicit crop origin. libyuv's nearest scaler chooses a different sample phase. In the deliberate high-frequency 1280×720→560×315 luma test, all 176,400 output luma samples differed. This shows semantic difference, not that either sampling policy is universally better.
3. Current chroma coordinates are floor(absolute_source_x/2), floor(absolute_source_y/2). Independently scaling cropped 4:2:0 planes changes chroma phase, particularly for odd crop origins, odd dimensions and downscaling. The accepted (3,1,3,3) crop showed 30,581 RGB565 pixel differences in a chroma-only synthetic test.
4. Even without scaling, libyuv's coefficient/rounding implementation is not identical. Across all 16,777,216 YUV triples, 972,541 converted RGB565 values differed, with maximum one native step per R/G/B channel. That result came from the installed x86-64 libyuv 0.0.1904, not an ARM build.
5. Both the existing converter and default I420ToRGB565 assume BT.601 limited range. H.264 preflight currently skips VUI range/matrix values; ic_yuv_frame carries no color metadata. The existing source contract is therefore clear, but its match to an actual camera stream is not established. Carry validated matrix/range metadata or enforce a known input policy before claiming arbitrary-stream color correctness. Do not infer BT.709 solely from 720p resolution.

For a full 560×315 I420 intermediate, chroma is 280×158, never 280×157. Tight I420 storage is 264,880 bytes and RGB565 is 352,800 bytes. Actual aspect-fit widths may not be divisible by eight, so a vector-tail path is mandatory. The gather prototype uses only three 560-byte row arrays and preserves arbitrary accepted crop parity rather than building a scaled I420 image.

## CPU/ABI evidence

The exact repository build explicitly targets ARMv7 ARMEL base AAPCS with -mfloat-abi=soft, ELF interpreter /lib/ld-linux.so.3, and documented glibc 2.19. It is not Android Bionic or armhf. The decoder checks cpu_is_mx6x, libvpu 5.4.16 and firmware 2.3.10. These facts do not prove NEON is enabled by the actual kernel.

No trusted saved target /proc/cpuinfo or AT_HWCAP feature result is available. Runtime capability detection must remain in place. The default host build omits the NEON object entirely. ARM dispatch uses getauxval(AT_HWCAP) & HWCAP_NEON; absent support keeps scalar fallback. A NEON-specific correctness binary exits 77 if NEON is not reported, rather than claiming a pass.

Compile the dispatcher/reference/scalar objects with -mfloat-abi=soft. Compile only row_neon.c with -mfpu=neon -mfloat-abi=softfp. Softfp retains base AAPCS integer calling convention while permitting the NEON instructions; never link an armhf (-mfloat-abi=hard) library. Keep cross-object LTO disabled so NEON cannot migrate into the ungated code. The row TU rejects __ARM_PCS_VFP.

## Reproduce host checks

Run run_host_tests.sh with an existing C compiler. It builds only pure CPU tests, using the repository's exact native pixel source and headers, and executes no VPU/display code. No network, device writes, receiver, credentials or deployment tools are involved.

Optional compare_libyuv.py requires existing NumPy, cc and libyuv.so.0. It verifies geometry and exhaustively compares conversion coefficients. It does not install dependencies. Host libyuv itself is prebuilt and not ASan-instrumented.

## Reproduce ARM compile-only checks

Quick NEON syntax/object check with the known NDK clang (used only as an ARM compiler):

    clang --target=arm-linux-gnueabi -march=armv7-a -mfpu=neon -mfloat-abi=softfp -ffreestanding -O2 -Wall -Wextra -Werror -c prototype/row_neon.c -o row_neon.o
    llvm-readelf -h -A row_neon.o
    llvm-objdump -d row_neon.o

This checks only one object's syntax and attributes, not its linkage, numerical correctness or performance. An object with a VFP-register argument convention must be rejected. Expected code contains actual NEON operations, not merely a flag in the build command.

For full CPU-only correctness and benchmark executables, cross_build_only.sh requires ARM_CC and ARM_SYSROOT. With Clang also set ARM_TARGET=--target=arm-linux-gnueabi and provide its compatible linker/runtime configuration. The sysroot must match ARMEL glibc, with headers, CRT, libgcc/builtins, libc and getauxval available. The script never runs or transfers outputs. No ARM compiler or matching sysroot is installed in this executor, so this full build was NOT RUN.

After a build, record compiler version, source hashes, ELF interpreter, DT_NEEDED and GLIBC version requirements. Reject /lib/ld-linux-armhf.so.3, Android linker/Bionic, unexpected C++ dependencies, or glibc requirements newer than the actual target. Inspect assembly/attributes before any later separately authorized CPU execution. Compile the baseline fallback with NEON disabled and verify no ungated NEON instructions occur there.

## Later CPU execution and benchmark scope

A separately authorized ARM environment can run test_exact_armel first. It exercises the actual NEON row across all YUV combinations and row tails, then compares complete gather output to the pinned scalar converter. It must exit zero, not 77. Run the original pixel suite against the NEON-enabled gather path as well. QEMU can help establish instruction-path correctness, but its timing cannot predict hardware throughput.

bench_exact_armel is CPU-only and reads/writes ordinary RAM. It reports baseline and gather-dispatch p50/p95/p99/max, process CPU time and output hashes over 1,000 frames after 60 warmups for four source/crop shapes. It prints whether OS NEON support was observed. Run scalar-forced and detected builds in alternating order; record CPU frequency/governor, load and temperature. Its timing omits VPU decoding, DMA/cache behavior, networking, pipe IPC, guardian scheduling, framebuffer writes and physical refresh.

Before any end-to-end FPS conclusion, separately measure those stages using the existing profiling candidate and actual incoming stream. Do not infer physical frame rate from a CPU conversion microbenchmark. This experiment does not cure the existing foreground-disable transient, VPU/driver hangs, guardian failure or framebuffer-tearing risks.

## Primary API references

- libyuv RGB565 dispatch/default matrix: https://chromium.googlesource.com/libyuv/libyuv/+/44396e6e9aad554283c8f1fbe981ac122c40dfc7/source/convert_argb.cc
- Exact original converter: https://github.com/zgaiss/P7_ViewAssist/blob/c2ebfa26572f23da1de41d965cf2109cb2f42d52/instrument/native/ic_pixels.c
- Exact VUI handling: https://github.com/zgaiss/P7_ViewAssist/blob/c2ebfa26572f23da1de41d965cf2109cb2f42d52/instrument/native/ic_preflight.c
- Exact target build: https://github.com/zgaiss/P7_ViewAssist/blob/c2ebfa26572f23da1de41d965cf2109cb2f42d52/instrument/build_local.py
