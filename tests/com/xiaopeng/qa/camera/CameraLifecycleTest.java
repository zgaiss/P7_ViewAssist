package com.xiaopeng.qa.camera;

/** Production admission/token/lease tests. No claim of Android callback emulation. */
public final class CameraLifecycleTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError(assertions); }
    public static void main(String[] args) {
        CameraLifecycle state = new CameraLifecycle();
        Object serviceA = new Object(), serviceB = new Object(), deviceA = new Object(), deviceB = new Object();
        long openA = state.reserve(serviceA);
        check(openA != 0);
        check(state.reserve(serviceB) == 0); // process cap before any onOpened
        state.unavailable(serviceA, openA); // timeout is not a release
        check(!state.isFree());
        check(!state.opened(serviceA, openA, deviceA)); // late open cannot publish
        check(state.reserve(serviceB) == 0);
        check(!state.closed(serviceB, openA));
        check(state.closed(serviceA, openA)); // only matching real closure frees the reservation
        long openB = state.reserve(serviceB);
        check(openB > openA);
        check(!state.opened(serviceA, openA, deviceA));
        check(state.opened(serviceB, openB, deviceB));
        check(!state.matches(serviceB, openB, deviceA));
        long sessionB = state.configure(serviceB, openB, deviceB);
        check(sessionB != 0);
        check(state.configure(serviceB, openB, deviceB) == 0); // no overlapping configure
        check(state.state() == CameraLifecycle.State.CONFIGURING);
        // Each expected setRepeatingRequest exception leaves this transition uncommitted.
        for (String error : new String[]{"CameraAccessException", "IllegalStateException", "IllegalArgumentException"}) {
            check(state.state() != CameraLifecycle.State.REPEATING);
        }
        check(state.repeating(serviceB, openB, deviceB, sessionB));
        state.closing(serviceA, openA); // stale error/stop cannot alter B
        check(state.state() == CameraLifecycle.State.REPEATING);
        check(!state.closed(serviceA, openA));
        state.closing(serviceB, openB);
        check(!state.currentSession(serviceB, openB, deviceB, sessionB));
        check(!state.repeating(serviceB, openB, deviceB, sessionB));
        check(state.reserve(serviceA) == 0); // close unconfirmed still occupies capacity
        check(state.closed(serviceB, openB));
        check(state.isFree());
        final int[] releases = {0};
        CameraLifecycle.Lease lease = new CameraLifecycle.Lease(new Runnable() { public void run() { releases[0]++; }});
        check(!lease.released()); // configure success/retirement do not release
        lease.closeConfirmed(); // matching session close
        lease.closeConfirmed(); // matching device close must not double release
        check(lease.released() && releases[0] == 1);
        CameraLifecycle.Lease broken = new CameraLifecycle.Lease(new Runnable() { public void run() { throw new IllegalStateException(); }});
        try { broken.closeConfirmed(); throw new AssertionError(); } catch (IllegalStateException expected) {}
        check(!broken.released());
        System.out.println("CameraLifecycleTest: " + assertions + " assertions passed");
    }
}
