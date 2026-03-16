package com.dlchm.dlc.channel.rest;

import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.agent.StreamEvent;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestChannel(CodingAgent agent, SessionManager sessionManager) {
        this.agent = agent;
        this.sessionManager = sessionManager;
    }

    /**
     * 同步聊天：返回完整响应。
     * POST /api/chat
     * Body: { "message": "...", "sessionId": "..." }
     * Response: { "sessionId": "...", "response": "..." }
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");

        Session session = resolveSession(sessionId);

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
    public Flux<String> chatStream(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");

        Session session = resolveSession(sessionId);

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
    public Mono<Map<String, String>> clearSession(@PathVariable String id) {
        Session session = sessionManager.get(id);
        if (session != null) {
            session.clearHistory();
            return Mono.just(Map.of("status", "cleared", "sessionId", id));
        }
        return Mono.just(Map.of("status", "not_found", "sessionId", id));
    }

    private Session resolveSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionManager.getOrCreate(sessionId, "rest", "anonymous");
        }
        return sessionManager.create("rest", "anonymous");
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
