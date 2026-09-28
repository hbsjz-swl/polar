package com.dlchm.dlc.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Coordinates explicit human approval for tools that can mutate the system. */
@Component
public class ApprovalManager {
    private static final Duration MAX_WAIT = Duration.ofMinutes(10);
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public Request request(String sessionId, String toolName, String summary) {
        String id = UUID.randomUUID().toString();
        Request request = new Request(id, sessionId, toolName, summary, Instant.now());
        pending.put(id, new Pending(request));
        return request;
    }

    public Decision await(String requestId, BooleanSupplier cancelled) {
        Pending value = pending.get(requestId);
        if (value == null) return Decision.DENIED;
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (cancelled.getAsBoolean()) {
                    value.future.complete(Decision.CANCELLED);
                    return Decision.CANCELLED;
                }
                try {
                    Decision result = value.future.get(250, TimeUnit.MILLISECONDS);
                    return result == null ? Decision.DENIED : result;
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // Poll so cancellation can be observed without imposing
                    // a short approval deadline.
                }
            }
            value.future.complete(Decision.EXPIRED);
            return Decision.EXPIRED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            value.future.complete(Decision.CANCELLED);
            return Decision.CANCELLED;
        } catch (java.util.concurrent.ExecutionException e) {
            value.future.complete(Decision.DENIED);
            return Decision.DENIED;
        } finally {
            pending.remove(requestId, value);
        }
    }

    public boolean approve(String requestId) {
        Pending value = pending.get(requestId);
        return value != null && value.future.complete(Decision.APPROVED);
    }

    public boolean approve(String requestId, String sessionId) {
        Pending value = pending.get(requestId);
        return value != null && sameSession(value.request.sessionId(), sessionId)
                && value.future.complete(Decision.APPROVED);
    }

    public boolean deny(String requestId) {
        Pending value = pending.get(requestId);
        return value != null && value.future.complete(Decision.DENIED);
    }

    public boolean deny(String requestId, String sessionId) {
        Pending value = pending.get(requestId);
        return value != null && sameSession(value.request.sessionId(), sessionId)
                && value.future.complete(Decision.DENIED);
    }

    public void cancelSession(String sessionId) {
        if (sessionId == null) return;
        pending.values().stream()
                .filter(value -> sessionId.equals(value.request.sessionId()))
                .forEach(value -> value.future.complete(Decision.CANCELLED));
    }

    public List<Request> pending(String sessionId) {
        List<Request> result = new ArrayList<>();
        for (Pending value : pending.values()) {
            if (sessionId == null || sessionId.equals(value.request.sessionId())) result.add(value.request);
        }
        result.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return result;
    }

    private boolean sameSession(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    public record Request(String id, String sessionId, String toolName, String summary, Instant createdAt) {}
    public enum Decision { APPROVED, DENIED, CANCELLED, EXPIRED }

    private static final class Pending {
        private final Request request;
        private final CompletableFuture<Decision> future = new CompletableFuture<>();

        private Pending(Request request) { this.request = request; }
    }
}
