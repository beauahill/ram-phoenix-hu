package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class GaugeReadingTest {
    @Test public void canWinsWithoutRelyingOnOrAveragingTheObdValue() {
        GaugeReading reading=GaugeReading.choose(91f,98f);
        assertEquals(91f,reading.value,0f);assertEquals(GaugeReading.Source.CAN,reading.source);
        assertEquals(GaugeReading.Source.CAN,GaugeReading.choose(91f,null).source);
    }
    @Test public void missingCanFallsBackIndividuallyThenBecomesUnavailable() {
        assertEquals(GaugeReading.Source.OBD,GaugeReading.choose(null,37f).source);
        assertSame(GaugeReading.NONE,GaugeReading.choose(null,null));
    }
    @Test public void genuineZeroIsNotTreatedAsMissing() {
        GaugeReading reading=GaugeReading.choose(0f,37f);assertEquals(0f,reading.value,0f);assertEquals("CAN",reading.label());
    }
    @Test public void nonfiniteValuesNeverBecomeAvailableReadings() {
        assertEquals(GaugeReading.Source.OBD,GaugeReading.choose(Float.NaN,37f).source);
        assertSame(GaugeReading.NONE,GaugeReading.choose(Float.POSITIVE_INFINITY,Float.NaN));
    }
    @Test public void sourceLabelsReflectSelectedReadingsRatherThanAdapterConnection() {
        GaugeReading can=GaugeReading.choose(32f,null),obd=GaugeReading.choose(null,32f);
        assertEquals("GAUGES: CAN",GaugeReading.summary(can,can,GaugeReading.NONE));
        assertEquals("GAUGES: CAN + OBD",GaugeReading.summary(can,obd));
        assertEquals("GAUGES: OBD",GaugeReading.summary(obd));
        assertEquals("GAUGES UNAVAILABLE",GaugeReading.summary(GaugeReading.NONE));
    }
}
