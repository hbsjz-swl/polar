package com.dlchm.dlc.channel.websocket;

import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.agent.StreamEvent;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * WebSocket 消息处理器：每个连接创建独立 Session，支持双向实时通信。
 * 入站协议：{"message": "..."}
 * 出站协议：{"type": "TOKEN|REASONING", "data": "..."}
 */
@Component
public class AgentWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private final CodingAgent agent;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentWebSocketHandler(CodingAgent agent, SessionManager sessionManager) {
        this.agent = agent;
        this.sessionManager = sessionManager;
    }

    @Override
    public Mono<Void> handle(WebSocketSession wsSession) {
        Session session = sessionManager.create("websocket", wsSession.getId());
        log.debug("WebSocket connected: session={}", session.getId());

        // Send session ID as first message
        String sessionMsg = toJson("SESSION", session.getId());

        Sinks.Many<String> outSink = Sinks.many().unicast().onBackpressureBuffer();

        // Emit session ID immediately
        outSink.tryEmitNext(sessionMsg);

        // Process inbound messages
        Mono<Void> inbound = wsSession.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(text -> {
                    String message = extractMessage(text);
                    if (message == null || message.isBlank()) {
                        return Mono.empty();
                    }
                    return agent.stream(session, message)
                            .doOnNext(event -> {
                                String json = toJson(event.type().name(), event.data());
                                outSink.tryEmitNext(json);
                            })
                            .doOnComplete(() -> {
                                outSink.tryEmitNext(toJson("DONE", ""));
                            })
                            .doOnError(e -> {
                                outSink.tryEmitNext(toJson("ERROR", e.getMessage()));
                            })
                            .then();
                }, 1) // process one message at a time (concurrency=1)
                .then()
                .doFinally(signal -> {
                    outSink.tryEmitComplete();
                    sessionManager.remove(session.getId());
                    log.debug("WebSocket disconnected: session={}", session.getId());
                });

        // Send outbound messages
        Flux<WebSocketMessage> outbound = outSink.asFlux()
                .map(wsSession::textMessage);

        return Mono.zip(inbound, wsSession.send(outbound)).then();
    }

    private String extractMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText(null);
        } catch (Exception e) {
            return json; // Treat as plain text message
        }
    }

    private String toJson(String type, String data) {
        try {
            return objectMapper.writeValueAsString(
                    objectMapper.createObjectNode()
                            .put("type", type)
                            .put("data", data != null ? data : "")
            );
        } catch (Exception e) {
            return "{\"type\":\"ERROR\",\"data\":\"serialization error\"}";
        }
    }
}
