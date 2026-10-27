package com.ramphoenix.launcher;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ObdProbeTest {
    @Test public void explicitRequestsKeepServiceAndLeadingZeroDid() {
        List<ObdProbe.Request> r = ObdProbe.parse("7e0:21d2,212D,220168,0168");
        assertEquals(4, r.size());
        assertEquals("7E0", r.get(0).route);
        assertEquals("21D2", r.get(0).hex);
        assertEquals("220168", r.get(2).hex);
        assertEquals("0168", r.get(3).hex);
    }

    @Test public void asymmetricRouteStaysAttachedToItsRequest() {
        List<ObdProbe.Request> r = ObdProbe.parse("7E0:0105;740/4C0:22A0F1");
        assertEquals("740/4C0", r.get(1).route);
        assertEquals("22A0F1", r.get(1).hex);
    }

    @Test public void resetWriteRoutineAndAdapterCommandsAreRejected() {
        for (String s : new String[]{"7E0:04", "7E0:1101", "7E0:2EA00100", "7E0:310101", "7E0:ATZ", "7E0:22A001,04"})
            assertThrows(s, IllegalArgumentException.class, () -> ObdProbe.parse(s));
    }

    @Test public void malformedRoutesAndIncompleteRequestsAreRejected() {
        for (String s : new String[]{"7E0", "800:0105", "7E0:22A0", "7E0:22A00100", "7E0:0105,", "7E0:0105;"})
            assertThrows(s, IllegalArgumentException.class, () -> ObdProbe.parse(s));
        assertEquals(0, ObdProbe.parse("").size());
    }

    @Test public void probesAreBounded() {
        String twelve = "7E0:" + String.join(",", java.util.Collections.nCopies(12, "0105"));
        assertEquals(12, ObdProbe.parse(twelve).size());
        assertThrows(IllegalArgumentException.class, () -> ObdProbe.parse(twelve + ",0105"));
    }
}
