package com.ramphoenix.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ObdRouteTest {
    @Test public void oemPressureRouteUsesActualReplyAndFlowControlIds() {
        assertEquals(Arrays.asList("ATFCSM0", "ATSP6", "ATSH740", "ATCRA4C0",
                "ATFCSH740", "ATFCSD300000", "ATFCSM1"), ObdRoute.parse("740/4c0").commandsAfter(""));
    }

    @Test public void engineRouteRestoresAutomaticFlowControlAfterOemRead() {
        assertEquals(Arrays.asList("ATFCSM0", "ATSH7E0", "ATCRA7E8"),
                ObdRoute.parse("7E0").commandsAfter("740/4C0"));
    }

    @Test public void functionalRequestsAcceptAllModuleReplyIds() {
        assertNull(ObdRoute.parse("7DF").rx);
        ObdRoute route = ObdRoute.parse("18DB33F1");
        assertNull(route.rx);
        assertTrue(route.commandsAfter("7DF").contains("ATSP7"));
        assertTrue(route.commandsAfter("7DF").contains("ATCRA"));
        assertFalse(route.commandsAfter("7DF").contains("ATCRA18DBF133"));
    }

    @Test public void physical29BitRoutesSwapTargetAndTester() {
        ObdRoute route = ObdRoute.parse("18DA40F1");
        assertEquals("18DAF140", route.rx);
        List<String> commands = route.commandsAfter("7E0");
        assertTrue(commands.containsAll(Arrays.asList("ATSP7", "ATCP18", "ATSHDA40F1", "ATCRA18DAF140")));
    }

    @Test public void unchangedRouteDoesNotSendConfigurationAgain() {
        assertTrue(ObdRoute.parse("740/4C0").commandsAfter("740/4C0").isEmpty());
    }

    @Test public void extendedToStandardSwitchSelects11BitProtocol() {
        assertTrue(ObdRoute.parse("7DF").commandsAfter("18DA40F1").contains("ATSP6"));
    }

    @Test public void malformedRoutesCannotInjectExtraAdapterCommands() {
        for (String value : Arrays.asList("740\rATZ", "800/4C0", "20000000", "740/18DAF140", "740/4C0/7E8")) {
            try { ObdRoute.parse(value); fail("Accepted invalid route: " + value); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
