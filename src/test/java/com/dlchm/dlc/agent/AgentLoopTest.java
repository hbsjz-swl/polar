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
import org.springframework.ai.chat.messages.UserMessage;
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

    @Test void aNarrationThatNamesTheWrongSiteIsCorrectedBeforeTheNextAction() {
        Fixture f = fixture("browser_view",
                "{\"success\":true,\"url\":\"https://www.amap.com/ss/search\",\"title\":\"高德地图\"}");
        when(f.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall("browser_view", "先打开查询页")),
                Flux.just(toolCall("browser_view", "当前截图确认是携程火车票查询页")),
                Flux.just(answer("完成")));

        f.loop.run(f.session, "查一下机票价格", f.sink, f.model);

        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, times(3)).stream(requests.capture());
        List<org.springframework.ai.chat.messages.Message> third =
                requests.getAllValues().get(2).getInstructions();
        boolean corrected = third.stream().anyMatch(m -> m instanceof UserMessage u
                && u.getText().startsWith("[系统提示]")
                && u.getText().contains("携程") && u.getText().contains("amap.com"));
        assertTrue(corrected, instructionsText(requests.getAllValues().get(2)));
    }

    @Test void aGateRedriveDoesNotConsumeTheStopAndAskBudget() {
        Fixture f = fixture("Chrome started successfully.");
        when(f.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall()),                                   // runs a tool, arms the ledger
                Flux.just(answer("去程大约 ¥999 起")),                     // gate redrive 1
                Flux.just(answer("返程大约 ¥888 起")),                     // gate redrive 2 (budget spent)
                Flux.just(answer("价格 ¥777，要不要我继续，下一步我继续帮你查")), // gate spent -> ask guard must fire
                Flux.just(answer("完成")));                                // clean finish

        f.loop.run(f.session, "查一下机票价格", f.sink, f.model);

        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, times(5)).stream(requests.capture());
        boolean askGuard = requests.getAllValues().get(4).getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage u && u.getText().contains("不要停下来征求同意"));
        assertTrue(askGuard, "the ask-guard budget must survive two gate redrives: "
                + instructionsText(requests.getAllValues().get(4)));
    }

    @Test void aDeliveredTurnWithAClosingOfferIsNotReDriven() {
        Fixture f = fixture("browser_action",
                "{\"success\":true,\"url\":\"https://flights.ctrip.com/x\",\"final\":{\"main_text\":\"¥550 ¥800\"}}");
        when(f.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall("browser_action", "")),
                Flux.just(answer("石家庄往返最低 ¥550 + ¥800 = ¥1350。如果你愿意，我下一步可以继续补查高铁票价。")));

        f.loop.run(f.session, "查一下机票价格", f.sink, f.model);

        // The answer quotes two observed values, so the closing sentence is an offer.
        // Re-driving it would promote an unrequested extra into mandatory work.
        verify(f.model, times(2)).stream(any(Prompt.class));
    }

    @Test void aStallWithNothingDeliveredIsStillReDriven() {
        Fixture f = fixture("browser_view",
                "{\"success\":true,\"url\":\"https://flights.ctrip.com/x\",\"final\":{\"main_text\":\"no prices yet\"}}");
        when(f.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall("browser_view", "")),
                Flux.just(answer("还没拿到价格。要不要我继续查，下一步我接着对比？")),
                Flux.just(answer("已按要求完成对比。")));

        f.loop.run(f.session, "查一下机票价格", f.sink, f.model);

        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, times(3)).stream(requests.capture());
        boolean reDriven = requests.getAllValues().get(2).getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage u && u.getText().contains("不要停下来征求同意"));
        assertTrue(reDriven, instructionsText(requests.getAllValues().get(2)));
    }

    @Test void aPageThatNeverChangesTriggersAStallHint() {
        Fixture f = fixture("browser_action",
                "{\"success\":true,\"url\":\"https://flights.ctrip.com/online/channel\","
                        + "\"title\":\"携程机票\",\"final\":{\"main_text\":\"出发地 到达地 搜索\"}}");
        when(f.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(answer("改用结果页直达 URL。")));

        f.loop.run(f.session, "查一下机票价格", f.sink, f.model);

        // Six clicks all "succeeded" while the page never moved. Only the unchanged URL
        // exposes the deadlock, so the hint must appear on the seventh request.
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, times(7)).stream(requests.capture());
        boolean stallHint = requests.getAllValues().get(6).getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage u && u.getText().contains("同一个页面"));
        assertTrue(stallHint, instructionsText(requests.getAllValues().get(6)));
    }

    @Test void navigatingToANewPageClearsTheStallStreak() {
        Fixture f = fixture("browser_action", "unused");
        when(f.tool.call(anyString())).thenReturn(
                page("https://flights.ctrip.com/online/list/oneway-sjw-xmn"),
                page("https://flights.ctrip.com/online/list/oneway-pek-xmn"),
                page("https://flights.ctrip.com/online/list/oneway-tsn-xmn"),
                page("https://flights.ctrip.com/online/list/oneway-xmn-sjw"),
                page("https://flights.ctrip.com/online/list/oneway-xmn-pek"),
                page("https://flights.ctrip.com/online/list/oneway-xmn-tsn"));
        when(f.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(toolCall("browser_action", "")),
                Flux.just(answer("汇总完成。")));

        f.loop.run(f.session, "查一下机票价格", f.sink, f.model);

        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(f.model, times(7)).stream(requests.capture());
        boolean stallHint = requests.getAllValues().get(6).getInstructions().stream()
                .anyMatch(m -> m instanceof UserMessage u && u.getText().contains("同一个页面"));
        assertFalse(stallHint, "each call observed a different route page, so nothing is stalled");
    }

    private static String page(String url) {
        return "{\"success\":true,\"url\":\"" + url + "\",\"title\":\"携程机票\","
                + "\"final\":{\"main_text\":\"¥600起\"}}";
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(String result) {
        return fixture("browser_start", result);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(String toolName, String result) {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name(toolName)
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

    private static String instructionsText(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        for (var message : prompt.getInstructions()) text.append(message.getText()).append('\n');
        return text.toString();
    }

    private ChatResponse toolCall() {
        return toolCall("browser_start", "");
    }

    private ChatResponse toolCall(String name, String narration) {
        return response(AssistantMessage.builder().content(narration).toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", name, "{}"))).build());
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
