package com.dlchm.dlc.agent;

/**
 * 流式事件：LLM 输出的单个 token 或推理片段。
 */
public record StreamEvent(Type type, String data) {
    public enum Type { TOKEN, REASONING, USAGE }
}
