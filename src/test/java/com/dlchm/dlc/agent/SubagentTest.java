package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dlchm.dlc.cli.DlcSetup;
import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.session.MarkdownSessionStore;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import com.dlchm.dlc.tools.ApprovalRequiredException;
import com.dlchm.dlc.tools.BrowserTool;
import com.dlchm.dlc.tools.MemoryTool;
import com.dlchm.dlc.tools.ReadFileTool;
import com.dlchm.dlc.tools.SubagentTool;
import com.dlchm.dlc.tools.ToolOutputTruncator;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Subagent delegation limits, and the runtime switch behind {@code /config}.
 */
class SubagentTest {
    @TempDir Path workspace;

    @Test void disabledSwitchRefusesDelegation() {
        Fixture f = fixture();
        f.props.getSubagent().setEnabled(false);
        String result = f.manager.delegate("do the work", null);
        assertTrue(result.contains("disabled"), result);
        verify(f.agent, never()).chat(any(Session.class), anyString(), anyInt());
    }

    @Test void nestingBeyondMaxDepthIsRefused() {
        Fixture f = fixture();
        f.props.getSubagent().setMaxDepth(1);
        // Depth 1 is already one level deep, so a child cannot spawn a grandchild.
        String result = ExecutionContext.call(f.session, 1, () -> f.manager.delegate("nested", null));
        assertTrue(result.contains("nested subagents are disabled"), result);
    }

    @Test void childIsGivenTheParentDepthRatherThanZero() {
        Fixture f = fixture();
        f.props.getSubagent().setMaxDepth(3);
        ExecutionContext.call(f.session, 1, () -> f.manager.delegate("child work", null));
        ArgumentCaptor<Integer> depth = ArgumentCaptor.forClass(Integer.class);
        verify(f.agent).chat(any(Session.class), eq("child work"), depth.capture());
        assertEquals(2, depth.getValue(),
                "the child must run one level deeper than its parent, not reset to 0");
    }

    @Test void emptyAndOversizedPromptsAreRejectedWithTheActualLimit() {
        Fixture f = fixture();
        assertTrue(f.manager.delegate("   ", null).contains("empty"));

        f.props.getSubagent().setMaxPromptChars(100);
        String tooLong = f.manager.delegate("x".repeat(101), null);
        assertTrue(tooLong.contains("too long"), tooLong);
        assertTrue(tooLong.contains("101"), tooLong);
    }

    @Test void requestedTimeoutIsClampedToTheHardCeiling() {
        Fixture f = fixture();
        f.props.getSubagent().setTimeoutSeconds(30);
        f.props.getSubagent().setMaxTimeoutSeconds(3);
        // The child blocks on a latch the test never releases, so the clamp shows
        // up as a short bounded wait instead of the requested 9999 seconds.
        CountDownLatch hold = new CountDownLatch(1);
        when(f.agent.chat(any(Session.class), anyString(), anyInt())).thenAnswer(inv -> {
            hold.await(60, TimeUnit.SECONDS);
            return "never";
        });
        long start = System.nanoTime();
        String result = f.manager.delegate("slow work", 9999);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.contains("timed out after 3s"), result);
        assertTrue(elapsedMs < 20_000, "waited " + elapsedMs + "ms, clamp not applied");
        hold.countDown();
    }

    @Test void aChildApprovalIsRefusedInsteadOfBlockingForever() {
        Fixture f = fixture();
        ToolCallback gated = mock(ToolCallback.class);
        when(gated.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("write_file").description("write").inputSchema("{\"type\":\"object\"}").build());
        when(gated.call(anyString())).thenThrow(
                new ApprovalRequiredException("req-1", "write_file", "overwrite pom.xml"));
        ToolCallback[] tools = {gated};
        AgentLoop loop = new AgentLoop(() -> tools,
                new SandboxPathResolver(workspace.toString(), List.of()), mock(MemoryTool.class),
                f.props, "test", new AgnesRequestRateLimiter(), new ApprovalManager(),
                mock(MarkdownSessionStore.class));
        // The fixture model only ever answers with text, so the gated tool would
        // never run and the approval path would go untested.
        OpenAiChatModel calling = mock(OpenAiChatModel.class);
        when(calling.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test").build());
        when(calling.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall("write_file")),
                Flux.just(answer("没有执行该操作。")));

        Session child = new Session("subagent", "test", "test");
        // Depth 1 with the real 10-minute approval deadline: without the guard
        // this test hangs for ten minutes instead of failing.
        ExecutionContext.run(child, 1, () -> loop.run(child, "overwrite the pom", f.sink, calling));
        ArgumentCaptor<StreamEvent> emitted = ArgumentCaptor.forClass(StreamEvent.class);
        verify(f.sink, atLeastOnce()).next(emitted.capture());
        String toolResult = emitted.getAllValues().stream()
                .filter(e -> e.type() == StreamEvent.Type.TOOL_OUTPUT)
                .map(StreamEvent::data)
                .reduce("", String::concat);
        assertTrue(toolResult.contains("needs human approval"), toolResult);
        assertTrue(emitted.getAllValues().stream()
                        .noneMatch(e -> e.type() == StreamEvent.Type.APPROVAL_REQUIRED),
                "a child must not emit an approval request no channel can answer");
    }

    @Test void parentTurnKeepsBothBrowserAndDelegation() {
        Fixture f = fixture();
        f.loop.run(f.session, "parent work", f.sink, f.model);
        List<String> offered = offeredToolNames(f);
        assertTrue(offered.contains("browser_view"), offered.toString());
        assertTrue(offered.contains("delegate_task"), offered.toString());
    }

    @Test void childTurnHidesBrowserAndDelegationToolsButKeepsFileTools() {
        Fixture f = fixture();
        Session child = new Session("subagent", "test", "test");
        ExecutionContext.run(child, 1, () -> f.loop.run(child, "child work", f.sink, f.model));
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, atLeastOnce()).stream(requests.capture());
        List<String> offered = names(requests.getAllValues().get(0));
        assertFalse(offered.contains("browser_view"),
                "BrowserTool is a singleton holding one CDP tab: " + offered);
        assertFalse(offered.contains("delegate_task"), offered.toString());
        assertTrue(offered.contains("read_file"), "the child still needs file access: " + offered);
    }

    @Test void disablingTheSwitchAtRuntimeHidesTheDelegationTool() {
        Fixture f = fixture();
        f.props.getSubagent().setEnabled(false);
        f.loop.run(f.session, "parent work", f.sink, f.model);
        assertFalse(offeredToolNames(f).contains("delegate_task"),
                "a tool the model can see but never succeed at invites pointless calls: "
                        + offeredToolNames(f));
    }

    @Test void reEnablingAtRuntimeBringsTheToolBack() {
        Fixture f = fixture();
        f.props.getSubagent().setEnabled(false);
        f.loop.run(f.session, "off", f.sink, f.model);
        assertFalse(offeredToolNames(f).contains("delegate_task"));

        f.props.getSubagent().setEnabled(true);
        clearInvocations(f.model, f.sink);
        f.loop.run(f.session, "on", f.sink, f.model);
        assertTrue(offeredToolNames(f).contains("delegate_task"),
                "/config must be able to turn the feature back on without a restart");
    }

    @Test void concurrencyLimitIsEnforcedPerCallNotPerProcessStart() throws Exception {
        Fixture f = fixture();
        f.props.getSubagent().setMaxConcurrent(1);
        f.props.getSubagent().setTimeoutSeconds(2);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(f.agent.chat(any(Session.class), anyString(), anyInt())).thenAnswer(inv -> {
            started.countDown();
            hold.await(10, TimeUnit.SECONDS);
            return "done";
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> ExecutionContext.call(f.session, 0,
                    () -> f.manager.delegate("first", 10)));
            assertTrue(started.await(5, TimeUnit.SECONDS), "the first child never started");
            // The limit is read per call, so a second delegation is turned away
            // instead of running alongside the first.
            String rejected = ExecutionContext.call(f.session, 0,
                    () -> f.manager.delegate("second", 1));
            assertTrue(rejected.contains("slots are busy"), rejected);
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }

    @Test void raisingTheConcurrencyLimitAtRuntimeAdmitsMoreChildren() throws Exception {
        Fixture f = fixture();
        f.props.getSubagent().setMaxConcurrent(1);
        f.props.getSubagent().setTimeoutSeconds(2);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(f.agent.chat(any(Session.class), anyString(), anyInt())).thenAnswer(inv -> {
            started.countDown();
            hold.await(10, TimeUnit.SECONDS);
            return "done";
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> ExecutionContext.call(f.session, 0,
                    () -> f.manager.delegate("first", 10)));
            assertTrue(started.await(5, TimeUnit.SECONDS), "the first child never started");

            // Raise the ceiling the way `/config` would, then confirm the waiter
            // parked under the old limit gets in rather than timing out.
            f.props.getSubagent().setMaxConcurrent(2);
            String second = ExecutionContext.call(f.session, 0,
                    () -> f.manager.delegate("second", 1));
            assertFalse(second.contains("slots are busy"),
                    "raising max-concurrent at runtime must admit a waiting child: " + second);
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }

    @Test void cancellationMarksTheTaskCancelledAndStampsFinishTime() {
        Fixture f = fixture();
        CountDownLatch hold = new CountDownLatch(1);
        when(f.agent.chat(any(Session.class), anyString(), anyInt())).thenAnswer(inv -> {
            hold.await(10, TimeUnit.SECONDS);
            return "done";
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> ExecutionContext.call(f.session, 0,
                    () -> f.manager.delegate("long", 30)));
            var info = awaitTracked(f);
            assertEquals("running", info.status());
            assertTrue(f.manager.cancel(info.id()));
            var after = f.manager.list(f.session.getId()).get(0);
            assertEquals("cancelled", after.status());
            assertNotNull(after.finishedAt());
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }

    @Test void thePersistedConfigExplainsEveryKeyInChinese() throws Exception {
        Properties saved = new Properties();
        saved.setProperty("subagent-enabled", "true");
        saved.setProperty("subagent-max-depth", "2");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8)) {
            DlcSetup.writeAnnotatedForTest(writer, saved);
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        // Properties.store can only carry one file-level comment, which leaves a
        // bare wall of keys. Whoever edits this file later needs the meaning of
        // each value, not just its name.
        assertTrue(text.contains("# 是否启用子 Agent"), text);
        assertTrue(text.contains("subagent-max-depth=2"), text);
        assertTrue(text.indexOf("# 是否启用子 Agent") < text.indexOf("subagent-enabled="),
                "each comment must sit directly above its own key");
    }

    @Test void applyToRuntimePushesSavedValuesIntoTheLiveBean() {
        DlcProperties props = new DlcProperties();
        Properties saved = new Properties();
        saved.setProperty("subagent-enabled", "false");
        saved.setProperty("subagent-max-depth", "3");
        saved.setProperty("subagent-max-concurrent", "9");
        saved.setProperty("subagent-timeout", "42");
        saved.setProperty("subagent-max-timeout", "not-a-number");

        DlcSetup.applyToRuntime(saved, props);

        assertFalse(props.getSubagent().isEnabled());
        assertEquals(3, props.getSubagent().getMaxDepth());
        assertEquals(9, props.getSubagent().getMaxConcurrent());
        assertEquals(42, props.getSubagent().getTimeoutSeconds());
        assertEquals(600, props.getSubagent().getMaxTimeoutSeconds(),
                "an unparsable value must leave the current setting alone");
    }

    // ==================== fixture ====================

    private record Fixture(SubagentManager manager, AgentLoop loop, CodingAgent agent,
                           DlcProperties props, Session session,
                           OpenAiChatModel model, FluxSink<StreamEvent> sink) {}

    private Fixture fixture() {
        DlcProperties props = new DlcProperties();
        SandboxPathResolver paths = new SandboxPathResolver(workspace.toString(), List.of());
        SessionManager sessions = new SessionManager(mock(MarkdownSessionStore.class));
        CodingAgent agent = mock(CodingAgent.class);
        when(agent.chat(any(Session.class), anyString(), anyInt())).thenReturn("child summary");

        SubagentManager manager = new SubagentManager(singletonProvider(agent), sessions, props);

        ToolOutputTruncator truncator = new ToolOutputTruncator(props.getMaxToolOutputChars());
        ToolCallback[] tools = {
                toolFor(new ReadFileTool(paths, truncator), "read_file"),
                toolFor(new BrowserTool(truncator), "browser_view"),
                toolFor(new SubagentTool(manager), "delegate_task")};

        MemoryTool memory = mock(MemoryTool.class);
        when(memory.loadAllMemory()).thenReturn("");
        AgentLoop loop = new AgentLoop(() -> tools,
                paths, memory, props, "test", new AgnesRequestRateLimiter(),
                new ApprovalManager(), mock(MarkdownSessionStore.class));

        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test").build());
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(
                ChatResponse.builder().generations(List.of(
                        new Generation(new AssistantMessage("done")))).build()));

        return new Fixture(manager, loop, agent, props,
                new Session("test", "test", "test"), model, mock(FluxSink.class));
    }

    /** Waits for the delegation to show up in the task table. */
    private static SubagentManager.TaskInfo awaitTracked(Fixture f) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<SubagentManager.TaskInfo> running = f.manager.list(f.session.getId());
            if (!running.isEmpty()) return running.get(0);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("the delegation was never tracked");
    }

    /** Picks one named callback out of a tool object, which may expose several. */
    private static ToolCallback toolFor(Object toolObject, String expectedName) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolObject).build().getToolCallbacks();
        return java.util.Arrays.stream(callbacks)
                .filter(cb -> expectedName.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        expectedName + " not produced by " + toolObject.getClass().getSimpleName()));
    }

    private static org.springframework.beans.factory.ObjectProvider<CodingAgent> singletonProvider(
            CodingAgent agent) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public CodingAgent getObject() { return agent; }
            @Override public CodingAgent getObject(Object... args) { return agent; }
            @Override public CodingAgent getIfAvailable() { return agent; }
            @Override public CodingAgent getIfUnique() { return agent; }
        };
    }

    /** Captures the tool names the model was offered on its first request. */
    private static List<String> offeredToolNames(Fixture f) {
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, atLeastOnce()).stream(requests.capture());
        return names(requests.getAllValues().get(0));
    }

    private static ChatResponse toolCall(String name) {
        return ChatResponse.builder().generations(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", name, "{}"))).build())))
                .build();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text)))).build();
    }

    private static List<String> names(Prompt prompt) {
        List<String> names = new ArrayList<>();
        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions();
        for (ToolCallback callback : options.getToolCallbacks()) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }
}
