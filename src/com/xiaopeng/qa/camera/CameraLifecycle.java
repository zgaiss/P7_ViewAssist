package com.xiaopeng.qa.camera;

/** Owner-thread state. An unknown open/close is still a reservation, not free capacity. */
final class CameraLifecycle {
    enum State { IDLE, OPENING, OPEN, CONFIGURING, REPEATING, CLOSING, UNAVAILABLE }
    /** Exactly-once target release, shared by session-close and device-close paths. */
    static final class Lease {
        private final Runnable release;
        private boolean released;
        Lease(Runnable release) { this.release = release; }
        void closeConfirmed() {
            if (released) return;
            // A throwing release keeps the lease charged; do not pretend cleanup succeeded.
            release.run();
            released = true;
        }
        boolean released() { return released; }
    }
    private long sequence;
    private Object owner;
    private Object device;
    private long openToken;
    private long sessionToken;
    private State state = State.IDLE;

    long reserve(Object candidate) {
        if (owner != null) return 0L;
        owner = candidate;
        openToken = ++sequence;
        state = State.OPENING;
        return openToken;
    }
    boolean owns(Object candidate, long token) { return owner == candidate && openToken == token; }
    boolean matches(Object candidate, long token, Object handle) {
        return owns(candidate, token) && device == handle;
    }
    boolean opened(Object candidate, long token, Object handle) {
        if (!owns(candidate, token) || state != State.OPENING) return false;
        device = handle;
        state = State.OPEN;
        return true;
    }
    long configure(Object candidate, long token, Object handle) {
        if (!matches(candidate, token, handle) || state != State.OPEN) return 0L;
        sessionToken = ++sequence;
        state = State.CONFIGURING;
        return sessionToken;
    }
    boolean currentSession(Object candidate, long token, Object handle, long session) {
        return matches(candidate, token, handle) && sessionToken == session
                && (state == State.CONFIGURING || state == State.REPEATING);
    }
    boolean repeating(Object candidate, long token, Object handle, long session) {
        if (!currentSession(candidate, token, handle, session) || state != State.CONFIGURING) return false;
        state = State.REPEATING;
        return true;
    }
    void retireSession(Object candidate, long token) {
        if (!owns(candidate, token)) return;
        sessionToken = ++sequence;
        state = State.CLOSING;
    }
    boolean sessionClosed(Object candidate, long token, Object handle) {
        if (!matches(candidate, token, handle) || state != State.CLOSING) return false;
        state = State.OPEN;
        return true;
    }
    void closing(Object candidate, long token) {
        if (owns(candidate, token)) { sessionToken = ++sequence; state = State.CLOSING; }
    }
    void unavailable(Object candidate, long token) {
        if (owns(candidate, token)) { sessionToken = ++sequence; state = State.UNAVAILABLE; }
    }
    boolean closed(Object candidate, long token) {
        if (!owns(candidate, token)) return false;
        owner = null;
        device = null;
        sessionToken = ++sequence;
        state = State.IDLE;
        return true;
    }
    boolean isFree() { return owner == null; }
    State state() { return state; }
}
