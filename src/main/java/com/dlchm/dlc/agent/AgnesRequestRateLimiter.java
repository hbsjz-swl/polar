package com.dlchm.dlc.agent;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide rolling-window limiter for the Agnes free API.
 *
 * <p>The limit is applied before every model HTTP request, including requests
 * made by context compression. Tool execution itself does not consume an API
 * request and is therefore not throttled here.</p>
 */
public final class AgnesRequestRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(AgnesRequestRateLimiter.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 20;
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private final Deque<Long> requestTimes = new ArrayDeque<>(MAX_REQUESTS_PER_MINUTE);

    /**
     * Blocks until starting one more request stays within the rolling 60-second
     * window. The agent runs model calls on boundedElastic, so this wait does
     * not block a WebFlux event-loop thread.
     */
    public void acquire() {
        boolean reportedWait = false;
        for (;;) {
            long waitNanos;
            synchronized (requestTimes) {
                long now = System.nanoTime();
                evictExpired(now);
                if (requestTimes.size() < MAX_REQUESTS_PER_MINUTE) {
                    requestTimes.addLast(now);
                    if (reportedWait) log.debug("Agnes request rate limit window reopened");
                    return;
                }
                waitNanos = Math.max(1L, requestTimes.peekFirst() + WINDOW_NANOS - now);
                if (!reportedWait) {
                    log.info("Agnes API rate limit reached ({} requests/min); waiting before next request",
                            MAX_REQUESTS_PER_MINUTE);
                    reportedWait = true;
                }
            }

            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the Agnes API rate limit", e);
            }
        }
    }

    private void evictExpired(long now) {
        while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= WINDOW_NANOS) {
            requestTimes.removeFirst();
        }
    }
}
