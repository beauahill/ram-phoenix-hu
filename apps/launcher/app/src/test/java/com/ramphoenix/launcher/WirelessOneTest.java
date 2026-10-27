package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic wire examples derived from reference-app fields; not truck captures. */
public class WirelessOneTest {
    private byte[] target(int kpa) { return new byte[]{(byte)kpa,(byte)(kpa>>>8),0}; }
    private SuspensionState ready(long now) {
        SuspensionState s=new SuspensionState();s.reset(false);
        s.accept(WirelessOneProtocol.MODE,new byte[]{0},now);
        s.accept(WirelessOneProtocol.FAULTS,new byte[]{0,0},now);
        s.accept(WirelessOneProtocol.STATE,new byte[]{1,89,1},now); // 345 kPa measured, compressor on
        s.accept(WirelessOneProtocol.TARGET,target(552),now); // 80 psi target is separate
        return s;
    }
    @Test public void fourLocalProfilesDoNotConsumeThreeManifoldPresets() {
        assertArrayEquals(new int[]{80,50,30,5},new int[]{WirelessOneProtocol.presetPsi(0,5),WirelessOneProtocol.presetPsi(1,5),WirelessOneProtocol.presetPsi(2,5),WirelessOneProtocol.presetPsi(3,5)});
        assertArrayEquals(new byte[]{0x28,2,0},WirelessOneProtocol.target(80,5,100));
        assertArrayEquals(new byte[]{0x59,1,0},WirelessOneProtocol.target(50,5,100));
        assertArrayEquals(new byte[]{(byte)0xcf,0,0},WirelessOneProtocol.target(30,5,100));
    }
    @Test public void emptyHonorsBothControllerAndKitMinimum() {
        assertArrayEquals(new byte[]{35,0,0},WirelessOneProtocol.target(5,5,100));
        assertEquals(10,WirelessOneProtocol.presetPsi(3,10));
        assertThrows(IllegalArgumentException.class,()->WirelessOneProtocol.target(5,10,100));
    }
    @Test public void kitLimitsAreEnforcedWithoutSilentlyChangingTheRequestedPressure() {
        assertThrows(IllegalArgumentException.class,()->WirelessOneProtocol.target(80,5,35));
        assertThrows(IllegalArgumentException.class,()->WirelessOneProtocol.target(0,5,100));
        assertThrows(IllegalArgumentException.class,()->WirelessOneProtocol.target(101,5,100));
        assertThrows(IllegalArgumentException.class,()->WirelessOneProtocol.target(50,-1,-1));
        assertFalse(WirelessOneProtocol.validLimits(35,5));
    }
    @Test public void measuredPressureIsNotTheSelectedTarget() {
        SuspensionState s=ready(100);assertEquals(345,s.actualKpa);assertEquals(552,s.targetKpa);
        assertEquals(50,WirelessOneProtocol.psi(s.actualKpa),.1);assertEquals("Inflating",s.activity());
    }
    @Test public void missingOrUnverifiedSetupCannotSend() {
        SuspensionState s=ready(100);
        assertNull(s.blocked(100,true,true,5,100));
        assertNotNull(s.blocked(100,false,true,5,100));
        assertNotNull(s.blocked(100,true,false,5,100));
        assertNotNull(s.blocked(100,true,true,-1,-1));
    }
    @Test public void everyRequiredReadingMustBeFresh() {
        SuspensionState s=ready(100);
        assertNotNull(s.blocked(99,true,true,5,100));
        assertNotNull(s.blocked(100+SuspensionState.FRESH_MS+1,true,true,5,100));
        s.accept(WirelessOneProtocol.STATE,new byte[]{0,89,1},9000);
        s.accept(WirelessOneProtocol.TARGET,target(552),9000);
        assertNotNull(s.blocked(9000,true,true,5,100)); // fresh pressure cannot refresh old mode/fault data
    }
    @Test public void bootloaderOrFaultsBlockControls() {
        SuspensionState s=ready(100);s.accept(WirelessOneProtocol.MODE,new byte[]{1},100);
        assertNotNull(s.blocked(100,true,true,5,100));
        s.accept(WirelessOneProtocol.MODE,new byte[]{0},100);s.accept(WirelessOneProtocol.FAULTS,new byte[]{0,1},100);
        assertNotNull(s.blocked(100,true,true,5,100));
    }
    @Test public void tankModeMustBeCheckedWhenCharacteristicExists() {
        SuspensionState s=ready(100);s.tankPresent=true;assertNotNull(s.blocked(100,true,true,5,100));
        s.accept(WirelessOneProtocol.TANK,new byte[]{1},100);assertNotNull(s.blocked(100,true,true,5,100));
        s.accept(WirelessOneProtocol.TANK,new byte[]{0},100);assertNull(s.blocked(100,true,true,5,100));
    }
    @Test public void malformedReadInvalidatesTheOldValidField() {
        SuspensionState s=ready(100);
        assertThrows(IllegalArgumentException.class,()->s.accept(WirelessOneProtocol.STATE,new byte[]{1},110));
        assertEquals(-1,s.actualKpa);assertNotNull(s.blocked(110,true,true,5,100));
        assertThrows(IllegalArgumentException.class,()->s.accept(WirelessOneProtocol.TARGET,new byte[]{-1,-1,0},110));
        assertEquals(-1,s.targetKpa);
    }
    @Test public void unavailableAndUnsupportedTargetsAreRejected() {
        for(byte[] bytes:new byte[][]{null,new byte[0],new byte[]{35},new byte[]{-1,-1,0},new byte[]{0,0,0},new byte[]{35,0,4}})
            assertThrows(IllegalArgumentException.class,()->WirelessOneProtocol.targetPressure(bytes));
    }
    @Test public void disconnectClearsAllReadinessAndReadings() {
        SuspensionState s=ready(100);s.reset(false);assertEquals(-1,s.actualKpa);assertEquals(-1,s.targetKpa);
        assertNotNull(s.blocked(101,true,true,5,100));
    }
    @Test public void aWriteAttemptOrGattAckIsNotAConfirmedPressureChange() {
        SuspensionCommand c=new SuspensionCommand(1,552,100);
        assertTrue(c.beginWrite(1,110));assertFalse(c.confirmed);
        c.written(120);assertFalse(c.confirmed);
        assertFalse(c.observe(1,345,130));assertTrue(c.observe(1,552,140));
    }
    @Test public void oldOrOtherConnectionReadbackCannotAcknowledgeANewRequest() {
        SuspensionCommand c=new SuspensionCommand(1,552,100);assertTrue(c.beginWrite(1,110));c.written(120);
        assertFalse(c.observe(1,552,119));assertFalse(c.observe(2,552,130));assertFalse(c.confirmed);
    }
    @Test public void requestsCannotBeWrittenTwiceOrAfterTheShortQueueDeadline() {
        SuspensionCommand c=new SuspensionCommand(1,552,100);assertTrue(c.beginWrite(1,110));assertFalse(c.beginWrite(1,120));
        c=new SuspensionCommand(1,552,100);assertFalse(c.beginWrite(1,851));
        c=new SuspensionCommand(1,552,100);assertFalse(c.beginWrite(2,110));
    }
    @Test public void cameraDisconnectOrAppHandoffCancellationCannotReplay() {
        SuspensionCommand c=new SuspensionCommand(1,552,100);c.cancel();assertFalse(c.beginWrite(1,110));
        c=new SuspensionCommand(1,552,100);assertTrue(c.beginWrite(1,110));c.written(120);c.cancel();
        assertFalse(c.observe(1,552,130));assertFalse(c.beginWrite(2,140));
    }
    @Test public void unconfirmedRequestsTimeOutWithoutAnotherWrite() {
        SuspensionCommand c=new SuspensionCommand(1,552,100);assertTrue(c.beginWrite(1,110));c.written(120);
        assertTrue(c.expired(6121));assertFalse(c.beginWrite(1,6121));assertFalse(c.confirmed);
    }
}
