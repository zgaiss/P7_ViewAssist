package com.xiaopeng.qa.camera;

/** Exercises the actual production admission, cleanup and statistics policy, without Android. */
public final class RelayLifecycleTest {
    public static void main(String[] args) {
        outputCapAndIdentity();
        rootsSurviveServiceReplacement();
        twoPhaseCompletion();
        generationAndSwapTruth();
        rootDependencyOrder();
        cleanChurnDoesNotRetainRecords();
        boundedOwnerLocalPacing();
        sameTargetWaitHasBoundedGrace();
        System.out.println("PASS RelayLifecycleTest (production policy, not Android/EGL integration)");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void outputCapAndIdentity() {
        RelayLifecycle registry = new RelayLifecycle();
        Object target = new Object();
        RelayLifecycle.Token first = registry.reserve(false, target);
        check(first != null);
        check(registry.reserve(false, target) == null);
        first.retiring.set(true);
        check(registry.reserve(false, target) == null); // Timeout/retire is not cleanup.
        for (int i = 1; i < 8; i++) check(registry.reserve(false, new Object()) != null);
        for (int i = 0; i < 10000; i++) check(registry.reserve(false, new Object()) == null);
        check(registry.snapshot().size() == 8);
        first.nativeClean = true;
        check(!registry.finish(first));
        check(registry.reserve(false, target) == null);
        first.threadExited = true;
        check(registry.finish(first));
        check(!registry.finish(first));
        check(registry.reserve(false, target) != null);
    }
    private static void rootsSurviveServiceReplacement() {
        RelayLifecycle registry = new RelayLifecycle();
        RelayLifecycle.Token old = registry.reserve(true, null);
        old.retiring.set(true); // Old service shutdown does not erase it.
        check(registry.reserve(true, null) != null);
        check(registry.reserve(true, null) == null);
        old.threadExited = true; // Unexpected exit does not prove native cleanup.
        check(!registry.finish(old));
        check(registry.reserve(true, null) == null);
        old.nativeClean = true;
        check(registry.finish(old));
        check(registry.reserve(true, null) != null);
    }
    private static void twoPhaseCompletion() {
        RelayLifecycle registry = new RelayLifecycle();
        RelayLifecycle.Token t = registry.reserve(false, new Object());
        t.contextGone = true; // Shared program or surface may still need cleanup.
        t.threadExited = true;
        check(!registry.finish(t));
        check(!registry.empty());
        t.nativeClean = true;
        check(registry.finish(t));
        check(registry.empty());
    }
    private static void generationAndSwapTruth() {
        RelayLifecycle registry = new RelayLifecycle();
        RelayLifecycle.Token old = registry.reserve(false, new Object());
        RelayLifecycle.Token current = registry.reserve(false, new Object());
        check(!RelayLifecycle.accepts(old, current.id));
        check(RelayLifecycle.accepts(current, current.id));
        current.retiring.set(true);
        check(!RelayLifecycle.accepts(current, current.id));
        RelayLifecycle.Stats stats = new RelayLifecycle.Stats(0, 0, 0, 0, 0);
        stats = stats.result(true, 100, 3, 0);
        stats = stats.result(false, 200, 9, 0x300D);
        check(stats.attempts == 2 && stats.successes == 1);
        check(stats.lastSuccess == 100 && stats.error == 0x300D && stats.cost == 9);
        stats = stats.result(true, 300, 2, 0);
        check(stats.lastSuccess == 300 && stats.successes == 2);
    }
    private static void rootDependencyOrder() {
        RelayLifecycle registry = new RelayLifecycle();
        RelayLifecycle.Token child = registry.reserve(false, new Object());
        java.util.List<RelayLifecycle.Token> children = java.util.Collections.singletonList(child);
        check(!RelayLifecycle.canFinalizeRoot(0, children));
        child.contextGone = true;
        check(!RelayLifecycle.canFinalizeRoot(0, children)); // Shared program still pending.
        child.nativeClean = true;
        check(!RelayLifecycle.canFinalizeRoot(1, children)); // Camera still owns input.
        check(RelayLifecycle.canFinalizeRoot(0, children)); // No circular wait on worker exit.
        check(!registry.finish(child)); // Budget still requires actual exit.
    }
    private static void cleanChurnDoesNotRetainRecords() {
        RelayLifecycle registry = new RelayLifecycle();
        Object target = new Object();
        for (int i = 0; i < 10000; i++) {
            RelayLifecycle.Token t = registry.reserve(false, target);
            check(t != null);
            t.retiring.set(true);
            t.nativeClean = true;
            t.threadExited = true;
            check(registry.finish(t));
            check(registry.empty());
        }
    }

    private static void boundedOwnerLocalPacing() {
        RelayLifecycle.Pacing pacing = new RelayLifecycle.Pacing(0, 0);
        check(pacing.successfulSwap(100, false).minIntervalMs == 0);
        pacing = pacing.successfulSwap(100, true);
        check(pacing.minIntervalMs == 16);
        pacing = pacing.successfulSwap(100, true);
        check(pacing.minIntervalMs == 32);
        pacing = pacing.successfulSwap(1, true).successfulSwap(1, true);
        check(pacing.minIntervalMs == 0);
        pacing = pacing.successfulSwap(250, true);
        check(pacing.minIntervalMs == 16 && pacing.slowStreak == 1);
        pacing = pacing.successfulSwap(250, true);
        check(pacing.minIntervalMs == 64 && pacing.slowStreak == 0);
        for (int i = 0; i < 10000; i++) pacing = pacing.successfulSwap(100, true);
        check(pacing.minIntervalMs == 200);
        for (int i = 0; i < 10000; i++) pacing = pacing.successfulSwap(1, false);
        check(pacing.minIntervalMs == 0);
    }

    private static void sameTargetWaitHasBoundedGrace() {
        Object target = new Object();
        RelayLifecycle.TargetWait wait = RelayLifecycle.TargetWait.observe(null, target, 7, 100);
        check(!wait.expired(2099));
        check(wait.expired(2100));
        check(RelayLifecycle.TargetWait.observe(wait, target, 7, 2000) == wait);
        check(RelayLifecycle.TargetWait.observe(wait, target, 7, 100000).expired(100000));
        RelayLifecycle.TargetWait differentTarget = RelayLifecycle.TargetWait.observe(wait, new Object(), 7, 3000);
        check(!differentTarget.expired(3000));
        RelayLifecycle.TargetWait differentRelay = RelayLifecycle.TargetWait.observe(wait, target, 8, 3000);
        check(!differentRelay.expired(3000));
        check(differentRelay.expired(5000));
    }

}
