# P7 simulation source references — NOT PRODUCTION

This archival branch is based on test commit `83743850b22b66f4197f7ad70b5d8de79a789efb`. It adds reference snapshots only under this directory. Existing application sources, resources, native build inputs and defaults are unchanged. No PR, deployment, installation or device operation is part of this archive.

## Separate components, not an integrated solution

- `native-v12/`: 15 exact pinned source files from the v12 resource-admission candidate. DMA request accounting and persistent backup reservation are experimental; defaults remain disabled. Host tests are not hardware ABI, physical memory, display timing or safe DMA reclamation evidence.
- `android-editor-observer/`: five exact service/lifecycle/projector sources from the later editor/observer candidate. This is not integrated into v12 or this branch's live `src/`. Service SHA256: `324d5e519149809cd984603d7481b1da2b501549ce2c99c723336948d5c0c672`.
- `recovery-host/`: selected exact recovery prototype implementation sources and its own pinned native sources, kept separate because they differ from v12. Its sealed snapshot, journal, ownership and retained virtual-device model is HOST ONLY. It assumes trusted cooperative processes and storage. It does not authorize or implement verified physical vehicle recovery.

This is a deliberately minimal **source-reference snapshot**, NOT a self-contained build, full test distribution, merged candidate, 30fps proof or deployment package. Missing build drivers, fake SDK declarations, Linux host-layout headers, Android resources/stubs, fixture media, fault injection suites and historical logs are intentional. Do not run copied code against hardware or substitute it directly into the application.

## Evidence identity and limitations

The separately preserved full v12 evidence archive is `P7-instrument-simulation-v12.zip`, SHA256 `0a32b4cc141de690c0f5f1403c7b3168d26b72a474a1a60528de4f722965a730` (49,522,370 bytes). Its clean-extraction verification recorded host-only success. That historical result applies to that full archive, NOT to all code in this source-only branch. The later Android and recovery snapshots are not part of its identity. No private artifact URL or dependency binary is embedded here.

All 45 source files are unchanged byte copies; `SOURCE-MANIFEST.json` records their SHA256 and originating component-relative paths. Existing copyright/license notices are preserved, and this branch does not relicense project code. No vendor SDK, FFmpeg header import, Linux host header, compiled binary, test-media binary, vehicle token, signing material or large evidence archive is newly included.

## Archive validation

Run `python3 verify_archive.py` here. It checks the exact file allowlist, UTF-8/no-NUL requirement, source hashes, Python syntax and selected credential patterns. Pattern checks are defense in depth, not a guarantee that every possible secret can be detected.

Additional checks performed with Debian GCC 14.2.0:

- `cc -std=c11 -Wall -Wextra -Werror -fsyntax-only native-v12/ic_backup_budget.c native-v12/ic_pixels.c native-v12/ic_preflight.c`: PASS.
- Python imports of `sealed`, `supervisor`, and `bridge`: PASS with standard library dependencies.
- The same strict GCC check including `ic_protocol.c`: FAIL on pre-existing pointer-versus-array declaration mismatches (`-Warray-parameter`). The frozen sources were not changed or warnings suppressed to manufacture a pass.
- Android full compile, native whole-program link, runtime fault matrices and target/hardware validation were NOT rerun on this minimal archive. This archive must not inherit earlier whole-package acceptance claims.

Real capture/codec identity, HAL lifecycle behavior, target ABI and memory capacity, driver blocking, physical display clearing/restoration and target frame rate remain separate engineering and safety work.
