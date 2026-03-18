package com.dlchm.dlc.agent;

import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.tools.MemoryTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dlchm.dlc.session.Session;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final int MAX_TOOL_ITERATIONS = 100;
    private static final int MAX_ROLLBACKS = 3;
    private static final int MAX_TOOL_RESULT_LENGTH = 4000;
    private static final int LOOP_DETECT_THRESHOLD = 3;
    private static final int MAX_HISTORY_TOOL_RESULT_LENGTH = 800;
    private static final Pattern SCREENSHOT_PATTERN = Pattern.compile("\\[SCREENSHOT:([^\\]]+)]");
    private static final int MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final List<String> AGENT_MD_NAMES = List.of(
            "AGENT.md", "agent.md", "Agent.md"
    );
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolCallbackProvider toolCallbacks;
    private final SandboxPathResolver pathResolver;
    private final MemoryTool memoryTool;
    private final DlcProperties dlcProperties;
    private final ContextCompressor contextCompressor;
    private final String systemPromptTemplate;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String model;
    private String agentMdContent;

    public CodingAgent(
            ToolCallbackProvider toolCallbacks,
            SandboxPathResolver pathResolver,
            MemoryTool memoryTool,
            DlcProperties dlcProperties,
            String systemPromptTemplate,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.toolCallbacks = toolCallbacks;
        this.pathResolver = pathResolver;
        this.memoryTool = memoryTool;
        this.dlcProperties = dlcProperties;
        this.contextCompressor = new ContextCompressor(objectMapper);
        this.systemPromptTemplate = systemPromptTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.agentMdContent = loadAgentMd();
    }

    /**
     * 运行时更新 API 配置（/config 后立即生效，无需重启）。
     */
    public void reloadConfig() {
        String newBaseUrl = System.getProperty("spring.ai.openai.base-url");
        String newApiKey = System.getProperty("spring.ai.openai.api-key");
        String newModel = System.getProperty("spring.ai.openai.chat.options.model");
        if (newBaseUrl != null && !newBaseUrl.isBlank()) this.baseUrl = newBaseUrl;
        if (newApiKey != null && !newApiKey.isBlank()) this.apiKey = newApiKey;
        if (newModel != null && !newModel.isBlank()) this.model = newModel;
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
    public Flux<StreamEvent> stream(Session session, String userMessage) {
        return Flux.<StreamEvent>create(sink -> {
            reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
                try {
                    streamChat(session, userMessage, sink);
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
    public String chat(Session session, String userMessage) {
        StringBuilder result = new StringBuilder();
        stream(session, userMessage)
                .doOnNext(event -> {
                    if (event.type() == StreamEvent.Type.TOKEN) {
                        result.append(event.data());
                    }
                })
                .blockLast();
        return result.toString();
    }

    private void streamChat(Session session, String userMessage, FluxSink<StreamEvent> sink) throws Exception {
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
        for (ObjectNode historyMsg : session.getHistory()) {
            messages.add(historyMsg);
        }
        ObjectNode userMsg = objectMapper.createObjectNode()
                .put("role", "user").put("content", userMessage);
        messages.add(userMsg);

        // 上下文压缩：估算超过阈值时触发
        if (dlcProperties.isContextCompressionEnabled()) {
            boolean compressed = contextCompressor.compressIfNeeded(
                    messages, toolDefs, dlcProperties.getContextWindowTokens(),
                    baseUrl, apiKey, model);
            if (compressed) {
                // 压缩后同步更新 Session 历史，避免下次消息重复压缩
                List<ObjectNode> compressedHistory = new ArrayList<>();
                for (int ci = 1; ci < messages.size(); ci++) { // 跳过 system
                    JsonNode node = messages.get(ci);
                    if (node instanceof ObjectNode on) {
                        compressedHistory.add(on.deepCopy());
                    }
                }
                // 去掉最后一条（当前 userMsg），因为 saveToHistory 会重新添加
                if (!compressedHistory.isEmpty()) {
                    compressedHistory.remove(compressedHistory.size() - 1);
                }
                session.clearHistory();
                session.addMessages(compressedHistory);
                sink.next(new StreamEvent(StreamEvent.Type.TOKEN, "[上下文已压缩]\n"));
            }
        }

        String finalAssistantText = null;
        int rollbackCount = 0;
        boolean streamOptionsUnsupported = false;
        TokenUsage tokenUsage = new TokenUsage();
        List<String> recentAssistantTexts = new ArrayList<>(); // 用于循环检测

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.set("messages", messages);
            // 连续回退超过限制时，不再发送工具定义，强制模型纯文字回答
            boolean toolsDisabled = rollbackCount >= MAX_ROLLBACKS;
            if (!toolsDisabled && !toolDefs.isEmpty()) {
                requestBody.set("tools", toolDefs);
            }
            requestBody.put("temperature", 0.1);
            if (dlcProperties.getMaxCompletionTokens() > 0) {
                requestBody.put("max_tokens", dlcProperties.getMaxCompletionTokens());
            }
            requestBody.put("stream", true);
            // 请求返回 usage 信息（仅对支持 stream_options 的提供商生效，不支持的会忽略此字段或返回错误）
            // 注意：Ollama 等部分提供商不支持此参数，为安全起见仅在首次尝试时添加，
            // 若首次请求因此 400 则后续不再添加
            if (!streamOptionsUnsupported) {
                ObjectNode streamOptions = objectMapper.createObjectNode();
                streamOptions.put("include_usage", true);
                requestBody.set("stream_options", streamOptions);
            }

            // token 超 90% 时 warn
            int estimatedTokens = TokenEstimator.estimateMessages(messages)
                    + TokenEstimator.estimateToolDefs(toolDefs);
            int warnThreshold = (int) (dlcProperties.getContextWindowTokens() * 0.9);
            if (estimatedTokens > warnThreshold) {
                log.warn("token 估算 {} 已超过上下文窗口 90%（阈值 {}）", estimatedTokens, warnThreshold);
            }

            String requestJson = sanitizeJson(objectMapper.writeValueAsString(requestBody));
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
                String errorMsg = extractErrorMessage(errorBody);

                // 400 错误可能是 stream_options 不被支持，标记后重试当前轮
                if (response.statusCode() == 400 && !streamOptionsUnsupported
                        && (errorMsg.contains("stream_options") || errorMsg.contains("Unrecognized")
                            || errorMsg.contains("additional properties") || errorMsg.contains("unknown field"))) {
                    streamOptionsUnsupported = true;
                    log.info("API 不支持 stream_options，已禁用，重试当前请求");
                    i--; // 回退循环计数，重试当前轮
                    continue;
                }

                // 400/500 错误且不是第一轮 → 可能是上轮工具调用参数或内容导致，回退重试
                if ((response.statusCode() == 400 || response.statusCode() == 500) && i > 0) {
                    rollbackCount++;
                    log.warn("API {} error after tool call (rollback {}/{}): {}",
                            response.statusCode(), rollbackCount, MAX_ROLLBACKS, errorMsg);
                    // 移除上一轮的 assistant(tool_calls) + tool results
                    while (!messages.isEmpty()) {
                        JsonNode last = messages.get(messages.size() - 1);
                        String role = last.path("role").asText("");
                        if ("tool".equals(role) || (last.has("tool_calls"))) {
                            messages.remove(messages.size() - 1);
                        } else {
                            break;
                        }
                    }
                    // 同时移除之前可能累积的回退提示，避免重复
                    while (!messages.isEmpty()) {
                        JsonNode last = messages.get(messages.size() - 1);
                        String content = last.path("content").asText("");
                        if ("user".equals(last.path("role").asText(""))
                                && content.startsWith("[系统提示]")) {
                            messages.remove(messages.size() - 1);
                        } else {
                            break;
                        }
                    }
                    if (rollbackCount >= MAX_ROLLBACKS) {
                        // 已多次失败，告诉模型不用工具直接回答
                        messages.add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .put("content", "[系统提示] 工具调用多次失败，请根据已获得的信息直接用文字回答用户的问题。"));
                    } else {
                        // 还有重试机会，提示修正参数
                        messages.add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .put("content", "[系统提示] 上次工具调用参数格式有误(错误:" + errorMsg
                                        + ")，请检查JSON格式后重新调用。"));
                    }
                    continue;
                }

                throw new RuntimeException("API error (" + response.statusCode() + "): " + errorMsg);
            }

            // 成功请求后重置回退计数
            rollbackCount = 0;

            // Parse SSE stream
            StringBuilder contentBuilder = new StringBuilder();
            Map<Integer, ToolCallAccumulator> accumulators = new LinkedHashMap<>();
            final TokenUsage iterationUsage = tokenUsage; // effectively final ref for lambda

            try (var lines = response.body()) {
                lines.forEach(line -> {
                    if (!line.startsWith("data: ")) return;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) return;

                    try {
                        JsonNode chunk = objectMapper.readTree(data);

                        // 提取 usage 信息（stream_options.include_usage=true 时在最后一个 chunk）
                        JsonNode usage = chunk.path("usage");
                        if (usage.isObject() && usage.has("total_tokens")) {
                            iterationUsage.add(
                                    usage.path("prompt_tokens").asInt(0),
                                    usage.path("completion_tokens").asInt(0),
                                    usage.path("total_tokens").asInt(0));
                        }

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
                // 发送 token 用量事件
                if (tokenUsage.hasData()) {
                    sink.next(new StreamEvent(StreamEvent.Type.USAGE, tokenUsage.toString()));
                }
                saveToHistory(session, userMsg, messages, finalAssistantText);
                return;
            }

            // === 循环检测：如果模型连续多次输出相似内容，说明在原地打转 ===
            String currentText = contentBuilder.toString().trim();
            if (!currentText.isEmpty()) {
                // 取前80个字符作为签名比较
                String sig = currentText.length() > 80 ? currentText.substring(0, 80) : currentText;
                recentAssistantTexts.add(sig);
                // 只保留最近的记录
                if (recentAssistantTexts.size() > LOOP_DETECT_THRESHOLD + 2) {
                    recentAssistantTexts.remove(0);
                }
                // 检查最近 N 次是否相同
                if (recentAssistantTexts.size() >= LOOP_DETECT_THRESHOLD) {
                    String last = recentAssistantTexts.get(recentAssistantTexts.size() - 1);
                    long sameCount = recentAssistantTexts.stream().filter(s -> s.equals(last)).count();
                    if (sameCount >= LOOP_DETECT_THRESHOLD) {
                        log.warn("Loop detected: assistant repeated similar output {} times, breaking", sameCount);
                        sink.next(new StreamEvent(StreamEvent.Type.TOKEN,
                                "\n\n[检测到循环，自动终止。请尝试简化指令或更换模型。]"));
                        saveToHistory(session, userMsg, messages, currentText + "\n[循环终止]");
                        return;
                    }
                }
            }

            // 先修复所有工具参数，过滤掉无法修复的
            for (ToolCallAccumulator acc : accumulators.values()) {
                String argsStr = acc.arguments.toString().trim();
                argsStr = fixToolArguments(argsStr);
                acc.arguments.setLength(0);
                acc.arguments.append(argsStr);
            }

            // Build assistant message with tool_calls (使用修复后的参数)
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
                tc.put("id", acc.id != null ? acc.id : "call_" + System.nanoTime());
                tc.put("type", "function");
                ObjectNode fn = objectMapper.createObjectNode();
                fn.put("name", acc.name != null ? acc.name : "unknown");
                fn.put("arguments", acc.arguments.toString());
                tc.set("function", fn);
                toolCallsArray.add(tc);
            }
            assistantMsg.set("tool_calls", toolCallsArray);
            messages.add(assistantMsg);

            // Execute tools
            for (ToolCallAccumulator acc : accumulators.values()) {
                String argsStr = acc.arguments.toString();
                ToolCallback callback = toolMap.get(acc.name);
                String result;
                if (callback != null) {
                    try {
                        result = callback.call(argsStr);
                    } catch (Exception e) {
                        result = "Error executing " + acc.name + ": " + e.getMessage();
                    }
                } else {
                    result = "Error: Unknown tool '" + acc.name + "'";
                }

                // 截断过大的工具返回结果，防止上下文膨胀导致模型迷失
                if (result.length() > MAX_TOOL_RESULT_LENGTH) {
                    result = result.substring(0, MAX_TOOL_RESULT_LENGTH)
                            + "\n...(结果已截断，共 " + result.length() + " 字符)";
                }

                String callId = acc.id != null ? acc.id : "call_" + System.nanoTime();
                messages.add(objectMapper.createObjectNode()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result));
            }

            // Vision: 检测工具结果中的截图，注入多模态消息让模型"看到"图片
            if (dlcProperties.isVisionEnabled()) {
                injectScreenshotImages(messages);
            }

            // 工具循环中：仅当 token 超过 90% 阈值时才触发压缩（避免每轮都调 LLM 摘要）
            if (dlcProperties.isContextCompressionEnabled()) {
                int loopTokens = TokenEstimator.estimateMessages(messages)
                        + TokenEstimator.estimateToolDefs(toolDefs);
                if (loopTokens > (int) (dlcProperties.getContextWindowTokens() * 0.9)) {
                    boolean compressed = contextCompressor.compressIfNeeded(
                            messages, toolDefs, dlcProperties.getContextWindowTokens(),
                            baseUrl, apiKey, model);
                    if (compressed) {
                        sink.next(new StreamEvent(StreamEvent.Type.TOKEN, "[上下文已压缩]\n"));
                    }
                }
            }
            // Loop continues → next streaming request with tool results
        }

        // 达到最大迭代次数，仍保存对话记录
        if (tokenUsage.hasData()) {
            sink.next(new StreamEvent(StreamEvent.Type.USAGE, tokenUsage.toString()));
        }
        saveToHistory(session, userMsg, messages, "(max tool iterations reached)");
        throw new RuntimeException("Maximum tool iterations (" + MAX_TOOL_ITERATIONS + ") reached.");
    }

    // ==================== Conversation History ====================

    private void saveToHistory(Session session, ObjectNode userMsg, ArrayNode messages, String finalAssistantText) {
        // 从 messages 中提取当前轮次的消息（userMsg 之后的所有 assistant+tool_calls、tool result）
        List<ObjectNode> turnMessages = new ArrayList<>();
        turnMessages.add(userMsg);

        // 找到 userMsg 在 messages 中的位置
        int userMsgIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) == userMsg) {
                userMsgIndex = i;
                break;
            }
        }

        boolean hasToolCalls = false;
        if (userMsgIndex >= 0) {
            for (int i = userMsgIndex + 1; i < messages.size(); i++) {
                JsonNode msg = messages.get(i);
                if (!(msg instanceof ObjectNode)) continue;
                ObjectNode objMsg = (ObjectNode) msg;
                String role = objMsg.path("role").asText("");

                // 过滤掉回退恢复的 [系统提示] 消息
                if ("user".equals(role) && objMsg.path("content").asText("").startsWith("[系统提示]")) {
                    continue;
                }
                // 过滤掉多模态截图消息（content 为 array 类型，体积过大不宜存入历史）
                if ("user".equals(role) && objMsg.path("content").isArray()) {
                    continue;
                }

                if ("assistant".equals(role) && objMsg.has("tool_calls")) {
                    hasToolCalls = true;
                    turnMessages.add(objMsg);
                } else if ("tool".equals(role)) {
                    // 截断 tool result 到较短长度以节省 token
                    String content = objMsg.path("content").asText("");
                    if (content.length() > MAX_HISTORY_TOOL_RESULT_LENGTH) {
                        objMsg = objMsg.deepCopy();
                        objMsg.put("content", content.substring(0, MAX_HISTORY_TOOL_RESULT_LENGTH)
                                + "\n...(历史截断，共 " + content.length() + " 字符)");
                    }
                    turnMessages.add(objMsg);
                }
            }
        }

        // 添加最终的纯文本 assistant 回复
        if (finalAssistantText != null && !finalAssistantText.isEmpty()) {
            turnMessages.add(objectMapper.createObjectNode()
                    .put("role", "assistant")
                    .put("content", finalAssistantText));
        } else if (!hasToolCalls) {
            // 无工具调用且无文本，仍保存空 assistant 回复
            turnMessages.add(objectMapper.createObjectNode()
                    .put("role", "assistant")
                    .put("content", ""));
        }

        // 批量添加，避免逐条 add 时 trimHistory 在中间截断 tool_call/tool 对
        session.addMessages(turnMessages);
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

    /**
     * 修复小模型生成的不合规工具参数 JSON。
     * 常见问题：空字符串、非 JSON 文本、缺少大括号、尾部逗号、单引号等。
     */
    private String fixToolArguments(String args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }

        // 去掉可能的 markdown 代码块包裹
        if (args.startsWith("```")) {
            args = args.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }

        // 尝试直接解析
        try {
            objectMapper.readTree(args);
            return args;
        } catch (Exception ignored) {}

        // 修复单引号 → 双引号
        String fixed = args.replace('\'', '"');
        try {
            objectMapper.readTree(fixed);
            return fixed;
        } catch (Exception ignored) {}

        // 去掉尾部逗号 (trailing comma before })
        fixed = fixed.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");
        try {
            objectMapper.readTree(fixed);
            return fixed;
        } catch (Exception ignored) {}

        // 补全缺失的大括号
        if (!fixed.startsWith("{") && !fixed.startsWith("[")) {
            fixed = "{" + fixed;
        }
        if (fixed.startsWith("{") && !fixed.endsWith("}")) {
            fixed = fixed + "}";
        }
        try {
            objectMapper.readTree(fixed);
            return fixed;
        } catch (Exception ignored) {}

        // 实在无法修复，返回空对象
        log.warn("Cannot fix tool arguments, using empty: {}", args);
        return "{}";
    }

    /**
     * 清理 JSON 字符串中的无效转义序列。
     * Ollama (Go) 的 JSON 解析器比 Java 更严格，不允许 \+非法字符（如 \空格、\: 等）。
     */
    private String sanitizeJson(String json) {
        // 修复 JSON 字符串内部的无效转义序列（Ollama Go 解析器不允许 \+非法字符）
        // 将孤立的 \ 替换为 \\，合法转义原样保留
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
            } else if (inString && c == '\\') {
                if (i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '/' || next == 'b'
                            || next == 'f' || next == 'n' || next == 'r' || next == 't' || next == 'u') {
                        sb.append(c).append(next); // 合法转义，保留整对
                        i++; // 跳过下一个字符，避免被重复处理
                    } else {
                        sb.append('\\').append('\\'); // 非法转义，双写反斜杠
                    }
                } else {
                    sb.append('\\').append('\\');
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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

    // ==================== Vision: Screenshot → Multimodal ====================

    /**
     * 扫描最近的 tool 消息，检测 [SCREENSHOT:path] 标记。
     * 对每个截图读取文件、base64 编码，注入多模态 user 消息让模型"看到"图片。
     */
    private void injectScreenshotImages(ArrayNode messages) {
        // 从末尾向前找所有连续的 tool 消息
        List<String> screenshotPaths = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode msg = messages.get(i);
            if (!"tool".equals(msg.path("role").asText(""))) break;
            String content = msg.path("content").asText("");
            Matcher m = SCREENSHOT_PATTERN.matcher(content);
            while (m.find()) {
                screenshotPaths.add(m.group(1));
            }
        }
        if (screenshotPaths.isEmpty()) return;

        // 只取最后一张截图（避免注入过多图片膨胀上下文）
        String lastScreenshot = screenshotPaths.get(screenshotPaths.size() - 1);
        ObjectNode imageMsg = buildMultimodalImageMessage(lastScreenshot);
        if (imageMsg != null) {
            messages.add(imageMsg);
            log.info("注入截图多模态消息: {}", lastScreenshot);
        }
    }

    /**
     * 读取图片文件，构建 OpenAI 兼容的多模态 user 消息。
     */
    private ObjectNode buildMultimodalImageMessage(String imagePath) {
        try {
            Path path = Path.of(imagePath.trim());
            if (!Files.exists(path)) {
                log.warn("截图文件不存在: {}", imagePath);
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_IMAGE_SIZE) {
                log.warn("截图文件过大({}MB)，跳过: {}", bytes.length / 1024 / 1024, imagePath);
                return null;
            }
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = imagePath.endsWith(".jpg") || imagePath.endsWith(".jpeg")
                    ? "image/jpeg" : "image/png";
            String dataUrl = "data:" + mimeType + ";base64," + base64;

            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", "user");
            ArrayNode content = objectMapper.createArrayNode();
            content.add(objectMapper.createObjectNode()
                    .put("type", "text")
                    .put("text", "[系统：以上工具调用产生了浏览器截图，请仔细分析截图内容。"
                            + "如果看到验证码，请识别验证码要求并使用 click_xy 在正确的坐标位置点击完成验证。"
                            + "截图坐标系：左上角为(0,0)，向右为x正方向，向下为y正方向。]"));
            ObjectNode imagePart = objectMapper.createObjectNode();
            imagePart.put("type", "image_url");
            imagePart.set("image_url", objectMapper.createObjectNode().put("url", dataUrl));
            content.add(imagePart);
            msg.set("content", content);
            return msg;
        } catch (Exception e) {
            log.warn("读取截图失败: {}", e.getMessage());
            return null;
        }
    }

}
