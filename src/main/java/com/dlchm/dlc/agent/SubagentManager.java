package com.dlchm.dlc.agent;

import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Runs bounded, isolated child conversations without sharing parent history. */
@Component
public class SubagentManager {
    private static final int MAX_CONCURRENT = 4;
    private static final int MAX_DEPTH = 1;
    private final ObjectProvider<CodingAgent> agentProvider;
    private final SessionManager sessions;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, runnable -> {
        Thread thread = new Thread(runnable, "polar-subagent");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    public SubagentManager(ObjectProvider<CodingAgent> agentProvider, SessionManager sessions) {
        this.agentProvider = agentProvider;
        this.sessions = sessions;
    }

    public String delegate(String prompt, Integer timeoutSeconds) {
        if (prompt == null || prompt.isBlank()) return "Error: subagent prompt is empty";
        if (prompt.length() > 32_000) return "Error: subagent prompt is too long";
        if (ExecutionContext.subagentDepth() >= MAX_DEPTH) {
            return "Error: nested subagents are disabled at this depth";
        }
        String id = UUID.randomUUID().toString();
        String parentId = ExecutionContext.sessionId();
        Session child = sessions.create("subagent", parentId == null ? "local" : parentId);
        Task task = new Task(id, parentId, child.getId(), prompt, Instant.now());
        tasks.put(id, task);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                ExecutionContext.call(child, ExecutionContext.subagentDepth() + 1,
                        () -> agentProvider.getObject().chat(child, prompt)), executor);
        task.future = future;
        int timeout = timeoutSeconds == null || timeoutSeconds < 1
                ? 300 : Math.min(timeoutSeconds, 600);
        try {
            String answer = future.get(timeout, TimeUnit.SECONDS);
            task.status = "completed";
            task.finishedAt = Instant.now();
            return "Subagent " + id + " completed:\n" + answer;
        } catch (java.util.concurrent.TimeoutException e) {
            task.status = "timed_out";
            future.cancel(true);
            task.finishedAt = Instant.now();
            return "Subagent " + id + " timed out after " + timeout + "s";
        } catch (Exception e) {
            task.status = "failed";
            task.finishedAt = Instant.now();
            return "Subagent " + id + " failed: " + e.getMessage();
        }
    }

    public boolean cancel(String id) {
        Task task = tasks.get(id);
        if (task == null || task.future == null) return false;
        boolean cancelled = task.future.cancel(true);
        if (cancelled) {
            task.status = "cancelled";
            task.finishedAt = Instant.now();
        }
        return cancelled;
    }

    public List<TaskInfo> list(String parentSessionId) {
        List<TaskInfo> result = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (parentSessionId == null || parentSessionId.equals(task.parentSessionId)) {
                result.add(new TaskInfo(task.id, task.parentSessionId, task.childSessionId,
                        task.status, task.createdAt, task.finishedAt));
            }
        }
        result.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return result;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public record TaskInfo(String id, String parentSessionId, String childSessionId, String status,
                           Instant createdAt, Instant finishedAt) {}

    private static final class Task {
        private final String id;
        private final String parentSessionId;
        private final String childSessionId;
        @SuppressWarnings("unused")
        private final String prompt;
        private final Instant createdAt;
        private volatile String status = "running";
        private volatile Instant finishedAt;
        private volatile CompletableFuture<String> future;

        private Task(String id, String parentSessionId, String childSessionId,
                     String prompt, Instant createdAt) {
            this.id = id;
            this.parentSessionId = parentSessionId;
            this.childSessionId = childSessionId;
            this.prompt = prompt;
            this.createdAt = createdAt;
        }
    }
}
