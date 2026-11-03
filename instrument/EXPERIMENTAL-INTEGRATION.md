# Narrow fail-closed receiver integration

Based on test commit `83743850b22b66f4197f7ad70b5d8de79a789efb`. Experimental only; no deployment, hardware execution, PR or FPS improvement claim.

## Changes

- Track successful reader-thread creation. Allocation or pthread_create failure cannot join an uninitialized pthread_t.
- If joining a successfully created reader fails, exit the worker process before stack/queue/decoder teardown, without a potentially blocking diagnostic write. Process exit closes its pipe endpoint; the independent guardian keeps its existing lease/cleanup behavior. This is not proof of physical restoration or immunity to stuck drivers/guardian failure.
- Read the queue drop counter under its existing mutex, matching the writer's synchronization. This counter still counts overflow episodes, not individual discarded AUs.
- Align protocol definitions with existing array-form header declarations. No wire/protocol behavior changes; strict GCC no longer fails on this declaration mismatch.

## Verification

Linux host GCC 14.2.0 with -Wall -Wextra -Werror and UBSan:

- `python3 instrument/tests/test_reader_lifecycle.py`: four scenarios PASS (RGB allocation failure, thread-create failure, unconfirmed join fail-stop, normal join).
- Negative control using the original receiver and fixed declarations: new test FAILS as expected on attempted join after allocation failure.
- `python3 instrument/tests/test_receiver_offline.py`: five existing host-stub scenarios PASS (default ABI gate, authentication rejection, EOF restore, blocked decoder restore, parent-death restore).
- C protocol bounds/authentication/IO test PASS using its C-generated synthetic fixture. Java interoperability was not rerun.
- `sh instrument/native/tests/run_pixels_tests.sh`: pixel cases and non-operational host display compilation PASS.

No actual VPU, frame buffer, Android/HAL, ARM cross-build or whole-app build was exercised. Other existing limitations remain, including cooperative thread/queue initialization assumptions and signal/worker stop synchronization. This narrow fix is not complete concurrency or safety certification.

## FPS findings

Current IC_FEED timing mixes initialization, feeding, VPU work, synchronous CPU conversion and pipe backpressure. Guardian read averages include upstream idle time. Those counters cannot identify the actual ~12fps bottleneck.

The larger host latest-frame candidate reduced simulated backlog under injected slow display, but barely changed throughput (roughly 21.7 to 21.9fps with 45ms injected drawing). It also introduces mailbox threads, admission/storage and DMA policy changes. It is not integrated here as an unproven FPS fix.

Bounded, opt-in stage attribution is now included; see PROFILING.md for deterministic host tests and limits. Release ordering, deadlines, default ABI gate and existing display checks remain unchanged. Real FPS conclusions still require separately authorized target evidence.
