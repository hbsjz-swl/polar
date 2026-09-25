package com.dlchm.dlc.agent;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * Compresses history before each Chat Completions request. Recent Message
 * objects remain intact so assistant tool calls and tool results can be replayed.
 */
public final class ContextCompressor {
    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);
    private static final int MAX_SUMMARY_INPUT_CHARS = 24_000;
    private static final int MAX_SUMMARY_CHARS = 2_400;
    private final AgnesRequestRateLimiter rateLimiter;

    public ContextCompressor(AgnesRequestRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public boolean compressIfNeeded(List<Message> messages, ToolCallback[] tools, int configuredWindow,
                                    OpenAiChatModel model) {
        int budget = Math.max(2_048, Math.min(configuredWindow, 32_768));
        int threshold = (int) (budget * 0.70);
        if (estimate(messages, tools) <= threshold) return false;
        boolean changed = false;

        int keepFrom = findKeepBoundary(messages, 2);
        if (keepFrom > 1) {
            String input = summarizeInput(messages.subList(1, keepFrom));
            String summary = generateSummary(input, model);
            if (summary == null || summary.isBlank()) {
                summary = localSummary(input);
                log.warn("Model summary unavailable; used bounded local summary");
            }
            replacePrefix(messages, keepFrom, summary);
            changed = true;
        }

        if (estimate(messages, tools) > threshold) {
            int lastUser = findKeepBoundary(messages, 1);
            if (lastUser > 1) {
                String summary = localSummary(summarizeInput(messages.subList(1, lastUser)));
                replacePrefix(messages, lastUser, summary);
                changed = true;
            }
        }

        if (estimate(messages, tools) > threshold) {
            int lastUser = findKeepBoundary(messages, 1);
            if (lastUser > 0 && lastUser < messages.size() - 1) {
                // The current tool chain itself is oversized. Summarize the
                // completed steps and restart the model from the user request.
                String progress = localSummary(summarizeInput(messages.subList(lastUser + 1, messages.size())));
                messages.subList(lastUser + 1, messages.size()).clear();
                messages.add(new UserMessage("[本轮执行进度摘要]\n" + progress + "\n请继续完成用户请求。"));
                changed = true;
            }
        }

        if (changed) log.info("Context compacted to approximately {} tokens", estimate(messages, tools));
        return changed;
    }

    private int estimate(List<Message> messages, ToolCallback[] tools) {
        return TokenEstimator.estimateMessages(messages) + TokenEstimator.estimateToolDefs(tools);
    }

    private void replacePrefix(List<Message> messages, int keepFrom, String summary) {
        List<Message> recent = new ArrayList<>(messages.subList(keepFrom, messages.size()));
        Message system = messages.get(0);
        messages.clear();
        messages.add(system);
        messages.add(new UserMessage("[上下文摘要]\n" + limit(summary, MAX_SUMMARY_CHARS)));
        messages.add(new AssistantMessage("已了解之前的对话，请继续。"));
        messages.addAll(recent);
    }

    private int findKeepBoundary(List<Message> messages, int turns) {
        int found = 0;
        for (int i = messages.size() - 1; i > 0; i--) {
            if (messages.get(i) instanceof UserMessage user
                    && user.getMedia().isEmpty()
                    && !user.getText().startsWith("[系统提示]")
                    && !user.getText().startsWith("[本轮执行进度摘要]")) {
                if (++found == turns) return i;
            }
        }
        return 0;
    }

    private String summarizeInput(List<Message> messages) {
        List<String> lines = new ArrayList<>();
        int chars = 0;
        for (int i = messages.size() - 1; i >= 0 && chars < MAX_SUMMARY_INPUT_CHARS; i--) {
            Message message = messages.get(i);
            String role = message.getMessageType().name().toLowerCase();
            String content = message.getText();
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                content += " [工具调用: " + assistant.getToolCalls().stream()
                        .map(AssistantMessage.ToolCall::name).toList() + "]";
            } else if (message instanceof ToolResponseMessage tools) {
                content = tools.getResponses().stream()
                        .map(r -> r.name() + ": " + limit(r.responseData(), 250))
                        .reduce("", (a, b) -> a + " " + b);
            }
            String line = "[" + role + "] " + limit(content, message instanceof ToolResponseMessage ? 400 : 1_200);
            if (chars + line.length() > MAX_SUMMARY_INPUT_CHARS) break;
            lines.add(0, line);
            chars += line.length();
        }
        return String.join("\n", lines);
    }

    private String generateSummary(String input, OpenAiChatModel model) {
        try {
            var options = model.getOptions().mutate().toolCallbacks(List.of()).maxCompletionTokens(800).build();
            rateLimiter.acquire();
            var response = model.call(new Prompt(List.of(
                    new SystemMessage("压缩对话。保留用户目标、已完成操作、重要路径、待办和失败原因。纯文本，不超过500字。"),
                    new UserMessage(input)), options));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("Context summary request failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private String localSummary(String input) {
        if (input == null) return "";
        // Preserve the most recent progress when a remote summary is unavailable.
        return input.length() <= MAX_SUMMARY_CHARS
                ? input : "[早期详情已省略]\n" + input.substring(input.length() - MAX_SUMMARY_CHARS);
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "\n[后续内容已省略]";
    }
}
