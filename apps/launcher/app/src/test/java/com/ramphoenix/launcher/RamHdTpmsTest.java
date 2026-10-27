package com.ramphoenix.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

public class RamHdTpmsTest {
    // Captured from this 2019 RAM, not generated to fit the cluster values.
    private static String[] recorded() {
        return new String[]{"00C0:62A0F11070001:003E605D0E0E00", "00C0:62A0F21130001:0043605D0E0E00",
                "00C0:62A0F313A9001:003A605D0E0E00", "00C0:62A0F41469001:0042605D0E0E00"};
    }

    @Test public void recordedPerWheelRepliesMatchClusterPositionsAndWholePsiDisplay() {
        RamHdTpms.Snapshot snapshot = RamHdTpms.decode("62A0A50F", recorded(), 1000);
        assertNotNull(snapshot);
        assertEquals(61.0319f, snapshot.tirePsi(0, 1000), 0.002f);
        assertEquals(63.8166f, snapshot.tirePsi(1, 1000), 0.002f);
        assertEquals(72.9975f, snapshot.tirePsi(2, 1000), 0.002f);
        assertEquals(75.7822f, snapshot.tirePsi(3, 1000), 0.002f);
        assertEquals("61", Fmt.psi(snapshot.tirePsi(0, 1000)));
        assertEquals("63", Fmt.psi(snapshot.tirePsi(1, 1000)));
        assertEquals("72", Fmt.psi(snapshot.tirePsi(2, 1000)));
        assertEquals("75", Fmt.psi(snapshot.tirePsi(3, 1000)));
    }

    @Test public void missingOrInvalidValidityCannotLookLikeLivePressures() {
        assertNull(RamHdTpms.decode("7F2231", recorded(), 1000));
        assertNull(RamHdTpms.decode("62A0A500", recorded(), 1000));
        assertNull(RamHdTpms.decode("62A0A507", recorded(), 1000));
        assertNull(RamHdTpms.decode("", recorded(), 1000));
    }

    @Test public void failedWheelReadDoesNotProduceAZeroPressureOrBorrowAnotherWheel() {
        String[] replies = recorded();
        replies[1] = "";
        replies[2] = "62A0F3FFFF";
        RamHdTpms.Snapshot snapshot = RamHdTpms.decode("62A0A50F", replies, 1000);
        assertNull(snapshot.tirePsi(1, 1000));
        assertNull(snapshot.tirePsi(2, 1000));
        assertNotNull(snapshot.tirePsi(0, 1000));
        assertNotNull(snapshot.tirePsi(3, 1000));
    }

    @Test public void oldReadingsExpireEvenIfBluetoothStillReportsConnected() {
        RamHdTpms.Snapshot snapshot = RamHdTpms.decode("62A0A50F", recorded(), 1000);
        assertNotNull(snapshot.tirePsi(0, 1000 + RamHdTpms.MAX_AGE_MS - 1));
        assertNull(snapshot.tirePsi(0, 1000 + RamHdTpms.MAX_AGE_MS));
        assertNull(snapshot.tirePsi(0, 999));
    }

    @Test public void malformedMultiframeReplyCannotBeUsedAsPressure() {
        assertNull(ObdPayload.data("00C0:62A0F11070", "62A0F1"));
        assertNull(ObdPayload.data("00C0:62A0F11070002:003E605D0E0E00", "62A0F1"));
        assertNull(ObdPayload.data(recorded()[0], "62A0F2"));
    }

    @Test public void framePaddingIsNotPartOfThePayload() {
        assertEquals(9, ObdPayload.data(recorded()[0], "62A0F1").length);
        assertArrayEquals(new byte[]{15}, ObdPayload.data("62 A0 A5 0F", "62A0A5"));
    }
}
