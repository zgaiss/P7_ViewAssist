package com.xiaopeng.qa.camera;

/** Process admission and monotonic retirement state, independent of Android for host tests. */
final class EncoderRetirement {
    private static EncoderRetirement admitted;
    // Keep quarantined native wrappers strongly reachable even after Service destruction.
    private Object retainedOwner;
    private boolean initialized;
    private boolean published;
    private boolean retiring;
    private boolean detached;
    private boolean cleanupClaimed;
    private Thread cleanupWorker;

    private static void observeExit() {
        if (admitted == null) return;
        synchronized (admitted) {
            if (admitted.cleanupWorker == null
                    || admitted.cleanupWorker.getState() != Thread.State.TERMINATED) return;
            admitted.retainedOwner = null;
            admitted = null;
        }
    }

    static synchronized EncoderRetirement acquire() {
        observeExit();
        if (admitted != null) return null;
        admitted = new EncoderRetirement();
        return admitted;
    }

    static synchronized boolean isFree() {
        observeExit();
        return admitted == null;
    }

    synchronized void retainOwner(Object owner) { retainedOwner = owner; }

    synchronized boolean initialized() {
        if (retiring) return false;
        initialized = true;
        return true;
    }

    synchronized boolean publish() {
        if (!initialized || retiring) return false;
        published = true;
        return true;
    }

    synchronized boolean retire() {
        if (retiring) return false;
        retiring = true;
        if (!published) detached = true;
        return true;
    }

    synchronized boolean needsProducerBarrier() { return published; }
    synchronized void producerDetached() { if (retiring) detached = true; }

    synchronized boolean claimCleanup(boolean initializationExited, boolean workersExited) {
        if (!retiring || !detached || !initializationExited || !workersExited || cleanupClaimed) return false;
        cleanupClaimed = true;
        return true;
    }

    /** A successful native cleanup is not sufficient: admission observes worker exit separately. */
    synchronized void nativeReleasedByCurrentWorker() {
        if (!cleanupClaimed) throw new IllegalStateException("cleanup not claimed");
        cleanupWorker = Thread.currentThread();
    }
}
