# Instrument decoder: contract and offline evidence

## Scope

The wrapper uses the legacy Linux ARMEL `libvpu.so.4` API directly, not FFmpeg, Bionic, or guessed SDK structures. Build against the external official SDK 5.4.16 `vpu_lib.h` and `vpu_io.h`; no proprietary headers/source are copied here. ARM32 assertions cover Open=88, Initial=152, FrameBuffer=28, Param=52, Output=308, memory descriptor=16 (plus enum/version sizes).

`ic_decoder_open` **validates, submits and attempts decoding of the first AU**. Do not submit that AU again. A frame callback may already run inside open. Firmware rollback can instead buffer that AU until more input arrives. Callbacks must synchronously copy all required Y/U/V bytes; pointers are invalid once callback returns. The wrapper calls `vpu_DecClrDispFlag` on the **display** index only after callback returns, while hardware is idle. No concurrent access/reentry is supported.

All open failures require process termination; a global poison flag rejects retries. Feed failures require stream shutdown and one close attempt. Nonzero close means unsafe or cleanup failure: exit the process, never retry. Busy timeout does **not** call GetOutputInfo, close, unmap, free or uninit, and deliberately retains possible DMA memory. No explicit reset is invoked by this wrapper. The vendor SDK may internally reset on certain error paths or initialization; this wrapper cannot promise the library never does so.

## Why rollback, not a guessed PIC_END value

Official external `source-5.4.16/vpu/vpu_lib.c` was read, not merely its ABI header:

- DecOpen lines 2662–2663 maps **any nonzero** `bitstreamMode` to stream-param bit3.
- GetInitialInfo lines 2950–2975 calls this **rollback mode**, reporting insufficient input rather than requiring an asynchronously arriving next AU.
- GetOutputInfo lines 4183–4185 converts insufficient input bit4 into `decodingSuccess & 0x10`.
- UpdateBitstreamBuffer lines 3580–3591 uses **size zero as EOS**, not a chunk-complete marker.
- SetEscSeqInit lines 2743–2768 controls the separate sequence escape bit0.

Therefore the wrapper uses `bitstreamMode=1` (rollback), never invents a modern PIC_END=2 enum, appends a legal six-byte AUD boundary to each complete AU, sets sequence escape for initial parsing and clears it afterward, and **never submits EOS during initialization or feed**. A 2 MiB circular DMA allocation, vendor-reported room checks, and at most four Starts per feed bound memory/work. Completed Starts are always paired with GetOutputInfo (releasing the SDK pending mutex), including rollback. Rollback returns success to the producer without duplicating or re-submitting the AU; unread bytes stay in the ring for the next incremental feed. No callback is made from rollback output.

This is source-reasoned anti-starvation behavior, **not device-proven streaming behavior**. SDK GetInitialInfo/RegisterFrameBuffer/Close and other calls contain internal polling or locking outside the wrapper's cooperative two-second busy deadline. An external process watchdog remains mandatory. No reset/recovery or in-process retry path is provided. Close does not drain a delayed final frame.

## Narrow pure-C gate

`ic_preflight` validates allocation-free Annex-B AU headers before any VPU use: at most 1 MiB; one immutable SPS/PPS pair, Baseline, coded dimensions 64..1280 by 64..720, progressive 8-bit 4:2:0; first AU must contain SPS, PPS and IDR; subsequent AUs must contain one picture. It rejects B/SP/SI slices, CABAC, FMO, redundant pictures, frame-num gaps, high-profile PPS extensions, changed parameter-set bytes, multiple pictures, unsupported/extension/EOS NALs and out-of-range cropping. Multiple increasing raster-order slices in the same picture are accepted. VUI/HRD and PS trailing bits are parsed, with nonzero max_num_reorder_frames rejected. Level <=3.1 only. These deliberate restrictions are narrower than every theoretically valid Baseline stream.

The gate is **not an entropy decoder or hostile-bitstream security boundary**: it cannot prove complete slice payload or full H.264 conformance from a header. Callers must frame complete AUs from the trusted encoder; firmware errors are still checked. Later sequence changes are rejected byte-for-byte, even an otherwise innocuous changed VUI or PPS.

## Offline reproduction

Run from project root:

```sh
python3 instrument/tests/build_decoder_offline.py
```

The helper uses the previous experiment only as an external read-only SDK/sysroot source, cross-compiles the two ARMEL objects with the NDK compiler and strict warnings, and runs host pure-C tests. Generated artifacts stay under `instrument/build/decoder/`. Optional `--fixture` accepts an Annex-B Baseline stream with four-byte AUD delimiters. An offline fixture can be encoded using host FFmpeg/libx264; that tool is only a fixture generator and is never linked into the target decoder:

```sh
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=1280x720:rate=10' \
  -frames:v 30 -c:v libx264 -profile:v baseline -level 3.1 -pix_fmt yuv420p -bf 0 \
  -x264-params 'aud=1:repeat-headers=1:keyint=10:scenecut=0' \
  -f h264 instrument/build/decoder/baseline720-aud.h264
python3 instrument/tests/build_decoder_offline.py \
  --fixture instrument/build/decoder/baseline720-aud.h264
```

## Actual validation in this session

- ARMEL `ic_decoder.o` and `ic_preflight.o` compiled with `-Wall -Wextra -Werror -Wpedantic`; all ABI static assertions passed, ELF32 little-endian ARM verified. Undefined-symbol inspection contains direct VPU/IO APIs and no reset or FFmpeg symbols.
- Host tests **passed with UBSan**: 10,000 incremental synthetic AUs, malformed/changed parameter sets, B/high-profile/interlaced/oversize/multi-picture rejection, same-picture multi-slice acceptance, truncation bounds, and 5,000 deterministic random inputs.
- A locally generated 719,160-byte Baseline 1280×720 no-B x264 stream with 30 complete AUD-delimited AUs passed the gate, including repeated IDR SPS/PPS.
- ASan builds compiled, but this host environment stalled before `main` after `AddressSanitizer: libc interceptors initialized`; bounded runs were terminated after 20/30 seconds. **ASan execution was not validated.** The helper defaults to UBSan; `--asan` is available for a compatible host. A separate unsanitized host run also passed the synthetic tests (including an earlier 50,000-input random smoke run).

No device is contacted or operated by these commands. Object compilation and host tests do not establish actual hardware DMA coherency, VPU/display interoperability, sustained framerate, watchdog recovery, library exit behavior, final-frame latency, or device cleanup correctness. The generated `instrument/build/decoder/offline-validation.json` records exact commands, output, external header hash, and object hashes.
