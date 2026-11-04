# Exact-commit follow-up validation

Code under review: `d475874a25dc0c7c26e9ce182ddb12ac097a96c8`, branch `experiment/fail-closed-fps-20261103`, based on test `83743850b22b66f4197f7ad70b5d8de79a789efb`. This follow-up adds a portable hostile test and this report, not a production-source change.

## Provenance and independent review

All 21 locally available native/test source files used in the follow-up were matched to the code commit's remote Git blobs. The unchanged Java protocol source and test were independently matched to remote blobs `96a24c029b967f6bfb43f63b3df828666a51eb82` and `b32c3bcca7ec77a3ef182b9e1c740e6c6c04e3b6`.

Exact receiver SHA256: `ccf8557ddeab15501fa3f50d28d30166e9a3a5f07788d6ffbdc55991ca3b5c4b`.
Exact profiling header SHA256: `da26ce45fa1d1f8ba17296c52840c966788d5ce5e0c9df70c99768a960b1d698`.

Independent source review and host tests found no newly introduced concrete lifecycle, queue synchronization or profiling-error regression. This is limited evidence, not proof of absence of defects or vehicle safety.

The additional test starts an actual receiver with fake decoder/display, displays one mock frame, fills stderr completely and restores blocking mode, then injects an unconfirmed pthread_join. With profiling both off and on, the receiver exits 74 and the independent guardian records mock restoration BEFORE stderr is drained. It verifies the new fail-stop does not depend on successful logging. It does not verify physical restoration or recovery from arbitrary pthread corruption.

## Reproduce host checks

From a Linux checkout with GCC and Python 3:

```
python3 instrument/tests/test_reader_lifecycle.py
python3 instrument/tests/test_profile.py
python3 instrument/tests/test_reader_blocked_stderr.py
python3 instrument/tests/test_receiver_offline.py
```

These UBSan/warnings-as-errors tests passed. The new hostile test passed independently again after changing its source lookup to be checkout-relative. Existing baseline hazards remain: blocking periodic stderr writes, signal/worker stop synchronization, unchecked mutex/condition initialization, legacy signed overflow counters and absent mutex/condition destruction. None is a new defect established by this change; none is certified safe.

## Java-to-C interoperability

Java 21.0.12.1 compiler module was usable despite no javac launcher. Exact unchanged Java protocol/test sources compiled and ran, then their binary handshake fixture passed current C protocol tests under UBSan:

```
mkdir -p instrument/build/exact-java
java com.sun.tools.javac.Main -d instrument/build/exact-java src/com/xiaopeng/qa/camera/ICLinkProtocol.java instrument/tests/ICLinkProtocolTest.java
java -cp instrument/build/exact-java com.xiaopeng.qa.camera.ICLinkProtocolTest instrument/build/hello.bin
cc -std=c11 -Wall -Wextra -Werror -fsanitize=undefined -I instrument/native instrument/native/ic_protocol.c instrument/tests/test_protocol.c -o instrument/build/exact-protocol
instrument/build/exact-protocol instrument/build/hello.bin
```

PASS: Java protocol tests and C protocol bounds/authentication/IO/Java fixture. The attempted `--release 11` compile FAILED because this runtime lacks the release-11 symbol set. No Java11 API compatibility, Android compilation, resource linking or DEX validation is inferred from the Java21 host run.

## Additional address/undefined sanitizer check

Deterministic profile tests passed in both modes with AddressSanitizer + UBSan and leak detection explicitly disabled:

```
mkdir -p instrument/build/exact-asan
for mode in 0 1; do
  cc -std=c11 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined -ffunction-sections -fdata-sections -DIC_PROFILE=$mode -I instrument/native instrument/tests/test_profile.c instrument/native/ic_protocol.c -Wl,--gc-sections -o instrument/build/exact-asan/profile-$mode
  ASAN_OPTIONS=detect_leaks=0 instrument/build/exact-asan/profile-$mode
done
```

Default leak-enabled execution FAILED at LeakSanitizer shutdown with its ptrace-environment fatal error. This limitation is retained; disabling leak detection permits address/UB checks but is NOT a leak-check pass. A preliminary wrapper that redundantly defined IC_PROFILE failed warnings-as-errors; the final commands above avoid duplicate definitions.

## ARM and Android build blockers

No usable ARM cross-compiler/sysroot/vendor SDK or Android SDK/aapt2/D8 is available in the current cloud executor. No connected authorized computer was available in the environment catalog. Continuing the prior compile executor failed, with no new validation turn visible afterward. Therefore ARM compilation/linking and complete Android build for this exact commit were NOT RUN. Previous v9/resource/editor-candidate reports cover different source combinations and are not accepted as evidence for this commit.

The Java/Android application paths are unchanged relative to the pinned test baseline, but that does not substitute for a complete exact-checkout build. The existing sensitive authentication resource was not fetched, copied or transmitted. A future Android compile-only rerun must explicitly record any invalid placeholder substitution and must not claim a bit-identical application resource build.

No target output was run, no hardware accessed, no dependency/vendor binary published, no signing, installation, deployment or PR performed. The unresolved target build and real-stage timing evidence remain blockers to target/FPS acceptance.
