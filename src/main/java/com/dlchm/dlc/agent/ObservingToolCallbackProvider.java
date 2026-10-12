package com.dlchm.dlc.agent;

import org.springaicommunity.agent.tools.ToolCallListener;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springaicommunity.agent.tools.ToolCallListeners;

/**
 * A {@link ToolCallbackProvider} that observes every tool invocation.
 *
 * <h2>What this is for</h2>
 * <p>The loop already emits {@code TOOL_CALL_STARTED}/{@code TOOL_OUTPUT}/
 * {@code TOOL_CALL_FINISHED} for the SSE stream, but that path only sees what the loop
 * itself knows. A listener attached to the callbacks sees every invocation however it
 * was reached — including subagent turns, which run their own loop and otherwise report
 * nothing back.</p>
 *
 * <h2>Why a decorator rather than edits inside each tool</h2>
 * <p>The tools are plain {@code @Tool} classes; adding timing or auditing to each would
 * mean touching every one of them and keeping that code in sync. The community
 * decorator wraps a callback and delegates {@code getToolDefinition()} untouched, so it
 * composes with the existing {@code MethodToolCallbackProvider} unchanged.</p>
 *
 * <h2>Listeners must not throw</h2>
 * <p>The decorator does not guard listener calls: an exception in {@code afterCall}
 * would discard a result the tool <em>successfully</em> produced (its side effects have
 * already happened, and the model may retry it), and one in {@code onError} would
 * replace the original exception. So this implementation catches its own failures and
 * reports them, never propagates them.</p>
 */
public final class ObservingToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final ToolCallListener listener;

    public ObservingToolCallbackProvider(ToolCallbackProvider delegate, ToolCallListener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] callbacks = delegate.getToolCallbacks();
        java.util.List<ToolCallback> observed = new java.util.ArrayList<>(callbacks.length);
        for (ToolCallback callback : callbacks) {
            observed.add(ToolCallListeners.wrap(callback, listener));
        }
        return observed.toArray(new ToolCallback[0]);
    }
}