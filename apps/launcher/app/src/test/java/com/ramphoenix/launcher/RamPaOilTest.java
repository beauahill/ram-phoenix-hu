package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class RamPaOilTest {
    @Test public void liveFahrenheitPacketsMatchCoolingCluster() {
        assertEquals("93°", Fmt.tempF(RamPaOil.decodeTemperature(32901)));
        assertEquals("91°", Fmt.tempF(RamPaOil.decodeTemperature(32899)));
    }

    @Test public void celsiusPacketRetainsItsUnit() {
        assertEquals(37f, RamPaOil.decodeTemperature(77), 0f);
        assertEquals("99°", Fmt.tempF(RamPaOil.decodeTemperature(77)));
    }

    @Test public void missingSentinelAndImplausibleValuesStayUnavailable() {
        for (int value : new int[]{0, -1, 65535, 32767, 65536, 5000})
            assertNull(RamPaOil.decodeTemperature(value));
    }

    @Test public void valuesRequireConfirmedProfileAndLiveIpc() {
        RamPaOil oil = new RamPaOil();
        oil.temperature(32899);
        assertNull(oil.temperatureC(true));
        oil.profile(RamPaOil.VERIFIED_PROFILE);
        assertEquals("91°", Fmt.tempF(oil.temperatureC(true)));
        assertNull(oil.temperatureC(false));
        oil.profile(0);
        assertNull(oil.temperatureC(true));
    }

    @Test public void clearDoesNotResurrectOldTemperatureOnReconnect() {
        RamPaOil oil = new RamPaOil();
        oil.profile(RamPaOil.VERIFIED_PROFILE);
        oil.temperature(32899);
        oil.clear();
        oil.profile(RamPaOil.VERIFIED_PROFILE);
        assertNull(oil.temperatureC(true));
        oil.temperature(32898);
        assertEquals("90°", Fmt.tempF(oil.temperatureC(true)));
    }
}
