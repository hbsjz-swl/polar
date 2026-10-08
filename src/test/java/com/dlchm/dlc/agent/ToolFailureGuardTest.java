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

    /**
     * The regression: a slider-captcha overlay refused a click on a radio that had
     * resolved and was visible. Playwright's log opens with "waiting for locator" on
     * every action, so the old prefix match filed it as a bad selector — and the
     * model's corrective prompt then told it to stop using refs, sending it round the
     * observation loop against a page it could not click at all.
     */
    @Test void coveredTargetIsNotReportedAsAMissingSelector() {
        String intercepted = """
                {"success":false,"failed_step":1,"blocked_kind":"captcha",
                 "blocked_by":"iframe.popwscps_d_iframe src=https://i.eastmoney.com/websitecaptcha/slidervalid",
                 "error":"Locator.click: Timeout 7000ms exceeded.\\nCall log:\\n- waiting for locator(\\".e5\\")\\n\
                -   locator resolved to <input type=radio data-dlc-ref=e5/>\\n\
                - <iframe class=popwscps_d_iframe></iframe> intercepts pointer events"}""";
        assertFalse(ToolFailureGuard.locatorMiss(intercepted),
                "a resolved element under an overlay is not a selector problem");
        assertTrue(ToolFailureGuard.obstructed(intercepted));
        assertTrue(ToolFailureGuard.captchaBlocked(intercepted));

        // Same shape without the structured fields: the call log alone is enough.
        String rawOnly = "Locator.click: Timeout 7000ms exceeded.\\nCall log:\\n"
                + "- waiting for locator(\"[data-dlc-ref=\\\"e5\\\"]\")\\n"
                + "-   locator resolved to <input type=radio data-dlc-ref=e5/>\\n"
                + "- <iframe class=popwscps_d_iframe src=.../slidervalid></iframe> intercepts pointer events";
        assertFalse(ToolFailureGuard.locatorMiss(rawOnly));
        assertTrue(ToolFailureGuard.captchaBlocked(rawOnly));
    }

    @Test void obstructedFailuresAreCountedApartFromSlowPages() {
        assertEquals("captcha", ToolFailureGuard.category(
                "{\"success\":false,\"blocked_kind\":\"captcha\",\"blocked_by\":\"iframe#cap\"}"));
        assertEquals("obscured", ToolFailureGuard.category(
                "{\"success\":false,\"blocked_kind\":\"obscured\",\"blocked_by\":\"div.modal\"}"));
        // A page that is merely slow stays retryable, and must not join their bucket.
        assertEquals("timeout", ToolFailureGuard.category(
                "{\"success\":false,\"error\":\"Page.goto: Timeout 20000ms exceeded\"}"));
    }

    @Test void captchaBlocksPageWritingToolsButLeavesObservationAlive() {
        ToolFailureGuard guard = new ToolFailureGuard();
        String captcha = "{\"success\":false,\"blocked_kind\":\"captcha\",\"blocked_by\":\"iframe#cap\"}";
        guard.record("browser_action", "{\"actions\":\"[click e5]\"}", captcha);
        assertNull(guard.blockedReason("browser_action", "{}"),
                "one attempt is not yet proof that the widget is there to stay");
        guard.record("browser_action", "{\"actions\":\"[click e7]\"}", captcha);

        String blocked = guard.blockedReason("browser_action", "{}");
        assertNotNull(blocked, "a captcha cannot be clicked through, so page writes must stop");
        assertTrue(blocked.contains("CAPTCHA"));
        assertTrue(blocked.contains("stop this turn"),
                "the model has to be told waiting is the correct action, not a stall");
        assertNull(guard.blockedReason("browser_view", "{}"),
                "browser_view is how the user confirms they finished the verification");
    }

    @Test void captchaEndsTheTurnInsteadOfRedrivingItIntoMoreClicking() {
        ToolFailureGuard guard = new ToolFailureGuard();
        String captcha = "{\"success\":false,\"blocked_kind\":\"captcha\",\"blocked_by\":\"iframe#cap\"}";
        guard.record("browser_action", "{\"i\":0}", captcha);
        guard.record("browser_action", "{\"i\":1}", captcha);
        assertTrue(guard.captchaBlocked());
        String hint = guard.escalateHint();
        assertNotNull(hint);
        assertTrue(hint.contains("验证码"));
        assertTrue(hint.contains("force"), "the model must be told not to click through the widget");
        assertNull(guard.escalateHint(), "one-shot, like the other escalation levels");
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
