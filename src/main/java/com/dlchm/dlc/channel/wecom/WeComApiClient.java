package com.dlchm.dlc.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/**
 * 企业微信智能机器人长连接协议消息构建器。
 *
 * 协议参考：@wecom/aibot-node-sdk
 * 连接地址：wss://openws.work.weixin.qq.com
 */
public class WeComApiClient {

    public static final String WS_URL = "wss://openws.work.weixin.qq.com";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String botId;
    private final String secret;

    public WeComApiClient(String botId, String secret) {
        this.botId = botId;
        this.secret = secret;
    }

    /** 构建 aibot_subscribe 认证命令 */
    public String buildSubscribeCommand() throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("cmd", "aibot_subscribe");
        msg.set("headers", mapper.createObjectNode().put("req_id", uuid()));
        ObjectNode body = mapper.createObjectNode();
        body.put("bot_id", botId);
        body.put("secret", secret);
        msg.set("body", body);
        return mapper.writeValueAsString(msg);
    }

    /** 构建 ping 心跳命令 */
    public String buildPingCommand() throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("cmd", "ping");
        msg.set("headers", mapper.createObjectNode().put("req_id", uuid()));
        return mapper.writeValueAsString(msg);
    }

    /**
     * 构建流式回复命令 aibot_respond_msg。
     *
     * @param reqId     原始消息的 req_id（透传）
     * @param streamId  流式回复的唯一 ID（同一条回复用同一个 streamId）
     * @param content   回复内容
     * @param finish    是否结束流式回复
     */
    public String buildStreamReply(String reqId, String streamId, String content, boolean finish) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("cmd", "aibot_respond_msg");
        msg.set("headers", mapper.createObjectNode().put("req_id", reqId));

        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "stream");

        ObjectNode stream = mapper.createObjectNode();
        stream.put("id", streamId);
        stream.put("finish", finish);
        stream.put("content", content);
        body.set("stream", stream);

        msg.set("body", body);
        return mapper.writeValueAsString(msg);
    }

    /**
     * 构建完整文本回复（非流式）。
     */
    public String buildTextReply(String reqId, String content) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("cmd", "aibot_respond_msg");
        msg.set("headers", mapper.createObjectNode().put("req_id", reqId));

        ObjectNode body = mapper.createObjectNode();
        body.put("msgtype", "markdown");
        body.set("markdown", mapper.createObjectNode().put("content", content));

        msg.set("body", body);
        return mapper.writeValueAsString(msg);
    }

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
