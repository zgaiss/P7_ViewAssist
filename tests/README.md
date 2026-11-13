# Lifecycle checks

These tests exercise the platform-independent policy classes used by the application.
They do **not** emulate Android Camera2, SurfaceTexture, EGL, MediaCodec, a camera HAL,
or a vehicle. Passing them is not evidence that video is fresh or that a driver will
return from a blocked native call.

## Host policy tests

Run from the repository root:

```sh
bash tests/run-host-tests.sh
```

Requires a JDK with `javac`, or Java 9+ with the `jdk.compiler` module. The script
compiles into a temporary directory and removes it on exit. It uses Java 8 source
and bytecode levels, but the host JDK's API classes; this is not an Android API
compatibility check.

## Java syntax check

With Java 11+ and `jdk.compiler`:

```sh
java tests/CheckJavaSyntax.java src
```

This parses every production Java file using Java 8 syntax. It intentionally does
not resolve Android symbols or type-check calls across the application.

## Real Android resource and API compilation

Install Android SDK platform 34 and build-tools, then run:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk bash tests/check-android-source.sh
```

`ANDROID_HOME` is also supported. `ANDROID_JAR` and `AAPT2` can select explicit
installed SDK files. This generates `R.java`, compiles resources, and compiles all
application Java against the actual Android API jar. It does not sign or install
an APK. Exit status 2 means the required SDK/tool is missing, **not** a passing
compilation.

## Device verification is still required

Before merge or release, use an isolated bench with a frame-numbered source and
record the actual display. Exercise delayed and out-of-order Camera2 callbacks,
failed/blocked EGL initialization and swaps, repeated view changes, encoder stop,
service stop/restart, and reverse/hazard/manual-close gates. Check creation versus
confirmed cleanup counts and the process resource limits, not just RSS or elapsed
time. Suggested endurance coverage is 1,000 view transitions and two hours of
continuous video, with recovery latency and false-positive recovery recorded.

Do not provoke failures while driving. The remaining shared-OES cross-context
synchronization risk needs a separate bounded-frame-pool/fence design and GPU
validation; these lifecycle tests do not solve or prove that synchronization.
