package com.ramphoenix.launcher;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ObdWorkerTest {
    @Test public void restartClosesConnectingSocketAndWaitsForOldCleanup() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1), firstClosed = new CountDownLatch(1);
        CountDownLatch allowOldExit = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        CountDownLatch secondClosed = new CountDownLatch(1), finished = new CountDownLatch(2);
        AtomicInteger starts = new AtomicInteger(), active = new AtomicInteger(), maxActive = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ObdWorker worker = new ObdWorker(session -> {
            int n = starts.incrementAndGet();
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (n == 1) {
                    session.attach(firstClosed::countDown);
                    firstEntered.countDown();
                    awaitIgnoringInterrupts(firstClosed); // Like a native connect: only close aborts it.
                    assertFalse(session.publish(published::incrementAndGet));
                    awaitIgnoringInterrupts(allowOldExit); // Hold the old worker in cleanup.
                } else {
                    session.attach(secondClosed::countDown);
                    assertTrue(session.publish(published::incrementAndGet));
                    secondEntered.countDown();
                    awaitIgnoringInterrupts(secondClosed);
                }
            } catch (Throwable t) { failure.set(t); }
            finally { active.decrementAndGet(); finished.countDown(); }
        });
        try {
            worker.start();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            worker.start(); // Repeated resumes do not duplicate the current worker.
            worker.stop();
            assertTrue(firstClosed.await(5, TimeUnit.SECONDS));
            worker.start();
            assertEquals(1, starts.get()); // Restart must wait even if connect is unwinding slowly.
            allowOldExit.countDown();
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get());
            assertEquals(1, published.get());
        } finally {
            worker.stop();
            allowOldExit.countDown();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
    }

    @Test public void restartWaitsForSocketCloseEvenWhenTaskHasAlreadyExited() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), allowTaskExit = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1), bothClosersEntered = new CountDownLatch(2);
        CountDownLatch allowCloseFinish = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        CountDownLatch secondClosed = new CountDownLatch(1), finished = new CountDownLatch(2);
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ObdWorker worker = new ObdWorker(session -> {
            try {
                if (starts.incrementAndGet() == 1) {
                    session.attach(() -> {
                        closeEntered.countDown();
                        bothClosersEntered.countDown();
                        awaitIgnoringInterrupts(allowCloseFinish);
                    });
                    entered.countDown();
                    awaitIgnoringInterrupts(allowTaskExit);
                } else {
                    session.attach(secondClosed::countDown);
                    secondEntered.countDown();
                    awaitIgnoringInterrupts(secondClosed);
                }
            } catch (Throwable t) { failure.set(t); }
            finally { finished.countDown(); }
        });
        worker.start();
        Thread stopping = new Thread(worker::stop);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            stopping.start();
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            worker.start();
            allowTaskExit.countDown();
            assertTrue(bothClosersEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, starts.get());
            allowCloseFinish.countDown();
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
        } finally {
            allowTaskExit.countDown();
            allowCloseFinish.countDown();
            stopping.join(5000);
            worker.stop();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
    }

    @Test public void cancelledSessionCannotStartAnotherSocketOrPublishStaleState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger rejectedSocketCloses = new AtomicInteger(), writes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ObdWorker worker = new ObdWorker(session -> {
            try {
                session.attach(closed::countDown);
                entered.countDown();
                awaitIgnoringInterrupts(closed);
                assertFalse(session.publish(writes::incrementAndGet));
                try {
                    session.attach(rejectedSocketCloses::incrementAndGet);
                    fail("Retired session attached a fallback socket");
                } catch (InterruptedException expected) { }
            } catch (Throwable t) { failure.set(t); }
            finally { finished.countDown(); }
        });
        worker.start();
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
        finally { worker.stop(); }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(1, rejectedSocketCloses.get());
        assertEquals(0, writes.get());
        assertNull(failure.get());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                if (latch.await(10, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException ignored) { }
        }
        throw new AssertionError("Timed out waiting for test coordination");
    }
}
