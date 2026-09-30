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

    /**
     * The regression this guards: an agent whose strategy is to vary the selector,
     * the URL and the site produces a new (tool|category) key on almost every
     * attempt, so no per-key threshold is ever reached. Eight failures spread over
     * six keys left the breaker cold and the turn ran on indefinitely.
     */
    @Test void turnBudgetEscalatesEvenWhenEveryFailureLooksDifferent() {
        ToolFailureGuard guard = new ToolFailureGuard();
        String[] errors = {
                "{\"success\":false,\"error\":\"Locator.click: Timeout 7000ms exceeded. waiting for locator('#departure')\"}",
                "Error: Page.goto: net::ERR_NAME_NOT_RESOLVED at https://chunxian8.com/",
                "Error: Page.goto: net::ERR_QUIC_PROTOCOL_ERROR at https://www.google.com/",
                "Error: Page.goto: HTTP 404 at https://trains.ctrip.com/x.aspx",
                "{\"success\":false,\"error\":\"Target is hidden. Choose the visible textarea/input/button ref\"}",
        };
        guard.record("browser_action", "{\"i\":0}", errors[0]);
        guard.record("browser_action", "{\"i\":1}", errors[1]);
        guard.record("browser_view", "{}", "{\"success\":true,\"url\":\"https://ok/\"}");
        guard.record("browser_action", "{\"i\":2}", errors[2]);
        guard.record("browser_action", "{\"i\":3}", errors[3]);

        assertEquals(4, guard.turnFailures());
        assertFalse(guard.exhausted(), "four scattered failures are not yet fatal");
        assertTrue(guard.escalateHint().contains("browser_view"),
                "level one orders a re-observation instead of more guessing");
        assertNull(guard.escalateHint(), "escalation is one-shot");

        guard.record("browser_action", "{\"i\":4}", errors[4]);
        guard.record("browser_action", "{\"i\":5}", errors[0]);
        guard.record("browser_action", "{\"i\":6}", errors[2]);

        assertEquals(7, guard.turnFailures());
        assertTrue(guard.exhausted(), "the turn budget spends itself regardless of key variety");
        assertTrue(guard.escalateHint().contains("收尾"),
                "level two tells the model to deliver what it has instead of exploring on");
    }

    @Test void locatorMissSeparatesGuessedSelectorsFromRealEnvironmentErrors() {
        assertTrue(ToolFailureGuard.locatorMiss("Locator.fill: Timeout 7000ms exceeded. waiting for locator(\"#d\")"));
        assertTrue(ToolFailureGuard.locatorMiss("Target is hidden. Choose the visible textarea/input/button ref"));
        assertTrue(ToolFailureGuard.locatorMiss("Invalid element ref; observe the page again"));
        assertFalse(ToolFailureGuard.locatorMiss("Error: Page.goto: net::ERR_NAME_NOT_RESOLVED at https://a/"));
        assertFalse(ToolFailureGuard.locatorMiss(null));
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
