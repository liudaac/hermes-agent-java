package com.nousresearch.hermes.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlActionPolicyTest {
    @Test
    void dashboardOperatorAdminCanControl() {
        for (String actor : new String[] {"dashboard", "operator", "admin", "system"}) {
            assertTrue(ControlActionPolicy.isAllowed(actor, ControlActionPolicy.Action.REPLAY_INTENT));
            assertTrue(ControlActionPolicy.isAllowed(actor, ControlActionPolicy.Action.REROUTE_INTENT));
            assertTrue(ControlActionPolicy.isAllowed(actor, ControlActionPolicy.Action.OVERRIDE_AGENT));
        }
    }

    @Test
    void viewerAndUnknownAreDenied() {
        assertFalse(ControlActionPolicy.isAllowed("viewer", ControlActionPolicy.Action.OVERRIDE_AGENT));
        assertFalse(ControlActionPolicy.isAllowed("guest", ControlActionPolicy.Action.REROUTE_INTENT));
        assertFalse(ControlActionPolicy.isAllowed("someone-else", ControlActionPolicy.Action.REPLAY_INTENT));
        assertTrue(ControlActionPolicy.denyReason("viewer", ControlActionPolicy.Action.OVERRIDE_AGENT).contains("OVERRIDE_AGENT"));
    }

    @Test
    void missingActorDefaultsToDashboard() {
        assertTrue(ControlActionPolicy.isAllowed(null, ControlActionPolicy.Action.REPLAY_INTENT));
        assertTrue(ControlActionPolicy.isAllowed("", ControlActionPolicy.Action.REROUTE_INTENT));
    }
}
