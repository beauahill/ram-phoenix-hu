package com.ramphoenix.camera;

/** Capture-result liveness, driven only on the controller's camera thread. */
final class PreviewWatchdog {
    static final long START_TIMEOUT_MS = 6000, STALL_TIMEOUT_MS = 2000;
    private boolean running, seenFrame;
    private long startedAt, lastFrameAt;

    void start(long now) { running = true; seenFrame = false; startedAt = now; }
    void stop() { running = false; }

    /** True for the first successful result of this attempt. */
    boolean frame(long now) {
        if (!running) return false;
        boolean first = !seenFrame;
        seenFrame = true;
        lastFrameAt = now;
        return first;
    }

    boolean expired(long now) {
        return running && (seenFrame ? now - lastFrameAt >= STALL_TIMEOUT_MS
                : now - startedAt >= START_TIMEOUT_MS);
    }

    long startupMs(long now) { return now - startedAt; }
}
