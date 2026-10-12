package com.dlchm.dlc.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Bounds one agent turn by wall-clock time and by tool-call count.
 *
 * <h2>Why this exists</h2>
 * <p>{@code MAX_ITERATIONS} already caps the loop, but a turn can still run for a very
 * long time inside that budget: each iteration is a model request plus however many
 * tool calls it makes, and a browser task spends seconds per action. The loop had no
 * way to stop a turn that was progressing but simply taking too long, so a stuck page
 * or a runaway subagent held the session open indefinitely.</p>
 *
 * <h2>Cooperative, not preemptive</h2>
 * <p>Checked between iterations, exactly where {@code sink.isCancelled()} is checked.
 * A guard that fired mid-tool-call could not safely unwind: the tool's side effects
 * have already happened, and abandoning the transcript at an arbitrary point leaves a
 * dangling tool call that the next request cannot replay. Between iterations the
 * transcript is always at a message boundary.</p>
 *
 * <h2>Defaults are off</h2>
 * <p>Both limits default to zero (disabled), so existing behaviour is unchanged until
 * they are configured. A limit that silently shortened every turn would be worse than
 * the unbounded case it replaced.</p>
 */
public final class TurnGuard {

    private final Duration maxDuration;
    private final int maxToolCalls;
    private Instant startedAt;
    private int toolCalls;

    private TurnGuard(Duration maxDuration, int maxToolCalls) {
        this.maxDuration = maxDuration;
        this.maxToolCalls = Math.max(0, maxToolCalls);
    }

    public static TurnGuard of(Duration maxDuration, int maxToolCalls) {
        return new TurnGuard(maxDuration, maxToolCalls);
    }

    /** No limits at all. */
    public static TurnGuard unlimited() {
        return new TurnGuard(null, 0);
    }

    public boolean isEnabled() {
        return maxDuration != null || maxToolCalls > 0;
    }

    /** Records that a batch of tool calls is about to run, so the count can be bounded. */
    public void recordToolCalls(int count) {
        this.toolCalls += Math.max(0, count);
    }

    /** Tool calls recorded so far, for the budget the model is shown each round. */
    public int toolCallsUsed() {
        return toolCalls;
    }

    /**
     * The reason this turn must stop, or {@code null} to continue.
     *
     * <p>Wall-clock is reported first: a turn that has run out of time is over
     * regardless of how few tools it used.</p>
     */
    public String violation() {
        if (maxDuration != null && startedAt != null
                && Duration.between(startedAt, Instant.now()).compareTo(maxDuration) > 0) {
            return "turn exceeded its time budget of " + maxDuration.toMinutes() + " min "
                    + "(set by dlc.turn.max-duration-seconds)";
        }
        if (maxToolCalls > 0 && toolCalls >= maxToolCalls) {
            return "turn exceeded its tool-call budget of " + maxToolCalls
                    + " (set by dlc.turn.max-tool-calls)";
        }
        return null;
    }

    /** Starts the clock. Called once, before the first iteration. */
    public TurnGuard start() {
        if (maxDuration != null && startedAt == null) this.startedAt = Instant.now();
        return this;
    }

    /** Convenience for tests and callers that think in seconds. */
    public static TurnGuard ofSeconds(long seconds, int maxToolCalls) {
        Duration duration = seconds <= 0 ? null : Duration.ofSeconds(seconds);
        return new TurnGuard(duration, maxToolCalls);
    }

    public static long millis(Duration duration) {
        return duration == null ? 0 : TimeUnit.MILLISECONDS.convert(duration);
    }
}