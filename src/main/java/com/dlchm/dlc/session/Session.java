package com.dlchm.dlc.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * In-process conversation memory. Spring AI message parts are retained intact,
 * including Responses reasoning payloads needed for lossless tool-loop replay.
 */
public class Session {
    private static final int MAX_MESSAGES = 60;
    private static final int MAX_HISTORY_CHARS = 160_000;

    private final String id;
    private final String channelType;
    private final String userId;
    private final List<Message> history = new ArrayList<>();
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
        this.lastActiveAt = createdAt;
    }

    public String getId() { return id; }
    public String getChannelType() { return channelType; }
    public String getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public void touch() { lastActiveAt = Instant.now(); }

    public synchronized List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized void replaceHistory(List<Message> messages) {
        history.clear();
        history.addAll(messages);
        trimHistory();
        touch();
    }

    private void trimHistory() {
        int characters = history.stream().mapToInt(Session::size).sum();
        while (!history.isEmpty() && (history.size() > MAX_MESSAGES || characters > MAX_HISTORY_CHARS)) {
            characters -= size(history.remove(0));
        }
        // A provider conversation cannot start with an orphan tool result or
        // an assistant's function call. Trim through the next user turn.
        while (!history.isEmpty() && !(history.get(0) instanceof UserMessage)) {
            history.remove(0);
        }
    }

    private static int size(Message message) {
        int n = message.getText() == null ? 0 : message.getText().length();
        if (message instanceof AssistantMessage assistant) {
            n += assistant.getToolCalls().stream().mapToInt(c -> c.arguments().length()).sum();
            n += assistant.getReasoning().stream().mapToInt(r ->
                    (r.summary() == null ? 0 : r.summary().length())
                            + (r.payload() == null ? 0 : r.payload().toString().length())).sum();
        } else if (message instanceof ToolResponseMessage tools) {
            n += tools.getResponses().stream().mapToInt(r -> r.responseData().length()).sum();
        }
        return n + 32;
    }

    public synchronized void clearHistory() {
        history.clear();
        touch();
    }
}
