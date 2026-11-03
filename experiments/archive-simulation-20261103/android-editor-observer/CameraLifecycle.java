package com.xiaopeng.qa.camera;

/** Service-camera admission shared by this class loader; local state owns callback lifetime.
 * No waiter list. Denied new services do not retain a ticket or another service's looper.
 * This is not a gate for unrelated activities or other processes that open cameras.
 */
final class CameraLifecycle<D> {
    static final class Ticket<D> {
        private D device;
        private boolean opened, closing, terminal;
    }
    private static final Object LOCK = new Object();
    private static Ticket<?> admitted;
    private Ticket<D> active;
    boolean hasWork() { synchronized (LOCK) { return active != null; } }
    static String containmentStatus() {
        synchronized (LOCK) {
            if (admitted == null) return "AVAILABLE";
            return admitted.closing ? "WAITING_FOR_CAMERA_CLOSED"
                    : admitted.device == null ? "WAITING_FOR_CAMERA_CALLBACK" : "CAMERA_DEVICE_OWNED";
        }
    }
    Ticket<D> begin() {
        synchronized (LOCK) {
            if (active != null) throw new IllegalStateException("own camera request still outstanding");
            if (admitted != null) return null;
            active = new Ticket<>();
            admitted = active;
            return active;
        }
    }
    private boolean observe(Ticket<D> ticket, D device) {
        if (ticket != active || ticket == null || ticket.terminal || device == null) return false;
        if (ticket.device != null && ticket.device != device) return false;
        ticket.device = device;
        return true;
    }
    boolean opened(Ticket<D> ticket, D device) {
        synchronized (LOCK) {
            if (!observe(ticket, device) || ticket.opened || ticket.closing) return false;
            ticket.opened = true;
            return true;
        }
    }
    void closing(Ticket<D> ticket, D device) {
        synchronized (LOCK) { if (observe(ticket, device)) ticket.closing = true; }
    }
    void closing(D device) {
        synchronized (LOCK) { if (active != null && active.device == device) active.closing = true; }
    }
    void rejected(Ticket<D> ticket) {
        synchronized (LOCK) {
            if (ticket != null && ticket == active && ticket.device == null) finish(ticket);
        }
    }
    void closed(Ticket<D> ticket, D device) {
        synchronized (LOCK) { if (observe(ticket, device)) finish(ticket); }
    }
    private void finish(Ticket<D> ticket) {
        ticket.terminal = true;
        active = null;
        if (admitted == ticket) admitted = null;
    }
}
