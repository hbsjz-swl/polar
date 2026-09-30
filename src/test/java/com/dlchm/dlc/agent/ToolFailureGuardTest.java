package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ToolFailureGuardTest {
    @Test void changingProfilesCannotBypassBrowserStartupLimit() {
        ToolFailureGuard guard = new ToolFailureGuard();
        guard.record("bash_execute", "p.chromium.launch(headless=False, profile='/tmp/a')", "Exit code: 1\nEPERM mkdtemp a");
        assertNull(guard.blockedReason("browser_start", "{}"));
        guard.record("bash_execute", "p.chromium.launch_persistent_context('/tmp/b')", "Exit code: 1\nEPERM mkdtemp b");
        assertNotNull(guard.blockedReason("browser_start", "{}"));
        assertNotNull(guard.blockedReason("bash_execute", "p.chromium.launch('/tmp/c')"));
        assertNull(guard.blockedReason("browser_view", "{}"));
        assertNull(guard.blockedReason("bash_execute", "ls /tmp"));
    }
    @Test void repeatedFailureEventuallyDisablesToolsEvenWithSuccessfulDiagnosticsBetweenAttempts() {
        ToolFailureGuard guard = new ToolFailureGuard();
        for (int i = 0; i < 4; i++) {
            guard.record("browser_action", "changed arguments " + i, "{\"success\":false,\"error\":\"Timeout 7000ms\"}");
            guard.record("bash_execute", "ls", "Exit code: 0");
        }
        assertTrue(guard.exhausted());
        assertNotNull(guard.blockedReason("browser_action", "{}"));
    }
    @Test void confirmedConnectionAllowsRecoveryAfterUserIntervention() {
        ToolFailureGuard guard = new ToolFailureGuard();
        guard.record("browser_start", "{}", "Error: CDP unavailable");
        guard.record("browser_start", "{}", "Error: CDP unavailable");
        guard.record("browser_view", "{}", "{\"success\":true,\"url\":\"https://example.com\"}");
        assertNull(guard.blockedReason("browser_start", "{}"));
    }

    @Test void repeatedSuccessfulObservationsWithoutProgressAreAlsoBounded() {
        ToolFailureGuard guard = new ToolFailureGuard();
        for (int i = 0; i < 4; i++) guard.record("browser_view", "{}", "{\"success\":true,\"url\":\"about:blank\"}");
        assertTrue(guard.exhausted());
    }

    @Test void blocksOnlyTheFailingFamilyAndKeepsObservationToolsAlive() {
        ToolFailureGuard guard = new ToolFailureGuard();
        for (int i = 0; i < 4; i++) {
            guard.record("browser_action", "attempt " + i, "{\"success\":false,\"error\":\"Timeout 7000ms\"}");
        }
        assertNotNull(guard.blockedReason("browser_action", "{}"));
        assertNull(guard.blockedReason("browser_view", "{}"),
                "observation must survive a sibling tool's meltdown");
        assertNull(guard.blockedReason("read_file", "{}"));
        assertNotNull(guard.blockedReason("bash_execute", "ls"));
    }
}
