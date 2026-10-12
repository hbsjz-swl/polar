package com.dlchm.dlc.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.ToolCallListener;

/**
 * Records how long each tool call took and how large its result was.
 *
 * <h2>Why it exists</h2>
 * <p>A browser action costs seconds and a bash build can cost minutes, so "which tool
 * is eating this turn" is the first question when a long task feels slow. Nothing
 * measured it: the loop emitted events for the UI but kept no aggregate, and the only
 * way to find the expensive step was to read the transcript by hand.</p>
 *
 * <h2>Contract</h2>
 * <p>{@link ToolCallListener} methods must not throw — a throwing {@code afterCall}
 * discards a result the tool already produced. Every method here is fully guarded, so
 * an observability failure can never cost a tool result.</p>
 */
public final class ToolCallTimingListener implements ToolCallListener {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTimingListener.class);

    /** A call this slow is worth surfacing at WARN even when it succeeds. */
    private static final long SLOW_CALL_MILLIS = 10_000;

    private final long slowCallMillis;

    public ToolCallTimingListener() {
        this(SLOW_CALL_MILLIS);
    }

    public ToolCallTimingListener(long slowCallMillis) {
        this.slowCallMillis = slowCallMillis;
    }

    @Override
    public Object beforeCall(String toolName, String toolInput) {
        return System.nanoTime();
    }

    @Override
    public void afterCall(Object context, String toolName, String toolInput, String result) {
        report(toolName, context, result, null);
    }

    @Override
    public void afterCompletion(Object context, String toolName, String toolInput) {
        // The community decorator calls afterCall for successful results; nothing to do.
    }

    @Override
    public String onError(Object context, String toolName, String toolInput, RuntimeException error) {
        report(toolName, context, null, error);
        // Returning null lets the original failure reach the model unchanged, which is
        // what the loop's failure accounting expects.
        return null;
    }

    private void report(String toolName, Object context, String result, RuntimeException error) {
        try {
            long elapsed = context instanceof Long start
                    ? (System.nanoTime() - start) / 1_000_000L : -1;
            int size = result == null ? 0 : result.length();
            if (error != null) {
                log.warn("Tool {} failed after {}ms ({} chars): {}", toolName, elapsed, size,
                        String.valueOf(error.getMessage()).replace('\n', ' '));
            } else if (elapsed >= slowCallMillis) {
                log.warn("Tool {} took {}ms ({} chars)", toolName, elapsed, size);
            } else {
                log.debug("Tool {} took {}ms ({} chars)", toolName, elapsed, size);
            }
        } catch (RuntimeException ignored) {
            // Observability must never break a tool call.
        }
    }
}