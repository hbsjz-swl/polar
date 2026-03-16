package com.dlchm.dlc.agent;

import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.tools.MemoryTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 本地 Coding Agent：直接调用 OpenAI 兼容 REST API，手动执行工具循环。
 * 流式输出（SSE），自行聚合 tool_call chunks，兼容 DashScope。
 */
@Component
public class CodingAgent {

    private static final Logger log = LoggerFactory.getLogger(CodingAgent.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TOOL_ITERATIONS = 25;
    private static final int MAX_HISTORY_MESSAGES = 40; // 保留最近 20 轮对话（user + assistant）
    private static final List<String> AGENT_MD_NAMES = List.of(
            "AGENT.md", "agent.md", "Agent.md"
    );
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ObjectNode> conversationHistory = new ArrayList<>();
    private final ToolCallbackProvider toolCallbacks;
    private final SandboxPathResolver pathResolver;
    private final MemoryTool memoryTool;
    private final String systemPromptTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private String agentMdContent;

    public CodingAgent(
            ToolCallbackProvider toolCallbacks,
            SandboxPathResolver pathResolver,
            MemoryTool memoryTool,
            String systemPromptTemplate,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.toolCallbacks = toolCallbacks;
        this.pathResolver = pathResolver;
        this.memoryTool = memoryTool;
        this.systemPromptTemplate = systemPromptTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.agentMdContent = loadAgentMd();
    }

    // ==================== AGENT.md ====================

    private String loadAgentMd() {
        Path root = pathResolver.getWorkspaceRoot();
        for (String name : AGENT_MD_NAMES) {
            Path p = root.resolve(name);
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return readAgentMd(p);
            }
        }
        try (var fileStream = Files.list(root)) {
            Path found = fileStream
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("agent.md"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
            if (found != null) {
                return readAgentMd(found);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private String readAgentMd(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (!content.isEmpty()) {
                return content;
            }
        } catch (IOException ignored) {}
        return null;
    }

    public void reloadAgentMd() {
        this.agentMdContent = loadAgentMd();
    }

    // ==================== 流式调用 ====================

    /**
     * 流式调用 LLM，逐 token 输出。工具调用自动执行后继续流式输出。
     */
    public Flux<StreamEvent> stream(String userMessage) {
        return Flux.<StreamEvent>create(sink -> {
            reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
                try {
                    streamChat(userMessage, sink);
                    sink.complete();
                } catch (Exception e) {
                    sink.error(e);
                }
            });
        });
    }

    /**
     * 非流式调用（同步），内部收集流式结果。
     */
    public String chat(String userMessage) {
        StringBuilder result = new StringBuilder();
        stream(userMessage)
                .doOnNext(event -> {
                    if (event.type() == StreamEvent.Type.TOKEN) {
                        result.append(event.data());
                    }
                })
                .blockLast();
        return result.toString();
    }

    private void streamChat(String userMessage, FluxSink<StreamEvent> sink) throws Exception {
        ToolCallback[] callbacks = toolCallbacks.getToolCallbacks();
        Map<String, ToolCallback> toolMap = new HashMap<>();
        for (ToolCallback cb : callbacks) {
            toolMap.put(cb.getToolDefinition().name(), cb);
        }

        String systemText = systemPromptTemplate
                .replace("{current_time}", ZonedDateTime.now().format(TIME_FMT))
                .replace("{working_dir}", pathResolver.getWorkspaceRoot().toString())
                .replace("{agent_md}", buildAgentMdSection())
                .replace("{memory}", memoryTool.loadAllMemory());

        ArrayNode toolDefs = buildToolDefinitions(callbacks);

        // 构建 messages：system + 历史对话 + 当前 user message
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode()
                .put("role", "system").put("content", systemText));
        synchronized (conversationHistory) {
            for (ObjectNode historyMsg : conversationHistory) {
                messages.add(historyMsg);
            }
        }
        ObjectNode userMsg = objectMapper.createObjectNode()
                .put("role", "user").put("content", userMessage);
        messages.add(userMsg);

        String finalAssistantText = null;

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.set("messages", messages);
            if (toolDefs.size() > 0) {
                requestBody.set("tools", toolDefs);
            }
            requestBody.put("temperature", 0.1);
            requestBody.put("stream", true);

            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                String errorBody;
                try (var lines = response.body()) {
                    errorBody = lines.collect(Collectors.joining("\n"));
                }
                throw new RuntimeException("API error (" + response.statusCode() + "): "
                        + extractErrorMessage(errorBody));
            }

            // Parse SSE stream
            StringBuilder contentBuilder = new StringBuilder();
            Map<Integer, ToolCallAccumulator> accumulators = new LinkedHashMap<>();

            try (var lines = response.body()) {
                lines.forEach(line -> {
                    if (!line.startsWith("data: ")) return;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) return;

                    try {
                        JsonNode chunk = objectMapper.readTree(data);
                        JsonNode delta = chunk.path("choices").path(0).path("delta");

                        // Text content → emit immediately
                        String content = delta.path("content").asText(null);
                        if (content != null && !content.isEmpty()) {
                            sink.next(new StreamEvent(StreamEvent.Type.TOKEN, content));
                            contentBuilder.append(content);
                        }

                        // Reasoning content (Qwen thinking tokens)
                        String reasoning = delta.path("reasoning_content").asText(null);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            sink.next(new StreamEvent(StreamEvent.Type.REASONING, reasoning));
                        }

                        // Tool calls → accumulate across chunks
                        JsonNode toolCalls = delta.path("tool_calls");
                        if (toolCalls.isArray()) {
                            for (JsonNode tc : toolCalls) {
                                int index = tc.path("index").asInt(0);
                                ToolCallAccumulator acc = accumulators.computeIfAbsent(
                                        index, k -> new ToolCallAccumulator());
                                String id = tc.path("id").asText(null);
                                if (id != null && !id.isEmpty()) acc.id = id;
                                // DashScope 只在第一个 chunk 发 function.name，后续为空
                                String name = tc.path("function").path("name").asText(null);
                                if (name != null && !name.isEmpty()) acc.name = name;
                                String args = tc.path("function").path("arguments").asText(null);
                                if (args != null) acc.arguments.append(args);
                            }
                        }
                    } catch (Exception ignored) {
                        // Skip malformed chunks
                    }
                });
            }

            // No tool calls → done, save to conversation history
            if (accumulators.isEmpty()) {
                finalAssistantText = contentBuilder.toString();
                saveToHistory(userMsg, finalAssistantText);
                return;
            }

            // Build assistant message with tool_calls for conversation history
            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            if (contentBuilder.length() > 0) {
                assistantMsg.put("content", contentBuilder.toString());
            } else {
                assistantMsg.putNull("content");
            }
            ArrayNode toolCallsArray = objectMapper.createArrayNode();
            for (ToolCallAccumulator acc : accumulators.values()) {
                ObjectNode tc = objectMapper.createObjectNode();
                tc.put("id", acc.id);
                tc.put("type", "function");
                ObjectNode fn = objectMapper.createObjectNode();
                fn.put("name", acc.name);
                fn.put("arguments", acc.arguments.toString());
                tc.set("function", fn);
                toolCallsArray.add(tc);
            }
            assistantMsg.set("tool_calls", toolCallsArray);
            messages.add(assistantMsg);

            // Execute tools
            for (ToolCallAccumulator acc : accumulators.values()) {
                ToolCallback callback = toolMap.get(acc.name);
                String result;
                if (callback != null) {
                    try {
                        result = callback.call(acc.arguments.toString());
                    } catch (Exception e) {
                        result = "Error executing " + acc.name + ": " + e.getMessage();
                    }
                } else {
                    result = "Error: Unknown tool '" + acc.name + "'";
                }

                messages.add(objectMapper.createObjectNode()
                        .put("role", "tool")
                        .put("tool_call_id", acc.id)
                        .put("content", result));
            }
            // Loop continues → next streaming request with tool results
        }

        // 达到最大迭代次数，仍保存对话记录
        saveToHistory(userMsg, "(max tool iterations reached)");
        throw new RuntimeException("Maximum tool iterations (" + MAX_TOOL_ITERATIONS + ") reached.");
    }

    // ==================== Conversation History ====================

    private void saveToHistory(ObjectNode userMsg, String assistantText) {
        synchronized (conversationHistory) {
            conversationHistory.add(userMsg.deepCopy());
            conversationHistory.add(objectMapper.createObjectNode()
                    .put("role", "assistant")
                    .put("content", assistantText != null ? assistantText : ""));
            // 保留最近 MAX_HISTORY_MESSAGES 条消息
            while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
                conversationHistory.remove(0);
            }
        }
    }

    /**
     * 清空会话历史（/clear 命令使用）。
     */
    public void clearHistory() {
        synchronized (conversationHistory) {
            conversationHistory.clear();
        }
    }

    // ==================== Tool call chunk accumulator ====================

    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    // ==================== Helpers ====================

    private ArrayNode buildToolDefinitions(ToolCallback[] callbacks) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (ToolCallback cb : callbacks) {
            var def = cb.getToolDefinition();
            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", def.name());
            function.put("description", def.description());
            try {
                function.set("parameters", objectMapper.readTree(def.inputSchema()));
            } catch (Exception e) {
                function.set("parameters", objectMapper.createObjectNode());
            }
            tool.set("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("error").path("message").asText(responseBody);
        } catch (Exception e) {
            return responseBody;
        }
    }

    private String buildAgentMdSection() {
        if (agentMdContent == null || agentMdContent.isEmpty()) {
            return "";
        }
        return "\n\n## Project Rules (from AGENT.md)\n\n" + agentMdContent;
    }

    public record StreamEvent(Type type, String data) {
        public enum Type { TOKEN, REASONING }
    }
}
