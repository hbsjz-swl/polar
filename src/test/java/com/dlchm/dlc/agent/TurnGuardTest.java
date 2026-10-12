package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link TurnGuard} is the cooperative stop for a turn that is progressing but taking
 * too long. Both limits default to off, so an enabled guard must be a deliberate
 * configuration — a limit that shortened every turn by default would be worse than the
 * unbounded case it replaced.
 */
class TurnGuardTest {

    @Test void defaultsToDisabledSoExistingBehaviourIsUnchanged() {
        TurnGuard guard = TurnGuard.ofSeconds(0, 0);
        assertFalse(guard.isEnabled());
        assertNull(guard.violation());
        guard.recordToolCalls(10_000);
        assertNull(guard.violation(), "an unlimited guard never reports a violation");
    }

    @Test void toolCallBudgetStopsAtTheLimit() {
        TurnGuard guard = TurnGuard.ofSeconds(0, 3).start();
        assertTrue(guard.isEnabled());
        assertNull(guard.violation());
        guard.recordToolCalls(2);
        assertNull(guard.violation(), "two of three is still within budget");
        guard.recordToolCalls(1);
        assertNotNull(guard.violation());
        assertTrue(guard.violation().contains("tool-call budget of 3"));
    }

    /** A negative count must not make the budget unreachable. */
    @Test void negativeCountsAreIgnored() {
        TurnGuard guard = TurnGuard.ofSeconds(0, 2).start();
        guard.recordToolCalls(-5);
        assertNull(guard.violation());
        guard.recordToolCalls(2);
        assertNotNull(guard.violation());
    }

    @Test void timeBudgetReportsOnceElapsed() throws Exception {
        TurnGuard guard = TurnGuard.of(Duration.ofMillis(40), 0).start();
        assertNull(guard.violation(), "just started");
        Thread.sleep(80);
        String violation = guard.violation();
        assertNotNull(violation);
        assertTrue(violation.contains("time budget"), violation);
    }

    /** The clock starts on {@link #start()}, not on construction. */
    @Test void timeBudgetDoesNotStartUntilStarted() throws Exception {
        TurnGuard guard = TurnGuard.of(Duration.ofMillis(10), 0);
        Thread.sleep(60);
        assertNull(guard.violation(), "an unstarted guard has no clock");
        guard.start();
        assertNull(guard.violation(), "and the clock starts fresh");
    }

    @Test void startIsIdempotent() {
        TurnGuard guard = TurnGuard.of(Duration.ofMinutes(5), 0);
        guard.start();
        guard.start();
        assertNull(guard.violation());
    }

    /** A turn out of time is over regardless of how few tools it used. */
    @Test void timeBudgetIsReportedBeforeTheToolBudget() throws Exception {
        TurnGuard guard = TurnGuard.of(Duration.ofMillis(30), 1).start();
        guard.recordToolCalls(5);
        Thread.sleep(70);
        String violation = guard.violation();
        assertTrue(violation.contains("time budget"),
                "the elapsed-time reason is the more fundamental one: " + violation);
    }

    @Test void toolBudgetAloneStillFiresWhenNoTimeBudgetIsSet() {
        TurnGuard guard = TurnGuard.of(null, 1).start();
        guard.recordToolCalls(1);
        assertTrue(guard.violation().contains("tool-call budget of 1"));
    }
}