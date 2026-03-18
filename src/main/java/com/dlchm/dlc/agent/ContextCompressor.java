package com.dlchm.dlc.agent;

import com.dlchm.dlc.session.Session;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文压缩器：当消息 token 估算超过上下文窗口 75% 时，
 * 用 LLM 摘要替代早期消息，保留最近 4 轮对话。
 */
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);
    private static final double COMPRESSION_THRESHOLD = 0.75;
    private static final int RECENT_TURNS_TO_KEEP = 4;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ObjectMapper objectMapper;

    public ContextCompressor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 估算当前消息 + 工具定义的 token 是否超过阈值，超过则压缩。
     * 返回压缩后的 messages（原 ArrayNode 会被修改）。
     *
     * @return true 如果执行了压缩
     */
    public boolean compressIfNeeded(ArrayNode messages, ArrayNode toolDefs, int contextLimit,
                                     String baseUrl, String apiKey, String model) {
        int msgTokens = TokenEstimator.estimateMessages(messages);
        int toolTokens = TokenEstimator.estimateToolDefs(toolDefs);
        int totalTokens = msgTokens + toolTokens;
        int threshold = (int) (contextLimit * COMPRESSION_THRESHOLD);

        if (totalTokens <= threshold) {
            return false;
        }

        log.info("上下文估算 {} tokens（阈值 {}），触发压缩", totalTokens, threshold);

        // 分离 system message（index 0）和其余消息
        if (messages.size() <= 1) return false;

        ObjectNode systemMsg = (ObjectNode) messages.get(0);

        // 计算要保留的最近消息（按轮次：user + assistant/tool 为一轮）
        List<JsonNode> allMsgs = new ArrayList<>();
        for (int i = 1; i < messages.size(); i++) {
            allMsgs.add(messages.get(i));
        }

        // 从后往前数 RECENT_TURNS_TO_KEEP 轮（每轮以 user 消息起始）
        int keepFromIndex = findKeepBoundary(allMsgs);
        if (keepFromIndex <= 0) {
            // 没有足够的消息可以压缩
            return false;
        }

        // 要压缩的早期消息
        List<JsonNode> earlyMsgs = allMsgs.subList(0, keepFromIndex);
        List<JsonNode> recentMsgs = allMsgs.subList(keepFromIndex, allMsgs.size());

        // 构建摘要文本
        StringBuilder earlyText = new StringBuilder();
        for (JsonNode msg : earlyMsgs) {
            String role = msg.path("role").asText("");
            String content = msg.path("content").asText("");
            if ("assistant".equals(role) && msg.has("tool_calls")) {
                earlyText.append("[assistant 调用了工具: ");
                JsonNode tcs = msg.path("tool_calls");
                for (JsonNode tc : tcs) {
                    earlyText.append(tc.path("function").path("name").asText(""));
                    earlyText.append(" ");
                }
                earlyText.append("]\n");
                if (!content.isEmpty()) {
                    earlyText.append(content).append("\n");
                }
            } else if ("tool".equals(role)) {
                String toolContent = content.length() > 200
                        ? content.substring(0, 200) + "..." : content;
                earlyText.append("[tool result] ").append(toolContent).append("\n");
            } else {
                earlyText.append("[").append(role).append("] ").append(content).append("\n");
            }
        }

        // 调用 LLM 生成摘要
        String summary = generateSummary(earlyText.toString(), baseUrl, apiKey, model);
        if (summary == null) {
            log.warn("摘要生成失败，跳过压缩");
            return false;
        }

        // 重建 messages：system + 摘要 + 最近消息
        messages.removeAll();
        messages.add(systemMsg);
        messages.add(objectMapper.createObjectNode()
                .put("role", "user")
                .put("content", "[上下文已压缩] 以下是之前对话的摘要：\n" + summary));
        messages.add(objectMapper.createObjectNode()
                .put("role", "assistant")
                .put("content", "好的，我已了解之前的对话内容，请继续。"));
        for (JsonNode msg : recentMsgs) {
            messages.add(msg);
        }

        log.info("上下文压缩完成：{} 条早期消息 → 摘要，保留 {} 条最近消息",
                earlyMsgs.size(), recentMsgs.size());
        return true;
    }

    /**
     * 压缩 Session 历史（在 session 对象上直接操作）。
     */
    public boolean compressSessionHistory(Session session, ArrayNode toolDefs, int contextLimit,
                                           String baseUrl, String apiKey, String model) {
        List<ObjectNode> history = session.getHistory();
        if (history.isEmpty()) return false;

        ArrayNode messages = objectMapper.createArrayNode();
        // 模拟一个 system 占位
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content", ""));
        for (ObjectNode msg : history) {
            messages.add(msg.deepCopy());
        }

        boolean compressed = compressIfNeeded(messages, toolDefs, contextLimit, baseUrl, apiKey, model);
        if (compressed) {
            // 更新 session 历史（去掉 system 占位）
            List<ObjectNode> newHistory = new ArrayList<>();
            for (int i = 1; i < messages.size(); i++) {
                newHistory.add((ObjectNode) messages.get(i));
            }
            session.clearHistory();
            session.addMessages(newHistory);
        }
        return compressed;
    }

    /**
     * 从后往前找到保留边界：保留最近 RECENT_TURNS_TO_KEEP 轮对话。
     */
    private int findKeepBoundary(List<JsonNode> msgs) {
        int turnsFound = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).path("role").asText(""))) {
                turnsFound++;
                if (turnsFound >= RECENT_TURNS_TO_KEEP) {
                    return i;
                }
            }
        }
        // 不够 RECENT_TURNS_TO_KEEP 轮，不压缩
        return 0;
    }

    /**
     * 同步非流式 API 调用生成摘要。
     */
    private String generateSummary(String conversationText, String baseUrl, String apiKey, String model) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("temperature", 0);
            requestBody.put("max_tokens", 800);
            requestBody.put("stream", false);

            ArrayNode messages = objectMapper.createArrayNode();
            messages.add(objectMapper.createObjectNode()
                    .put("role", "system")
                    .put("content", "你是一个对话摘要助手。请将以下对话历史压缩为简洁的摘要，保留关键信息：" +
                            "用户的主要需求、已完成的操作、重要的文件路径和代码变更、遇到的问题和解决方案。" +
                            "输出纯文本摘要，不超过 500 字。"));
            messages.add(objectMapper.createObjectNode()
                    .put("role", "user")
                    .put("content", conversationText));
            requestBody.set("messages", messages);

            String json = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("摘要 API 返回 {}：{}", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("生成摘要异常：{}", e.getMessage());
            return null;
        }
    }
}
