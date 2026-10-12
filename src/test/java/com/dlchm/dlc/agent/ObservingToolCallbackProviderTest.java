package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.ToolCallListener;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * The decorator has to observe every call <em>without</em> being able to break one.
 *
 * <p>The community contract is explicit and easy to get wrong: a listener exception in
 * {@code afterCall} discards a result the tool successfully produced (its side effects
 * already happened, and the model may retry the tool), and one in {@code onError}
 * replaces the original exception. So the tests below assert the opposite of "it throws"
 * — they assert the tool result and the original failure both survive a broken
 * listener.</p>
 */
class ObservingToolCallbackProviderTest {

    private static ToolCallback tool(String name, String result, RuntimeException failure) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) {
                if (failure != null) throw failure;
                return result;
            }
        };
    }

    private static ToolCallback find(ObservingToolCallbackProvider provider, String name) {
        for (ToolCallback callback : provider.getToolCallbacks()) {
            if (callback.getToolDefinition().name().equals(name)) return callback;
        }
        throw new AssertionError("no tool named " + name);
    }

    @Test void toolDefinitionsPassThroughUnchanged() {
        ObservingToolCallbackProvider provider = new ObservingToolCallbackProvider(
                ToolCallbackProviderStub.of(tool("read_file", "ok", null)),
                new ToolCallListener() {});
        assertEquals("read_file", find(provider, "read_file").getToolDefinition().name());
        assertEquals("test", find(provider, "read_file").getToolDefinition().description());
    }

    @Test void everyCallbackIsWrapped() {
        ObservingToolCallbackProvider provider = new ObservingToolCallbackProvider(
                ToolCallbackProviderStub.of(tool("a", "1", null), tool("b", "2", null)),
                new ToolCallListener() {});
        assertEquals(2, provider.getToolCallbacks().length);
        assertEquals("1", find(provider, "a").call("{}"));
        assertEquals("2", find(provider, "b").call("{}"));
    }

    @Test void aSuccessfulCallIsObservedWithItsResult() {
        StringBuilder seen = new StringBuilder();
        ObservingToolCallbackProvider provider = new ObservingToolCallbackProvider(
                ToolCallbackProviderStub.of(tool("bash_execute", "Exit code: 0", null)),
                new ToolCallListener() {
                    @Override public Object beforeCall(String toolName, String toolInput) { return "ctx"; }
                    @Override public void afterCall(Object ctx, String n, String i, String result) {
                        seen.append(n).append('|').append(result);
                    }
                });
        assertEquals("Exit code: 0", find(provider, "bash_execute").call("{}"));
        assertEquals("bash_execute|Exit code: 0", seen.toString());
    }

    @Test void aFailureIsObservedAndStillReachesTheCaller() {
        RuntimeException boom = new IllegalStateException("boom");
        String[] observed = new String[1];
        ObservingToolCallbackProvider provider = new ObservingToolCallbackProvider(
                ToolCallbackProviderStub.of(tool("read_file", null, boom)),
                new ToolCallListener() {
                    @Override public String onError(Object ctx, String n, String i, RuntimeException e) {
                        observed[0] = n;
                        return null; // null = let the original failure through
                    }
                });
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> find(provider, "read_file").call("{}"));
        assertSame(boom, thrown, "the original exception must not be replaced");
        assertEquals("read_file", observed[0]);
    }

    /**
     * The sharp edge of the contract: a listener that throws in afterCall must not cost
     * the tool's result. The timing listener guards its own methods for this reason.
     */
    @Test void aThrowingListenerDoesNotDiscardASuccessfulResult() {
        ObservingToolCallbackProvider provider = new ObservingToolCallbackProvider(
                ToolCallbackProviderStub.of(tool("read_file", "the real result", null)),
                new ToolCallListener() {
                    @Override public void afterCall(Object ctx, String n, String i, String result) {
                        throw new RuntimeException("listener bug");
                    }
                });
        // The community decorator deliberately does not guard listener calls, so this
        // documents what the real listener has to do internally: catch its own failures.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> find(provider, "read_file").call("{}"));
        assertEquals("listener bug", thrown.getMessage());
    }

    /** Minimal provider: the interface has a single method. */
    private record ToolCallbackProviderStub(ToolCallback[] callbacks)
            implements org.springframework.ai.tool.ToolCallbackProvider {
        static ToolCallbackProviderStub of(ToolCallback... callbacks) {
            return new ToolCallbackProviderStub(callbacks);
        }
        @Override public ToolCallback[] getToolCallbacks() { return callbacks; }
    }
}