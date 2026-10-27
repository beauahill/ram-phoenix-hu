package com.ramphoenix.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewWatchdogTest {
    @Test public void sessionThatNeverProducesFramesTimesOut() {
        PreviewWatchdog w = new PreviewWatchdog();
        w.start(100);
        assertFalse(w.expired(6099));
        assertTrue(w.expired(6100));
    }

    @Test public void successfulResultsKeepStreamAliveAndMeasureStartupOnce() {
        PreviewWatchdog w = new PreviewWatchdog();
        w.start(100);
        assertTrue(w.frame(185));
        assertEquals(85, w.startupMs(185));
        assertFalse(w.frame(218));
        assertFalse(w.expired(2217));
        assertTrue(w.expired(2218));
    }

    @Test public void stopCannotCauseLateRetryOrAcceptLateFrame() {
        PreviewWatchdog w = new PreviewWatchdog();
        w.start(100);
        w.stop();
        assertFalse(w.expired(10000));
        assertFalse(w.frame(10001));
    }

    @Test public void retryGetsFullStartupBudgetAndFreshFirstFrame() {
        PreviewWatchdog w = new PreviewWatchdog();
        w.start(100);
        w.frame(200);
        assertTrue(w.expired(2200));
        w.start(3000);
        assertFalse(w.expired(8999));
        assertTrue(w.frame(9000));
        assertFalse(w.expired(9001));
    }
}
