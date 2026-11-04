# VPU execution gate: CLOSED

The host diagnostic is safe in its stated scope because it links **no VPU,
framebuffer, display, guardian or IPU implementation**. Its fixture backend
replays software-decoded host RAM. It does not make hardware execution safe.
No car, live VPU, Android application, network transport or display was touched.

## Verified lifecycle, and what remains unproved

The source basis is P7_ViewAssist `cab703b15f3de4b968abc814af17558a13d9774a`.
Its unchanged `ic_decoder.c` Git blob is
`067f646912e1675a56c6a621f81c98b44767cea2`:
[exact wrapper](https://github.com/zgaiss/P7_ViewAssist/blob/cab703b15f3de4b968abc814af17558a13d9774a/instrument/native/ic_decoder.c).

1. A displayed frame remains owned during the synchronous callback. Finish
   conversion/copy before returning and before `vpu_DecClrDispFlag(display)`.
   Do not queue plane pointers for asynchronous CPU/SIMD use. A normal close
   closes the codec, checks idle, releases frame/slice/ring mappings and memory,
   and then uninitializes.
2. The wrapper's two-second check surrounds only per-frame busy polling. The
   official SDK 5.4.16 has internal unbounded busy loops in initialization,
   version retrieval, decoder close, sequence initialization and frame
   registration. Source locations include lines 337, 495, 2721, 2949–2953 and
   3399 of [pinned vpu_lib.c](https://github.com/nxp-imx/imx-lib/blob/a51ae713b266b63c44c046031d51e87706b5c1f2/vpu/vpu_lib.c).
3. On unsafe failure the wrapper avoids explicit output retrieval, close,
   unmap, free, uninit and reset. This conservatism does not prove DMA memory
   survives process exit. SDK shutdown closes its VPU descriptor and leaves
   physical-memory cleanup to the kernel: [vpu_io.c, lines 233–282](https://github.com/nxp-imx/imx-lib/blob/a51ae713b266b63c44c046031d51e87706b5c1f2/vpu/vpu_io.c#L233-L282).
4. The official Linux `imx_3.0.35_4.1.0_caf` comparator at
   `9f14414b0250131300c2a9a59d349632a099dce8` performs last-open-count cleanup.
   Its release path waits for idle, may attempt i.MX6 bus quiescence and a
   platform reset, returns `-EFAULT` if quiescence fails, and frees the global
   DMA-buffer list on successful cleanup. Other opens affect when this happens.
   [Pinned comparator mxc_vpu.c](https://github.com/nxp-imx/linux-imx/blob/9f14414b0250131300c2a9a59d349632a099dce8/drivers/mxc/vpu/mxc_vpu.c).
   This is **not an established source match** for the target kernel.

The exact source/build match for target `3.0.35-00054-g1733cc4` remains missing.
Matching SDK version numbers and public ABI sizes does not establish the target
library, kernel configuration, reset implementation or firmware identity.
Neither killing a worker, a watchdog, a fixed run duration, holding an extra fd,
nor intentionally leaking userspace allocations is an established safe DMA
teardown strategy. Keeping another process's fd open could change global
ownership and postpone rather than solve cleanup; it is not implemented here.

## Evidence required before any real backend

- Identify and match the deployed libvpu, driver, kernel configuration, platform
  reset code and firmware using trusted offline sources and build/binary
  fingerprints. Identify other VPU clients and global allocation ownership.
- Establish the precise device/bus quiescence condition before memory is
  unmapped/reclaimed. Analyze normal close, signals, process death, partial
  initialization and failures inside every SDK call, not only frame polling.
- Establish cache maintenance and output linear-layout/stride guarantees.
- Review impact of any reset or driver-global cleanup on other video/display
  clients. Headless operation does not imply isolation from them.
- Review target-compatible final ELF/ABI and stack headroom. Existing ARM v4T
  final-ELF metadata from the earlier receiver remains a separate open issue.
- Obtain separate explicit authorization and an appropriate stationary test
  environment for any hardware execution. This source work grants none.

Until these gates are satisfied, selecting `--backend=vpu` returns 78 before
opening fixture files, and compiling with `P7_ENABLE_REAL_VPU=1` deliberately
fails. There is no target build command or adapter to accidentally enable by
passing the receiver's existing ABI flag. Do not remove the gate merely because
host tests pass. A later hardware backend requires a separate reviewed change.
