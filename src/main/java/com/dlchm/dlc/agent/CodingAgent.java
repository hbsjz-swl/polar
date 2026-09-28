package com.dlchm.dlc.agent;

import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.MarkdownSessionStore;
import com.dlchm.dlc.tools.MemoryTool;
import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Public facade shared by CLI, REST, WebSocket and WeCom channels. */
@Component
public class CodingAgent {
    private final AgentLoop loop;
    private final DlcProperties properties;
    private final AgnesRequestRateLimiter rateLimiter;
    private final ApprovalManager approvalManager;
    private volatile OpenAiChatModel model;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String modelName;

    public CodingAgent(ToolCallbackProvider toolCallbacks, SandboxPathResolver pathResolver,
                       MemoryTool memoryTool, DlcProperties properties, String systemPromptTemplate,
                       ApprovalManager approvalManager, MarkdownSessionStore sessionStore,
                       @Value("${spring.ai.openai.base-url}") String baseUrl,
                       @Value("${spring.ai.openai.api-key}") String apiKey,
                       @Value("${spring.ai.openai.chat.options.model}") String modelName) {
        this.rateLimiter = new AgnesRequestRateLimiter();
        this.approvalManager = approvalManager;
        this.loop = new AgentLoop(toolCallbacks, pathResolver, memoryTool, properties,
                systemPromptTemplate, rateLimiter, approvalManager, sessionStore);
        this.properties = properties;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.model = buildModel();
    }

    /** /config changes the model connection without restarting the channels. */
    public synchronized void reloadConfig() {
        String url = System.getProperty("spring.ai.openai.base-url");
        String key = System.getProperty("spring.ai.openai.api-key");
        String name = System.getProperty("spring.ai.openai.chat.options.model");
        if (url != null && !url.isBlank()) baseUrl = url;
        if (key != null && !key.isBlank()) apiKey = key;
        if (name != null && !name.isBlank()) modelName = name;
        model = buildModel();
    }

    public void reloadAgentMd() {
        loop.reloadAgentMd();
    }

    public Flux<StreamEvent> stream(Session session, String userMessage) {
        return Flux.create(sink -> {
            OpenAiChatModel current = model;
            if (!sink.isCancelled()) {
                sink.next(new StreamEvent(StreamEvent.Type.TURN_STARTED,
                        "{\"sessionId\":\"" + escape(session.getId()) + "\"}"));
            }
            var task = Schedulers.boundedElastic().schedule(() -> {
                synchronized (session) {
                    try {
                        ExecutionContext.run(session, 0,
                                () -> loop.run(session, userMessage, sink, current));
                        if (!sink.isCancelled()) {
                            sink.next(new StreamEvent(StreamEvent.Type.TURN_COMPLETED, "{}"));
                        }
                        if (!sink.isCancelled()) sink.complete();
                    } catch (Throwable e) {
                        if (!sink.isCancelled()) {
                            sink.next(new StreamEvent(StreamEvent.Type.TURN_FAILED,
                                    "{\"error\":\"" + escape(String.valueOf(e.getMessage())) + "\"}"));
                            sink.error(e);
                        }
                    }
                }
            });
            sink.onCancel(() -> {
                task.dispose();
                approvalManager.cancelSession(session.getId());
            });
        });
    }

    public String chat(Session session, String userMessage) {
        StringBuilder result = new StringBuilder();
        stream(session, userMessage)
                .doOnNext(event -> {
                    if (event.type() == StreamEvent.Type.TOKEN) result.append(event.data());
                }).blockLast();
        return result.toString();
    }

    private OpenAiChatModel buildModel() {
        String url = baseUrl.strip().replaceAll("/+$", "");
        if (!url.endsWith("/v1")) url += "/v1";
        var builder = OpenAiChatOptions.builder()
                .baseUrl(url)
                .apiKey(apiKey)
                .model(modelName)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(0)
                .strict(false);
        if (properties.getMaxCompletionTokens() > 0) {
            builder.maxCompletionTokens(properties.getMaxCompletionTokens());
        }
        return OpenAiChatModel.builder().options(builder.build()).build();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
