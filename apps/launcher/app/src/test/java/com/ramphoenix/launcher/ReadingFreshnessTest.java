package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.ramphoenix.launcher.VehicleData.Gauge.*;

public class ReadingFreshnessTest {
    private RamPaGauges seeded(){
        RamPaGauges g=new RamPaGauges();g.profile(RamPaGauges.VERIFIED_PROFILE);
        g.update(234,32900,100);g.update(235,32901,100);g.update(236,32902,100);g.update(237,370,100);return g;
    }
    @Test public void cachedRegistrationIsNotProofOfCurrentVehicleData(){
        RamPaGauges g=seeded();assertNotNull(g.value(COOLANT,true));
        assertNull(g.liveValue(COOLANT,true,101));
    }
    @Test public void changedPacketConfirmsItsOtherUnchangedGaugeWords(){
        RamPaGauges g=seeded();g.update(237,380,200);
        assertNotNull(g.liveValue(COOLANT,true,201));assertNotNull(g.liveValue(OIL_TEMPERATURE,true,201));
        assertEquals(38f,g.liveValue(OIL_PRESSURE,true,201),0);
    }
    @Test public void repeatedCacheAndReadRequestsCannotKeepADeadSourceAlive(){
        RamPaGauges g=seeded();g.update(237,380,200);
        g.update(237,380,120199);g.profile(RamPaGauges.VERIFIED_PROFILE);
        assertNotNull(g.liveValue(OIL_PRESSURE,true,120200));
        assertNull(g.liveValue(OIL_PRESSURE,true,120201));
    }
    @Test public void invalidChangingWordsDoNotQualifyAsFreshGaugeData(){
        RamPaGauges g=seeded();g.update(234,65535,200);g.update(237,65535,201);
        assertNull(g.liveValue(OIL_TEMPERATURE,true,202));
    }
    @Test public void disconnectAndProfileChangeRetirePreviousProof(){
        RamPaGauges g=seeded();g.update(237,380,200);assertNull(g.liveValue(OIL_PRESSURE,false,201));
        g.clear();g.profile(RamPaGauges.VERIFIED_PROFILE);g.update(237,380,202);
        assertNull(g.liveValue(OIL_PRESSURE,true,203));
        g.update(237,390,204);assertNotNull(g.liveValue(OIL_PRESSURE,true,205));
        g.profile(0);g.profile(RamPaGauges.VERIFIED_PROFILE);g.update(237,390,206);
        assertNull(g.liveValue(OIL_PRESSURE,true,207));
    }
    @Test public void monotonicLeaseRejectsMissingBackwardsAndExpiredSamples(){
        assertFalse(ReadingAge.fresh(ReadingAge.NONE,50,30000));assertFalse(ReadingAge.fresh(100,99,30000));
        assertTrue(ReadingAge.fresh(0,30000,30000));assertFalse(ReadingAge.fresh(0,30001,30000));
    }
}
