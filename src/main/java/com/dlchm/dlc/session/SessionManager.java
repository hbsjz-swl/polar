package com.dlchm.dlc.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话管理器：管理所有活跃会话，支持自动清理空闲会话。
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_SESSIONS = 64;
    private static final String CLI_SESSION_ID = "cli";

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final MarkdownSessionStore store;

    public SessionManager(MarkdownSessionStore store) {
        this.store = store;
    }

    /**
     * 获取或创建会话。
     */
    public Session getOrCreate(String id, String channelType, String userId) {
        String sessionId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        Session session = sessions.computeIfAbsent(sessionId, k -> {
            Session loaded = new Session(sessionId, channelType, userId);
            loaded.replaceHistory(store.load(sessionId));
            loaded.getTaskState().copyFrom(store.loadTaskState(sessionId));
            return loaded;
        });
        session.touch();
        enforceCapacity();
        return session;
    }

    /**
     * 创建新会话（自动生成 ID）。
     */
    public Session create(String channelType, String userId) {
        Session session = new Session(channelType, userId);
        sessions.put(session.getId(), session);
        enforceCapacity();
        return session;
    }

    /**
     * 获取已有会话。
     */
    public Session get(String id) {
        Session session = sessions.get(id);
        if (session != null) session.touch();
        return session;
    }

    /**
     * 移除会话。
     */
    public Session remove(String id) {
        return sessions.remove(id);
    }

    /** Resume a durable session by id, loading its Markdown transcript if needed. */
    public Session resume(String id, String channelType, String userId) {
        return getOrCreate(id, channelType, userId);
    }

    public void save(Session session) {
        if (session != null) store.save(session);
    }

    public void clear(String id) {
        Session session = get(id);
        if (session != null) {
            session.clearHistory();
            store.save(session);
        }
    }

    /** Fork a durable transcript into a new independent session. */
    public Session fork(String sourceId, String channelType, String userId) {
        Session source = getOrCreate(sourceId, channelType, userId);
        Session copy = create(channelType, userId);
        copy.replaceHistory(source.getHistory());
        copy.getTaskState().copyFrom(source.getTaskState());
        store.save(copy);
        return copy;
    }

    public List<MarkdownSessionStore.SessionFile> listPersistedSessions() {
        return store.list();
    }

    public MarkdownSessionStore getStore() {
        return store;
    }

    /**
     * 获取 CLI 专用会话（单例）。
     */
    public Session getCliSession() {
        return getOrCreate(CLI_SESSION_ID, "cli", "local");
    }

    /**
     * 定时清理空闲超时的会话（每 10 分钟执行一次，不清理 CLI 会话）。
     */
    @Scheduled(fixedDelay = 600_000)
    public void cleanupIdleSessions() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        sessions.entrySet().removeIf(entry -> {
            if (CLI_SESSION_ID.equals(entry.getKey())) return false;
            boolean expired = entry.getValue().getLastActiveAt().isBefore(cutoff);
            if (expired) {
                log.debug("Removing idle session: {}", entry.getKey());
            }
            return expired;
        });
    }

    /**
     * 获取所有活跃会话数量。
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    private void enforceCapacity() {
        while (sessions.size() > MAX_SESSIONS) {
            sessions.entrySet().stream()
                    .filter(e -> !CLI_SESSION_ID.equals(e.getKey()))
                    .min((a, b) -> a.getValue().getLastActiveAt().compareTo(b.getValue().getLastActiveAt()))
                    .ifPresentOrElse(e -> sessions.remove(e.getKey(), e.getValue()), () -> { return; });
            if (sessions.size() <= 1) break;
        }
    }

    /**
     * 获取所有会话的快照。
     */
    public Map<String, Session> getAllSessions() {
        return Map.copyOf(sessions);
    }
}
