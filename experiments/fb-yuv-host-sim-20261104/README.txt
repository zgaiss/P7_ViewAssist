Framebuffer-owned YUV slots: host-only experiment
2026-11-04

This branch adds only this independent experiment directory to c2ebfa2.
It does not change or integrate the native/Android application.
No device image, private key, target library or hardware adapter is included.
Do not merge this experiment as a production display-safety implementation.

Reproduce using Python 3 standard library, from this directory:
  python3 -B run_suite.py results-reproduce
  python3 -B verify_results.py results-reproduce

The output directory must not exist. Results, traces, journals and bytecode
are ignored by Git. VALIDATION.json records the observed final run; the full
generated logs are reproducible and are deliberately not committed.

Source:
  layouts.py: bounded planar copies from padded VPU-style source arenas to
              framebuffer-owned I420/YV12/NV12 slots; no scaler.
  model.py: independent driver returns, hardware readers, slot leases, journal
            state, fake-only completion witnesses and conservative policy.
  run_suite.py: byte-copy, fault, restart, restore and finite-interleaving cases.
  verify_results.py: independent trace replay and tested-source hash checks.

Read REPORT-zh.txt for results and unresolved boundaries; EVIDENCE-MAPPING.txt
separates prior target facts from explicit adversarial/model assumptions.

Observed validation: 34/34 cases met expectations, including six negative
controls. The copy matrix has 39 trials and 5,751,162 visible Y/U/V component
samples. Independent verification replayed 386 events. Passing negative
controls means that the unsafe scenario was detected, not made safe.

Current target route remains DISABLED. The trusted cache, last-read,
retirement and global-stop witnesses have NO real target implementation.
The default controller refuses missing contracts and predicted allocation
growth. Real target frame retirement, exact restoration, OEM ownership and
last-keeper-crash safety remain unproven.

Crashes are deterministic model events and object restarts. No real SIGKILL,
DMA, framebuffer ioctl, mmap, socket, reset or target program is executed.
The model does not prove real-time deadlines, power-loss durability, full
kernel behavior or the physical visibility of OEM warning overlays.
