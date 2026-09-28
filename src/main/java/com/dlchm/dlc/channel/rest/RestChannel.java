package com.dlchm.dlc.channel.rest;

import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.agent.ApprovalManager;
import com.dlchm.dlc.agent.SubagentManager;
import com.dlchm.dlc.agent.StreamEvent;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST Channel：HTTP JSON + SSE 流式接口。
 */
@RestController
@RequestMapping("/api")
public class RestChannel {

    private final CodingAgent agent;
    private final SessionManager sessionManager;
    private final ApprovalManager approvalManager;
    private final SubagentManager subagentManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestChannel(CodingAgent agent, SessionManager sessionManager, ApprovalManager approvalManager,
                       SubagentManager subagentManager) {
        this.agent = agent;
        this.sessionManager = sessionManager;
        this.approvalManager = approvalManager;
        this.subagentManager = subagentManager;
    }

    /**
     * 同步聊天：返回完整响应。
     * POST /api/chat
     * Body: { "message": "...", "sessionId": "..." }
     * Response: { "sessionId": "...", "response": "..." }
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> chat(@RequestBody Map<String, String> request,
                                          @RequestHeader(value = "X-Polar-User", required = false) String headerUser) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");

        Session session = resolveSession(sessionId, owner(request, headerUser));

        return agent.stream(session, message)
                .filter(event -> event.type() == StreamEvent.Type.TOKEN)
                .map(StreamEvent::data)
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(sb -> Map.of(
                        "sessionId", session.getId(),
                        "response", sb.toString()
                ));
    }

    /**
     * 流式聊天：SSE 逐 token 返回。
     * POST /api/chat/stream
     * Body: { "message": "...", "sessionId": "..." }
     * SSE events: { "type": "TOKEN|REASONING", "data": "..." }
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody Map<String, String> request,
                                   @RequestHeader(value = "X-Polar-User", required = false) String headerUser) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");

        Session session = resolveSession(sessionId, owner(request, headerUser));

        // Prepend session ID as first SSE event
        Flux<String> sessionEvent = Flux.just(toJson(Map.of(
                "type", "SESSION",
                "data", session.getId()
        )));

        Flux<String> chatEvents = agent.stream(session, message)
                .map(event -> toJson(Map.of(
                        "type", event.type().name(),
                        "data", event.data()
                )));

        return Flux.concat(sessionEvent, chatEvents);
    }

    /**
     * 清空会话历史。
     * POST /api/session/{id}/clear
     */
    @PostMapping("/session/{id}/clear")
    public Mono<Map<String, String>> clearSession(@PathVariable String id,
                                                  @RequestHeader(value = "X-Polar-User", required = false) String owner) {
        Session session = sessionManager.get(id);
        if (session != null) {
            assertOwner(session, owner);
            sessionManager.clear(id);
            return Mono.just(Map.of("status", "cleared", "sessionId", id));
        }
        return Mono.just(Map.of("status", "not_found", "sessionId", id));
    }

    /** List durable local Markdown sessions. */
    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        return Map.of("sessions", sessionManager.listPersistedSessions().stream()
                .map(file -> Map.of("id", file.id(), "updatedAt", file.updatedAt()))
                .toList());
    }

    /** Load a durable session into memory so the next turn can continue it. */
    @PostMapping("/session/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id,
                                      @RequestHeader(value = "X-Polar-User", required = false) String owner) {
        Session session = sessionManager.resume(id, "rest", normalizeOwner(owner));
        assertOwner(session, owner);
        return Map.of("sessionId", session.getId(), "messages", session.getHistory().size());
    }

    @PostMapping("/session/{id}/fork")
    public Map<String, String> fork(@PathVariable String id,
                                    @RequestHeader(value = "X-Polar-User", required = false) String owner) {
        Session source = sessionManager.get(id);
        if (source != null) assertOwner(source, owner);
        Session session = sessionManager.fork(id, "rest", normalizeOwner(owner));
        return Map.of("sessionId", session.getId(), "sourceSessionId", id);
    }

    /** Resolve a pending approval from a REST/SSE client. */
    @PostMapping("/approval/{approvalId}")
    public Map<String, Object> approval(@PathVariable String approvalId,
                                        @RequestBody(required = false) Map<String, Object> request) {
        boolean approved = request != null && Boolean.TRUE.equals(request.get("approved"));
        boolean changed = approved ? approvalManager.approve(approvalId) : approvalManager.deny(approvalId);
        return Map.of("approvalId", approvalId, "approved", approved, "accepted", changed);
    }

    @PostMapping("/session/{sessionId}/approval/{approvalId}")
    public Map<String, Object> sessionApproval(@PathVariable String sessionId,
                                               @PathVariable String approvalId,
                                               @RequestBody(required = false) Map<String, Object> request) {
        boolean approved = request != null && Boolean.TRUE.equals(request.get("approved"));
        boolean changed = approved ? approvalManager.approve(approvalId, sessionId)
                : approvalManager.deny(approvalId, sessionId);
        return Map.of("approvalId", approvalId, "sessionId", sessionId,
                "approved", approved, "accepted", changed);
    }

    @GetMapping("/session/{id}/approvals")
    public Map<String, Object> pendingApprovals(@PathVariable String id) {
        return Map.of("approvals", approvalManager.pending(id));
    }

    @GetMapping("/session/{id}/agents")
    public Map<String, Object> subagents(@PathVariable String id) {
        return Map.of("tasks", subagentManager.list(id));
    }

    @PostMapping("/agent/{taskId}/cancel")
    public Map<String, Object> cancelSubagent(@PathVariable String taskId) {
        return Map.of("taskId", taskId, "cancelled", subagentManager.cancel(taskId));
    }

    private Session resolveSession(String sessionId, String owner) {
        if (sessionId != null && !sessionId.isBlank()) {
            Session existing = sessionManager.get(sessionId);
            if (existing != null) assertOwner(existing, owner);
            return sessionManager.getOrCreate(sessionId, "rest", owner);
        }
        return sessionManager.create("rest", owner);
    }

    private String owner(Map<String, String> request, String headerUser) {
        String requestUser = request.get("userId");
        return normalizeOwner(requestUser == null ? headerUser : requestUser);
    }

    private String normalizeOwner(String owner) {
        return owner == null || owner.isBlank() ? "anonymous" : owner.trim();
    }

    private void assertOwner(Session session, String owner) {
        String normalized = normalizeOwner(owner);
        if (!"anonymous".equals(session.getUserId()) && !session.getUserId().equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session belongs to another user");
        }
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
