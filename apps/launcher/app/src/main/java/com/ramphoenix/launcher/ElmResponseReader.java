package com.ramphoenix.launcher;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Reads a complete ELM transaction. A missing prompt is a lost connection. */
final class ElmResponseReader {
    private ElmResponseReader() {}

    static String read(InputStream in, long timeoutMs, long searchTimeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean searching = false;
        StringBuilder response = new StringBuilder();
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (in.available() == 0) { Thread.sleep(10); continue; }
            int ch = in.read();
            if (ch < 0) throw new EOFException("OBD socket closed");
            if (ch == '>') return response.toString();
            if (ch != '\r' && ch != '\n' && ch != ' ' && ch != 0) response.append((char) ch);
            // Allow one bounded protocol-search interval, not an extension on
            // every subsequent character that can keep a dead stream alive.
            if (!searching && response.indexOf("SEARCHING") >= 0) {
                searching = true;
                deadline = Math.max(deadline,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(searchTimeoutMs));
            }
        }
        throw new SocketTimeoutException("OBD response timed out before prompt");
    }
}
