package com.ramphoenix.launcher;

/** One explicit request, one write attempt, subsequent readback. Never persisted/replayed. */
final class SuspensionCommand {
    final int generation, targetKpa;
    final long requestedAt;
    long writtenAt = -1;
    boolean attempted, confirmed, cancelled;
    SuspensionCommand(int generation, int targetKpa, long now) {
        this.generation = generation; this.targetKpa = targetKpa; requestedAt = now;
    }
    boolean beginWrite(int currentGeneration, long now) {
        if (cancelled || attempted || generation != currentGeneration || now < requestedAt || now - requestedAt > 750)
            return false;
        attempted = true; return true;
    }
    void written(long now) { if (attempted && !cancelled) writtenAt = now; }
    boolean observe(int currentGeneration, int target, long readStartedAt) {
        if (!cancelled && attempted && writtenAt >= 0 && generation == currentGeneration
                && readStartedAt >= writtenAt && target == targetKpa) confirmed = true;
        return confirmed;
    }
    boolean expired(long now) { return !confirmed && now - (writtenAt >= 0 ? writtenAt : requestedAt) > 6000; }
    void cancel() { cancelled = true; }
}
