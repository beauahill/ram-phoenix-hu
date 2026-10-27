package com.ramphoenix.launcher;

import java.io.Closeable;
import java.io.IOException;

/** Owns the one OBD worker and its socket, including a socket still connecting. */
final class ObdWorker {
    interface Task { void run(Session session); }

    private final Task task;
    private boolean wanted;
    private Session current;

    ObdWorker(Task task) { this.task = task; }

    synchronized void start() {
        wanted = true;
        if (current == null) startLocked();
    }

    void stop() {
        final Session old;
        final Closeable socket;
        synchronized (this) {
            wanted = false;
            old = current;
            if (old == null) return;
            old.cancelled = true;
            socket = old.socket;
            // Keep ownership until the worker's finally block also closes it.
            // That block must not start a replacement ahead of an in-flight close.
        }
        // Interrupt sleep and close a blocked connect/read. A replacement waits
        // for this worker's finally block; it cannot tear down a new connection.
        old.thread.interrupt();
        close(socket);
    }

    private void startLocked() {
        Session next = new Session();
        current = next;
        next.thread = new Thread(() -> {
            try { task.run(next); }
            finally {
                Closeable socket;
                synchronized (ObdWorker.this) {
                    next.cancelled = true;
                    socket = next.socket;
                    next.socket = null;
                }
                close(socket);
                synchronized (ObdWorker.this) {
                    if (current == next) {
                        current = null;
                        if (wanted) startLocked();
                    }
                }
            }
        }, "obd");
        next.thread.start();
    }

    final class Session {
        private boolean cancelled;
        private Thread thread;
        private Closeable socket;

        boolean active() {
            synchronized (ObdWorker.this) {
                return wanted && !cancelled && current == this;
            }
        }

        /** Register before connect(), so stop() can abort a blocked connection. */
        void attach(Closeable value) throws InterruptedException {
            synchronized (ObdWorker.this) {
                if (active()) { socket = value; return; }
            }
            close(value);
            throw new InterruptedException("OBD session stopped");
        }

        void release(Closeable value) {
            synchronized (ObdWorker.this) {
                if (socket == value) socket = null;
            }
        }

        /** A retired worker may close its socket, but may not publish state. */
        boolean publish(Runnable update) {
            synchronized (ObdWorker.this) {
                if (!active()) return false;
                update.run();
                return true;
            }
        }
    }

    private static void close(Closeable socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
}
