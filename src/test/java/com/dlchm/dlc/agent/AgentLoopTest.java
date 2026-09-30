package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.session.MarkdownSessionStore;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.tools.MemoryTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

class AgentLoopTest {
    @TempDir Path workspace;

    @Test void transportRetryKeepsCompletedToolResultAndDoesNotRelaunchBrowser() {
        Fixture f = fixture("Chrome started successfully.");
        when(f.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall()), Flux.error(handshake()), Flux.just(answer("已读取执行记录")));
        f.loop.run(f.session, "前台搜索", f.sink, f.model);
        verify(f.tool, times(1)).call("{}");
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, times(3)).stream(requests.capture());
        for (int i = 1; i <= 2; i++) {
            assertTrue(requests.getAllValues().get(i).getInstructions().stream()
                    .anyMatch(m -> m instanceof ToolResponseMessage t
                            && t.getResponses().get(0).responseData().contains("Chrome started")));
        }
    }

    @Test void interruptionAfterStreamedTextDoesNotDuplicateOutputByRetrying() {
        Fixture f = fixture("unused");
        when(f.model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(answer("正在操作")), Flux.error(handshake())));
        assertThrows(RuntimeException.class, () -> f.loop.run(f.session, "搜索", f.sink, f.model));
        verify(f.model, times(1)).stream(any(Prompt.class));
        verify(f.tool, never()).call(anyString());
    }

    @Test void largeStructuredFailureRemainsFailedAfterCompactionAndPersistence() throws Exception {
        String result = "{\"success\":false,\"final\":{\"main_text\":\"" + "text ".repeat(2000)
                + "\"},\"error\":\"late failure\"}";
        Fixture f = fixture(result);
        when(f.model.stream(any(Prompt.class))).thenReturn(Flux.just(toolCall()), Flux.just(answer("操作失败")));
        f.loop.run(f.session, "搜索", f.sink, f.model);
        List<StreamEvent> events = new ArrayList<>();
        ArgumentCaptor<StreamEvent> emitted = ArgumentCaptor.forClass(StreamEvent.class);
        verify(f.sink, atLeastOnce()).next(emitted.capture());
        events.addAll(emitted.getAllValues());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        StreamEvent finished = events.stream().filter(e -> e.type() == StreamEvent.Type.TOOL_CALL_FINISHED).findFirst().orElseThrow();
        assertFalse(mapper.readTree(finished.data()).path("success").asBoolean());
        ToolResponseMessage saved = (ToolResponseMessage) f.session.getHistory().stream()
                .filter(m -> m instanceof ToolResponseMessage).findFirst().orElseThrow();
        var savedJson = mapper.readTree(saved.getResponses().get(0).responseData());
        assertFalse(savedJson.path("success").asBoolean());
        assertEquals("late failure", savedJson.path("error").asText());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(String result) {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("browser_start")
                .description("start").inputSchema("{\"type\":\"object\"}").build());
        when(tool.call(anyString())).thenReturn(result);
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test").build());
        DlcProperties props = mock(DlcProperties.class);
        when(props.getContextWindowTokens()).thenReturn(8192);
        MemoryTool memory = mock(MemoryTool.class);
        when(memory.loadAllMemory()).thenReturn("");
        AgentLoop loop = new AgentLoop(() -> new ToolCallback[]{tool},
                new SandboxPathResolver(workspace.toString(), List.of()), memory, props, "test",
                new AgnesRequestRateLimiter(), new ApprovalManager(), mock(MarkdownSessionStore.class));
        return new Fixture(loop, new Session("test", "test", "test"), model, tool, mock(FluxSink.class));
    }
    private ChatResponse toolCall() {
        return response(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "browser_start", "{}"))).build());
    }
    private ChatResponse answer(String text) { return response(new AssistantMessage(text)); }
    private ChatResponse response(AssistantMessage output) {
        return ChatResponse.builder().generations(List.of(new Generation(output))).build();
    }
    private RuntimeException handshake() {
        return new CompletionException(new SSLHandshakeException("Remote host terminated the handshake"));
    }
    private record Fixture(AgentLoop loop, Session session, OpenAiChatModel model, ToolCallback tool, FluxSink<StreamEvent> sink) { }
}
