# Opt-in receiver stage profiling (host-validated candidate)

Build defaults to `IC_PROFILE=0`. To enable, pass `-DIC_PROFILE=1` when compiling `instrument/native/ic_receiver.c`. No runtime environment policy, network output, payload logging or ABI-gate change is introduced. The existing build script remains default-off. Neither mode enables hardware permission.

## Metrics

Each stage prints one end-only `IC_PROFILE` summary: attempt count, failed count, invalid-clock count, total nanoseconds and maximum nanoseconds. There are no percentiles, histograms or measured FPS. Durations use CLOCK_MONOTONIC. Failed attempts with valid clock endpoints are included in totals; invalid/backwards endpoints are counted but excluded from total/max. New uint64 counters saturate at UINT64_MAX.

- `convert`: only synchronous RGB565 conversion. A stopped callback does not attempt conversion; conversion failure prevents pipe writing.
- `pipe_write`: only the existing complete-frame write, including backpressure and its unchanged 350 ms deadline.
- `guardian_read_wait`: entire read-exact call including waiting for upstream frames, fragmentation, and failed/EOF/timeout reads. Not a pure pipe-transfer measurement. The 1000 ms deadline is unchanged.
- `guardian_display_call`: only ic_display_frame, including its safety checks and copy/activation. Display open is deliberately excluded. These summaries do not expose framebuffer scanout timing or capture age.
- `queue_high_water`: maximum observed pending AU count, not the constant capacity (12). Updates occur inside the existing queue mutex.
- `dropped_aus`: counts all queue entries discarded on overflow, the overflowing incoming non-IDR when discarded, and non-IDRs discarded while awaiting IDR. An accepted IDR is not counted. Final teardown of unconsumed entries is excluded. Existing `overflow_events` counts overflow incidents, not individual AUs.

No decoder/VPU instrumentation or mailbox changes are included. Existing IC_FEED and IC_GUARD logs remain unchanged and retain their original, coarser meaning.

## Timing and cleanup limits

No additional per-frame I/O, locks, threads, heap allocation or unbounded samples are added. Fixed-size statistic fields still exist in default-off structures. Disabled-mode tests verify zero profiling clock calls and no profiling output; enabled-mode clock calls and counter work have overhead and do not guarantee strict timing noninterference.

Guardian summaries occur after display_close, RGB free and fd close. Session summaries occur after reader join, pipe close, decoder close attempt, guardian reap attempt and queue/RGB cleanup. The failed-reader-join path immediately calls _exit(74), without production logging or profiling. Summaries may be absent after crashes, join failure or blocked cleanup.

Summaries use stderr and can block after cleanup; this is not hard real-time bounded I/O. A failed display close or guardian reap remains an unconfirmed recovery, not a proven cleanup. Existing baseline logging is not made nonblocking by this change. No device restoration, SDK/DMA behavior, target throughput or vehicle safety claim follows.

## Reproduction and results

Run from the repository root on a Linux host with GCC, Python 3 and POSIX tools:

```
python3 instrument/tests/test_profile.py
python3 instrument/tests/test_reader_lifecycle.py
python3 instrument/tests/test_receiver_offline.py
mkdir -p instrument/build/profile-cc
printf '#!/bin/sh\nexec /usr/bin/cc -DIC_PROFILE=1 "$@"\n' > instrument/build/profile-cc/cc
chmod +x instrument/build/profile-cc/cc
PATH="$PWD/instrument/build/profile-cc:$PATH" python3 instrument/tests/test_reader_lifecycle.py
PATH="$PWD/instrument/build/profile-cc:$PATH" python3 instrument/tests/test_receiver_offline.py
```

The locally generated wrapper invokes /usr/bin/cc with -DIC_PROFILE=1. It is only for host tests, not a target compiler. Generated outputs remain under ignored instrument/build/.

Final results:
- Deterministic actual-receiver source seam, ordinary disabled and enabled configurations, compiled with -Wall -Wextra -Werror and UBSan: PASS. Covers exact conversion/write separation, failed conversion skipping write, write failure, stop-before-callback, invalid/backwards clocks, errno preservation, total saturation, both overflow recovery cases, exact dropped-AU/high-water accounting, and guardian read/display attribution with display-open time excluded.
- Original reader lifecycle suite: all 4 cases PASS in default-off and enabled modes (malloc failure, create failure, join fail-stop, normal join).
- Original guardian/network suite: all 5 cases PASS in default-off and enabled modes (default ABI gate, reject-auth, EOF, hung decoder, parent death).

Historical local run logs are not checked in; the commands above regenerate test results. Test executables are host-only with fake decoder/display or scripted timing/I/O. No hardware or SDK call, target compile, device FPS run, deployment or PR is part of this validation. Historical VALIDATION.md is copied background evidence, not a new validation of this candidate.
