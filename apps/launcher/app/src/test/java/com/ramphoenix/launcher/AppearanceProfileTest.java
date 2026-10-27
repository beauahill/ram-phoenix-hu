package com.ramphoenix.launcher;

import com.ramphoenix.appearance.AppearanceProfile;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppearanceProfileTest {
    @Test public void automaticTransitionsUseLocalSevenOClockBoundaries(){
        assertTrue(AppearanceProfile.night("auto",6));assertFalse(AppearanceProfile.night("auto",7));
        assertFalse(AppearanceProfile.night("auto",18));assertTrue(AppearanceProfile.night("auto",19));
        assertTrue(AppearanceProfile.night("auto",0));assertTrue(AppearanceProfile.night("auto",23));
    }
    @Test public void explicitChoiceOverridesSchedule(){
        assertFalse(AppearanceProfile.night("day",0));assertTrue(AppearanceProfile.night("night",12));
    }
    @Test public void invalidStoredPreferenceFallsBackToAutomatic(){
        assertEquals("auto",AppearanceProfile.mode(null));assertEquals("auto",AppearanceProfile.mode("legacy"));
        assertEquals("day",AppearanceProfile.mode("day"));assertEquals("night",AppearanceProfile.mode("night"));
    }
}
