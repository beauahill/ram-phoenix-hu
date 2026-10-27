package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.ramphoenix.launcher.VehicleData.Gauge.*;

public class RamPaGaugesTest {
    private RamPaGauges gauges() { RamPaGauges g=new RamPaGauges();g.profile(RamPaGauges.VERIFIED_PROFILE);return g; }
    @Test public void previouslyVerifiedOilTemperatureStillMatchesCluster() {
        assertEquals("93°",Fmt.tempF(RamPaGauges.decodeTemperature(32901)));
        assertEquals("91°",Fmt.tempF(RamPaGauges.decodeTemperature(32899)));
    }
    @Test public void celsiusAndFahrenheitWordsRetainTheirUnits() {
        assertEquals(37f,RamPaGauges.decodeTemperature(77),0f);
        assertEquals("99°",Fmt.tempF(RamPaGauges.decodeTemperature(77)));
        assertEquals(0f,RamPaGauges.decodeTemperature(0x8000|72),0f);
    }
    @Test public void invalidTemperatureWordsStayUnavailable() {
        for(int raw:new int[]{0,-1,0xffff,0x7fff,65536,5000})assertNull(RamPaGauges.decodeTemperature(raw));
    }
    @Test public void oilPressureUsesUnitTaggedTenthsRatherThanObdScaling() {
        assertEquals(37f,RamPaGauges.decodePressure(370),0f);
        assertEquals(37f,RamPaGauges.decodePressure(0x4000|2550),.02f);
        assertEquals(43.5113f,RamPaGauges.decodePressure(0x8000|30),.001f);
    }
    @Test public void invalidPressureWordsAndUnconfirmedUnitStayUnavailable() {
        for(int raw:new int[]{-1,65536,0xffff,0x3fff,0x7fff,0xbfff,0xc000|370,2501})assertNull(RamPaGauges.decodePressure(raw));
    }
    @Test public void fieldsRequireTheConfirmedProfileAndConnectedIpc() {
        RamPaGauges g=new RamPaGauges();g.update(235,32899);
        assertNull(g.value(OIL_TEMPERATURE,true));g.profile(RamPaGauges.VERIFIED_PROFILE);
        assertEquals("91°",Fmt.tempF(g.value(OIL_TEMPERATURE,true)));
        assertNull(g.value(OIL_TEMPERATURE,false));assertEquals(0,g.availableMask(false));
        g.profile(0);assertNull(g.value(OIL_TEMPERATURE,true));
    }
    @Test public void clearDoesNotResurrectOldFieldsOnReconnect() {
        RamPaGauges g=gauges();g.update(235,32899);g.update(237,370);g.clear();g.profile(RamPaGauges.VERIFIED_PROFILE);
        assertEquals(0,g.availableMask(true));assertNull(g.value(OIL_TEMPERATURE,true));
        g.update(235,32898);assertEquals("90°",Fmt.tempF(g.value(OIL_TEMPERATURE,true)));
    }
    @Test public void allFourIndependentCanFieldsWorkWithoutAnObdValue() {
        // Synthetic additional-field examples; only the oil-temperature word is a recorded truck sample.
        RamPaGauges g=gauges();g.update(234,32903);g.update(235,32899);g.update(236,32892);g.update(237,370);
        assertEquals("95°",Fmt.tempF(g.value(COOLANT,true)));assertEquals("84°",Fmt.tempF(g.value(TRANSMISSION,true)));
        assertEquals(37f,g.value(OIL_PRESSURE,true),0f);assertEquals(15,g.availableMask(true));
        g.update(236,0xffff);assertNull(g.value(TRANSMISSION,true));assertEquals(13,g.availableMask(true));
    }
    @Test public void initialZeroCacheIsNotEvidenceOfZeroOilPressure() {
        RamPaGauges g=gauges();g.update(237,0);assertNull(g.value(OIL_PRESSURE,true));
        g.update(237,370);assertEquals(37f,g.value(OIL_PRESSURE,true),0f);
        g.update(237,0);assertEquals(0f,g.value(OIL_PRESSURE,true),0f);
        g.update(237,0xffff);g.update(237,0);assertNull(g.value(OIL_PRESSURE,true));
    }
    @Test public void taggedZeroCanBeDistinguishedFromTheDefaultZeroWord() {
        RamPaGauges g=gauges();g.update(237,0x4000);assertEquals(0f,g.value(OIL_PRESSURE,true),0f);
        g.update(237,0);assertEquals(0f,g.value(OIL_PRESSURE,true),0f);
    }
    @Test public void profileChangesCannotReuseReadingsFromAnotherDecoder() {
        RamPaGauges g=gauges();g.update(235,32899);g.profile(0);g.update(235,77);
        g.profile(RamPaGauges.VERIFIED_PROFILE);assertNull(g.value(OIL_TEMPERATURE,true));
        g.update(235,32899);g.profile(RamPaGauges.VERIFIED_PROFILE);assertEquals("91°",Fmt.tempF(g.value(OIL_TEMPERATURE,true)));
    }
}
