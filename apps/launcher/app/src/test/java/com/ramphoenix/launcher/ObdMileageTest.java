package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class ObdMileageTest {
    private ObdMileage standard(int miles){return ObdMileage.decode(ObdMileage.STANDARD,String.format("41A6%08X",(long)Math.ceil(miles*1.609344*10)),100);}
    @Test public void standardOdometerUsesAllFourBytesAndConvertsKm(){ObdMileage r=standard(71000);assertNotNull(r);assertEquals(71000d,r.miles,.07);}
    @Test public void ramCandidateUsesMetersNotTheCoarseEightMileCounter(){ObdMileage r=ObdMileage.decode(ObdMileage.RAM,String.format("6203BC%08X",114263424),100);assertEquals(71000d,r.miles,.00001);}
    @Test public void manufacturerValuesAreUnsigned(){ObdMileage r=ObdMileage.decode(ObdMileage.RAM,"6203BC80000000",100);assertNotNull(r);assertTrue(r.miles>1_000_000);}
    @Test public void rejectsUnsupportedTruncatedAmbiguousAndSentinelReplies(){
        for(String value:new String[]{"","NODATA","7F0112","41A60001","41A6FFFFFFFF","41A600000000","410D10","41A60000000141A600000002"})assertNull(ObdMileage.decode(ObdMileage.STANDARD,value,100));
        assertNull(ObdMileage.decode(ObdMileage.RAM,"6203BC00000000",100));assertNull(ObdMileage.decode(ObdMileage.RAM,"6203BCFFFFFFFF",100));
    }
    @Test public void connectionAgeIsBounded(){ObdMileage r=standard(71000);assertTrue(r.fresh(100));assertFalse(r.fresh(99));assertFalse(r.fresh(100+ObdMileage.FRESH_MS+1));}
    @Test public void manufacturerReadingNeedsOneInitialClusterCheck(){
        MileageState s=new MileageState();ObdMileage r=ObdMileage.decode(ObdMileage.RAM,"6203BC06CF88C0",100);s.observe(r,1000);
        assertEquals(-1,s.value());assertTrue(s.candidate>=0);s.confirm(1100);assertEquals((int)Math.floor(r.miles),s.value());assertEquals(ObdMileage.RAM,s.confirmedSource);
    }
    @Test public void manualOverrideSurvivesAutomaticUpdates(){MileageState s=new MileageState();s.manual=71234;s.manualOnly=true;s.observe(standard(71000),1000);assertEquals(71234,s.value());s.manualOnly=false;assertEquals(71000,s.value());}
    @Test public void rollbackCannotOverwriteTheAcceptedMileage(){MileageState s=new MileageState();s.observe(standard(71000),1000);s.observe(standard(50000),2000);assertEquals(71000,s.value());assertEquals(50000,s.candidate);}
    @Test public void anImpossibleJumpRequiresReviewInsteadOfChangingReminders(){MileageState s=new MileageState();s.observe(standard(71000),1000);s.observe(standard(200000),2000);assertEquals(71000,s.value());assertEquals(200000,s.candidate);}
    @Test public void staleCandidateCannotBeApproved(){MileageState s=new MileageState();s.observe(ObdMileage.decode(ObdMileage.RAM,"6203BC06CF88C0",100),1000);assertThrows(IllegalArgumentException.class,()->s.confirm(400000));assertEquals(-1,s.value());}
}
