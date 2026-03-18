package com.dlchm.dlc.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话：维护单个对话的消息历史。线程安全。
 */
public class Session {

    private static final int DEFAULT_MAX_MESSAGES = 100;

    private final String id;
    private final String channelType;
    private final String userId;
    private final List<ObjectNode> history = new ArrayList<>();
    private final Instant createdAt;
    private volatile Instant lastActiveAt;

    public Session(String channelType, String userId) {
        this(UUID.randomUUID().toString(), channelType, userId);
    }

    public Session(String id, String channelType, String userId) {
        this.id = id;
        this.channelType = channelType;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getChannelType() {
        return channelType;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    public synchronized List<ObjectNode> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized void addMessage(ObjectNode message) {
        history.add(message.deepCopy());
        trimHistory(DEFAULT_MAX_MESSAGES);
        touch();
    }

    /**
     * 原子批量添加消息，避免逐条 add 时 trimHistory 在中间截断 tool_call/tool 对。
     */
    public synchronized void addMessages(List<ObjectNode> messages) {
        for (ObjectNode msg : messages) {
            history.add(msg.deepCopy());
        }
        trimHistory(DEFAULT_MAX_MESSAGES);
        touch();
    }

    public synchronized void trimHistory(int maxMessages) {
        while (history.size() > maxMessages) {
            history.remove(0);
        }
        // 确保历史不以孤立的 tool result 或带 tool_calls 的 assistant 开头
        // 否则 API 会因缺少配对消息而报错
        while (!history.isEmpty()) {
            ObjectNode first = history.get(0);
            String role = first.path("role").asText("");
            if ("tool".equals(role) || ("assistant".equals(role) && first.has("tool_calls"))) {
                history.remove(0);
            } else {
                break;
            }
        }
    }

    public synchronized void clearHistory() {
        history.clear();
    }
}
