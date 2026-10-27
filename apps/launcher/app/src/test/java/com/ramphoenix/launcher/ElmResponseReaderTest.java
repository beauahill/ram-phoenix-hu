package com.ramphoenix.launcher;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class ElmResponseReaderTest {
    @Test public void consumesExactlyOnePromptDelimitedResponse() throws Exception {
        InputStream in = bytes("41 05 5A\r\n>ATRV\r13.8V>");
        assertEquals("41055A", ElmResponseReader.read(in, 1000, 1000));
        assertEquals("ATRV13.8V", ElmResponseReader.read(in, 1000, 1000));
    }

    @Test(expected = SocketTimeoutException.class)
    public void partialReplyWithoutPromptCannotBeAccepted() throws Exception {
        ElmResponseReader.read(bytes("41055A\r"), 20, 20);
    }

    @Test(expected = EOFException.class)
    public void closedSocketFailsImmediately() throws Exception {
        ElmResponseReader.read(new InputStream() {
            @Override public int available() { return 1; }
            @Override public int read() { return -1; }
        }, 1000, 1000);
    }

    @Test(timeout = 2000, expected = SocketTimeoutException.class)
    public void searchingNoiseCannotExtendDeadlineForever() throws Exception {
        ElmResponseReader.read(new InputStream() {
            private final byte[] prefix = "SEARCHING...".getBytes(StandardCharsets.US_ASCII);
            private int pos;
            @Override public int available() { return 1; }
            @Override public int read() { return pos < prefix.length ? prefix[pos++] : '.'; }
        }, 20, 20);
    }

    @Test public void cancellationInterruptsSilentAdapterWait() throws Exception {
        Thread.currentThread().interrupt();
        try {
            ElmResponseReader.read(bytes(""), 1000, 1000);
            fail("Cancelled read kept waiting");
        } catch (InterruptedException expected) {
            // The worker exits instead of sending another request into a partial transaction.
        } finally { Thread.interrupted(); }
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }
}
