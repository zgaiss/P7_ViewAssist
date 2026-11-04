# Independent host-contract review

Reviewed `src/ic_ipu_candidate.c`, its public interface, and the host tests. No vehicle/device was accessed, and nothing was published or deployed.

## Finding fixed

The original callback accepted source planes overlapping its owned staging buffer, then used `memcpy`. An isolated reproduction assigned `frame.y = fake.mem[0] + 1` after `ic_ipu_open_test`; AddressSanitizer reported `memcpy-param-overlap` at the original callback's first plane copy (original source line 91).

The candidate now rejects each source plane's overflow-checked span against the input, output, and packed RGB allocations before any copy or backend operation. Rejection is preferable to `memmove`: copying one plane could otherwise destroy another plane's unread bytes. Nine regression cases exercise each Y/U/V plane against each of those three buffers and verify no additional check, sync, queue, or publish occurs. The isolated original defective reproduction is intentionally excluded from this deliverable.

## Final verification

From this experiment directory:

```sh
./reproduce.sh
```

Passed strict C11 compilation, default-build symbol checks, UBSan host-contract tests, and the production complete-frame pipe bridge test. The bridge uses synthetic bytes, production exact-write/read helpers (350 ms/1000 ms), and an independent child reader. It does not exercise a display or device.

Additional independent sanitizer command:

```sh
cc -std=c11 -O1 -g -Wall -Wextra -Werror -Wpedantic \
  -fsanitize=address,undefined -DIC_IPU_HOST_CONTRACT_TEST -Isrc \
  src/ic_ipu_candidate.c tests/test_ipu_candidate.c \
  -o results/test_fixed
ASAN_OPTIONS=detect_leaks=0 results/test_fixed
```

Passed. Running with default leak detection failed because LeakSanitizer cannot operate under this environment's ptrace. This is a tooling limitation, not a leak finding; leak detection was not established. Unknown-completion cases deliberately retain allocations and exit their test child.

## Review conclusions and limits

- The production target opener unconditionally returns `ENOTSUP`; the host opener requires the explicit test compile flag.
- CHECK mutation detection compares every plan field and both allocation handles.
- Unknown completion quarantines the object, retains both allocations, blocks further callback work, and refuses close. It does not prove real driver completion, DMA lifetime, or process-exit reclamation.
- No cleanup defect was found under the documented backend contract: uniquely owned successful allocations, zero descriptors on allocation failure, and no DMA launched by allocate/check/sync.
- “No reopen” must be a caller/process requirement; the test opener does not enforce a global prohibition against constructing another object.
- Backend callbacks must preserve allocation descriptors. They are trusted adapter code, not an adversarial interface.
- The source frame API carries no allocation lengths. Readable source backing and borrowed lifetime remain decoder obligations; arithmetic and alias checks cannot prove mapped/readable memory.
- Fake output is synthetic. These tests establish host ownership, geometry, fault handling, packing, and pipe transport only. They do not establish IPU ABI compatibility, cache behavior, color correctness, hardware support, latency, or end-to-end vehicle operation.
