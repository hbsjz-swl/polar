package com.dlchm.dlc.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
    private static final Duration IDLE_TIMEOUT = Duration.ofHours(1);
    private static final String CLI_SESSION_ID = "cli";

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话。
     */
    public Session getOrCreate(String id, String channelType, String userId) {
        return sessions.computeIfAbsent(id, k -> new Session(id, channelType, userId));
    }

    /**
     * 创建新会话（自动生成 ID）。
     */
    public Session create(String channelType, String userId) {
        Session session = new Session(channelType, userId);
        sessions.put(session.getId(), session);
        return session;
    }

    /**
     * 获取已有会话。
     */
    public Session get(String id) {
        return sessions.get(id);
    }

    /**
     * 移除会话。
     */
    public Session remove(String id) {
        return sessions.remove(id);
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

    /**
     * 获取所有会话的快照。
     */
    public Map<String, Session> getAllSessions() {
        return Map.copyOf(sessions);
    }
}
