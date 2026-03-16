package com.dlchm.dlc.channel.wecom;

import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.agent.StreamEvent;
import com.dlchm.dlc.channel.Channel;
import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * 企业微信智能机器人 Channel（WebSocket 长连接模式）。
 *
 * 协议流程：
 * 1. 连接 wss://openws.work.weixin.qq.com
 * 2. 发送 aibot_subscribe 命令认证（bot_id + secret）
 * 3. 收到 aibot_msg_callback 时处理用户消息
 * 4. 通过 aibot_respond_msg 流式回复
 * 5. 30 秒心跳保活，断线自动重连
 */
@Component
public class WeComChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeComChannel.class);
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int STREAM_THROTTLE_MS = 800; // 流式节流间隔，避免 SDK 队列溢出

    private final CodingAgent agent;
    private final SessionManager sessionManager;
    private final DlcProperties.WeComConfig config;
    private final WeComApiClient apiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, Session> userSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "wecom-channel");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatTask;

    public WeComChannel(CodingAgent agent, SessionManager sessionManager, DlcProperties props) {
        this.agent = agent;
        this.sessionManager = sessionManager;
        this.config = props.getChannels().getWecom();
        this.apiClient = new WeComApiClient(config.getCorpId(), config.getSecret());
    }

    @Override
    public String type() {
        return "wecom";
    }

    @Override
    public boolean isEnabled() {
        return config.isConfigured();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        log.info("WeCom channel starting (botId={}...)",
                config.getCorpId().substring(0, Math.min(8, config.getCorpId().length())));
        connect();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("WeCom channel stopping...");
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        scheduler.shutdownNow();
    }

    private void connect() {
        scheduler.execute(() -> {
            try {
                log.info("Connecting to WeCom WebSocket: {}", WeComApiClient.WS_URL);
                HttpClient client = HttpClient.newHttpClient();
                webSocket = client.newWebSocketBuilder()
                        .buildAsync(URI.create(WeComApiClient.WS_URL), new WsListener())
                        .join();

                // 发送认证命令
                String subscribeCmd = apiClient.buildSubscribeCommand();
                webSocket.sendText(subscribeCmd, true);
                log.info("WeCom subscribe command sent");

                // 启动心跳
                if (heartbeatTask != null) heartbeatTask.cancel(false);
                heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                        HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

            } catch (Exception e) {
                log.error("Failed to connect WeCom WebSocket: {}", e.getMessage());
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (running.get()) {
            log.info("WeCom reconnecting in {}s...", RECONNECT_DELAY_SECONDS);
            scheduler.schedule(this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void sendHeartbeat() {
        try {
            if (webSocket != null) {
                webSocket.sendText(apiClient.buildPingCommand(), true);
            }
        } catch (Exception e) {
            log.warn("WeCom heartbeat failed: {}", e.getMessage());
        }
    }

    // ==================== 消息处理 ====================

    private void handleFrame(String text) {
        try {
            JsonNode frame = objectMapper.readTree(text);
            String cmd = frame.path("cmd").asText("");

            switch (cmd) {
                case "aibot_subscribe" -> handleSubscribeResponse(frame);
                case "aibot_msg_callback" -> handleMsgCallback(frame);
                case "aibot_event_callback" -> handleEventCallback(frame);
                case "ping" -> {} // pong 响应，忽略
                case "aibot_respond_msg" -> {} // 回复确认，忽略
                default -> log.debug("WeCom unknown cmd: {}", cmd);
            }
        } catch (Exception e) {
            log.error("WeCom frame parse error: {}", e.getMessage());
        }
    }

    private void handleSubscribeResponse(JsonNode frame) {
        int errcode = frame.path("body").path("errcode").asInt(-1);
        if (errcode == 0) {
            log.info("WeCom authenticated successfully");
        } else {
            String errmsg = frame.path("body").path("errmsg").asText("unknown");
            log.error("WeCom authentication failed: {} ({})", errmsg, errcode);
        }
    }

    private void handleEventCallback(JsonNode frame) {
        JsonNode body = frame.path("body");
        JsonNode event = body.path("event");
        String eventType = event.path("event_type").asText("");
        log.debug("WeCom event: {}", eventType);
    }

    private void handleMsgCallback(JsonNode frame) {
        JsonNode body = frame.path("body");
        String reqId = frame.path("headers").path("req_id").asText("");
        String msgType = body.path("msgtype").asText("");

        // 提取用户文本
        String content;
        if ("text".equals(msgType)) {
            content = body.path("text").path("content").asText("");
        } else if ("voice".equals(msgType)) {
            // 语音消息已转文字
            content = body.path("voice").path("content").asText("");
        } else if ("mixed".equals(msgType)) {
            // 图文混排：提取文本部分
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : body.path("mixed").path("msg_item")) {
                if ("text".equals(item.path("msgtype").asText())) {
                    sb.append(item.path("text").path("content").asText());
                }
            }
            content = sb.toString();
        } else {
            log.debug("WeCom unsupported msgtype: {}", msgType);
            return;
        }

        if (content.isBlank()) return;

        // 去掉 @机器人 的前缀
        content = content.replaceFirst("^@\\S+\\s*", "").trim();
        if (content.isBlank()) return;

        String userId = body.path("from").path("userid").asText("unknown");
        String chatId = body.path("chatid").asText("");
        String chatType = body.path("chattype").asText("single");

        log.info("WeCom msg from {} ({}): {}", userId, chatType,
                content.length() > 50 ? content.substring(0, 50) + "..." : content);

        // 每个用户一个独立会话
        Session session = userSessions.computeIfAbsent(userId,
                k -> sessionManager.create("wecom", userId));

        // 取消该用户正在进行的上一个流式回复
        Disposable prev = activeStreams.remove(userId);
        if (prev != null && !prev.isDisposed()) {
            log.info("WeCom cancelling previous stream for user {}", userId);
            prev.dispose();
        }

        // 异步处理并流式回复
        processAndReply(session, reqId, content, userId);
    }

    private void processAndReply(Session session, String reqId, String userMessage, String userId) {
        String streamId = WeComApiClient.uuid();
        StringBuilder accumulated = new StringBuilder();

        Disposable disposable = agent.stream(session, userMessage)
                .filter(event -> event.type() == StreamEvent.Type.TOKEN)
                .map(StreamEvent::data)
                .buffer(java.time.Duration.ofMillis(STREAM_THROTTLE_MS))
                .filter(batch -> !batch.isEmpty())
                .map(batch -> String.join("", batch))
                .subscribe(
                        chunk -> {
                            accumulated.append(chunk);
                            sendStreamReply(reqId, streamId, accumulated.toString(), false);
                        },
                        error -> {
                            activeStreams.remove(userId);
                            log.error("WeCom agent error: {}", error.getMessage());
                            accumulated.append("\n\n[Error: ").append(error.getMessage()).append("]");
                            sendStreamReply(reqId, streamId, accumulated.toString(), true);
                        },
                        () -> {
                            activeStreams.remove(userId);
                            sendStreamReply(reqId, streamId, accumulated.toString(), true);
                        }
                );

        activeStreams.put(userId, disposable);
    }

    private void sendStreamReply(String reqId, String streamId, String content, boolean finish) {
        try {
            if (webSocket != null) {
                String reply = apiClient.buildStreamReply(reqId, streamId, content, finish);
                webSocket.sendText(reply, true);
            }
        } catch (Exception e) {
            log.error("WeCom reply failed: {}", e.getMessage());
        }
    }

    // ==================== WebSocket Listener ====================

    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket ws,
                                                                CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handleFrame(text);
            }
            ws.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws,
                                                                 int statusCode, String reason) {
            log.warn("WeCom WebSocket closed: {} {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("WeCom WebSocket error: {}", error.getMessage());
            scheduleReconnect();
        }
    }
}
