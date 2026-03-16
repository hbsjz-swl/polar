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

    private static final int DEFAULT_MAX_MESSAGES = 40;

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

    public synchronized void trimHistory(int maxMessages) {
        while (history.size() > maxMessages) {
            history.remove(0);
        }
    }

    public synchronized void clearHistory() {
        history.clear();
    }
}
