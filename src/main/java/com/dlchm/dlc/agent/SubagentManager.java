package com.dlchm.dlc.agent;

import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs bounded, isolated child conversations without sharing parent history.
 *
 * <p>Every knob lives in {@code dlc.subagent.*} so the feature can be turned off
 * or re-tuned without a rebuild. The limits that used to be constants here are
 * now the reason delegation cannot take the process down: a fixed thread pool
 * plus a fair semaphore bounds concurrency even when the parent fires several
 * delegations in one turn, and the depth guard is re-checked here rather than
 * trusted from the caller.</p>
 */
@Component
public class SubagentManager {
    /** Completed task metadata is capped so a long-lived process cannot leak. */
    private static final int MAX_TRACKED_TASKS = 200;
    /**
     * Hard ceiling on worker threads, independent of the configured limit.
     * The configured {@code max-concurrent} is enforced separately and can be
     * lowered or raised at runtime; this only bounds what a single start may
     * allocate.
     */
    private static final int HARD_MAX_CONCURRENT = 16;
    private final ObjectProvider<CodingAgent> agentProvider;
    private final SessionManager sessions;
    private final DlcProperties properties;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    /** Delegations currently executing; guarded by {@code this} for the limit check. */
    private int inFlight;

    public SubagentManager(ObjectProvider<CodingAgent> agentProvider, SessionManager sessions,
                           DlcProperties properties) {
        this.agentProvider = agentProvider;
        this.sessions = sessions;
        this.properties = properties;
        // Sized to the hard ceiling, not to the configured limit: a fixed pool
        // cannot grow, so sizing it to a value that `/config` can raise later
        // would silently cap the new setting. Idle threads cost nothing here
        // because the pool only materialises a thread when a task arrives.
        this.executor = Executors.newFixedThreadPool(HARD_MAX_CONCURRENT, runnable -> {
            Thread thread = new Thread(runnable, "dlc-subagent");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean isEnabled() {
        return properties.getSubagent().isEnabled();
    }

    public String delegate(String prompt, Integer timeoutSeconds) {
        DlcProperties.SubagentConfig config = properties.getSubagent();
        if (!config.isEnabled()) {
            return "Error: subagent delegation is disabled (dlc.subagent.enabled=false). "
                    + "Do the work yourself in this session.";
        }
        if (prompt == null || prompt.isBlank()) return "Error: subagent prompt is empty";
        int maxPrompt = Math.max(1, config.getMaxPromptChars());
        if (prompt.length() > maxPrompt) {
            return "Error: subagent prompt is too long (" + prompt.length()
                    + " chars, limit " + maxPrompt + "). Split the task or pass a shorter brief.";
        }
        int depth = ExecutionContext.subagentDepth();
        int maxDepth = Math.max(0, config.getMaxDepth());
        if (depth >= maxDepth) {
            return "Error: nested subagents are disabled at depth " + depth
                    + " (max-depth=" + maxDepth + "). Complete this task yourself.";
        }
        // Fair permit acquisition with a real timeout: without it a burst of
        // delegations would park every caller thread on a semaphore that the
        // single shared AgentLoop can never drain. The limit is read per call
        // so lowering it in `/config` throttles the next delegation instead of
        // only taking effect after a restart.
        int timeout = resolveTimeout(timeoutSeconds, config);
        int limit = Math.max(1, config.getMaxConcurrent());
        if (!acquireSlot(limit, timeout)) {
            return "Error: all " + limit + " subagent slots are busy. Wait for one to finish, "
                    + "then retry. Do not retry immediately in a loop.";
        }
        String id = UUID.randomUUID().toString();
        String parentId = ExecutionContext.sessionId();
        int childDepth = depth + 1;
        Session child;
        try {
            child = sessions.create("subagent", parentId == null ? "local" : parentId);
        } catch (RuntimeException e) {
            releaseSlot();
            return "Error: cannot create subagent session: " + e.getMessage();
        }
        Task task = new Task(id, parentId, child.getId(), prompt, Instant.now(), childDepth);
        tasks.put(id, task);
        prune();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            // The depth is passed explicitly: chat() hops to another thread, so
            // the thread-local would read 0 and the child's own children would
            // never be blocked.
            return agentProvider.getObject().chat(child, prompt, childDepth);
        }, executor);
        task.future = future;
        try {
            String answer = future.get(timeout, TimeUnit.SECONDS);
            task.status = "completed";
            task.finishedAt = Instant.now();
            return "Subagent " + id + " completed:\n" + answer;
        } catch (java.util.concurrent.TimeoutException e) {
            task.status = "timed_out";
            future.cancel(true);
            task.finishedAt = Instant.now();
            return "Subagent " + id + " timed out after " + timeout + "s. "
                    + "Do not re-delegate the same task unchanged; narrow it or use your own tools.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.status = "cancelled";
            future.cancel(true);
            task.finishedAt = Instant.now();
            return "Subagent " + id + " was interrupted.";
        } catch (Exception e) {
            task.status = "failed";
            task.finishedAt = Instant.now();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return "Subagent " + id + " failed: " + cause.getMessage();
        } finally {
            releaseSlot();
        }
    }

    /**
     * Waits for a free slot under the live {@code max-concurrent} limit.
     *
     * <p>A plain semaphore sized at construction could not honour a limit
     * changed through {@code /config}, and the worker pool is deliberately
     * larger than any limit so the ceiling stays a policy rather than a
     * resource cap. The monitor is held only around the counter, so the wait
     * itself never blocks a delegation that is already running.</p>
     */
    private synchronized boolean acquireSlot(int limit, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (inFlight >= limit) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                // Wait in slices so a limit raised at runtime is picked up even
                // by a caller that is already parked here.
                long slice = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250));
                TimeUnit.NANOSECONDS.timedWait(this, slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        inFlight++;
        return true;
    }

    private synchronized void releaseSlot() {
        if (inFlight > 0) inFlight--;
        notifyAll();
    }

    private static int resolveTimeout(Integer requested, DlcProperties.SubagentConfig config) {
        int fallback = Math.max(1, config.getTimeoutSeconds());
        int ceiling = Math.max(1, config.getMaxTimeoutSeconds());
        int value = requested == null || requested < 1 ? fallback : Math.min(requested, ceiling);
        return Math.max(1, Math.min(value, ceiling));
    }

    /** Drops the oldest finished task metadata once the table grows too large. */
    private void prune() {
        if (tasks.size() <= MAX_TRACKED_TASKS) return;
        List<Task> finished = new ArrayList<>(tasks.values()).stream()
                .filter(t -> t.status != "running" && t.finishedAt != null)
                .sorted((a, b) -> a.finishedAt.compareTo(b.finishedAt))
                .limit(tasks.size() - MAX_TRACKED_TASKS)
                .toList();
        for (Task task : finished) tasks.remove(task.id, task);
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
                        task.status, task.depth, task.createdAt, task.finishedAt));
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
                           int depth, Instant createdAt, Instant finishedAt) {}

    private static final class Task {
        private final String id;
        private final String parentSessionId;
        private final String childSessionId;
        @SuppressWarnings("unused")
        private final String prompt;
        private final int depth;
        private final Instant createdAt;
        private volatile String status = "running";
        private volatile Instant finishedAt;
        private volatile CompletableFuture<String> future;

        private Task(String id, String parentSessionId, String childSessionId,
                     String prompt, Instant createdAt, int depth) {
            this.id = id;
            this.parentSessionId = parentSessionId;
            this.childSessionId = childSessionId;
            this.prompt = prompt;
            this.createdAt = createdAt;
            this.depth = depth;
        }
    }
}
