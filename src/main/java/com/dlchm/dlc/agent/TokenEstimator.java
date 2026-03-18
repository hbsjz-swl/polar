package com.dlchm.dlc.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 基于字符数的 token 估算工具。
 * 中文约 1.5 字符/token，英文约 4 字符/token，按 CJK 比例混合计算。
 */
public final class TokenEstimator {

    private static final double CJK_CHARS_PER_TOKEN = 1.5;
    private static final double LATIN_CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {}

    /**
     * 估算单段文本的 token 数。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        return (int) Math.ceil(cjkChars / CJK_CHARS_PER_TOKEN + otherChars / LATIN_CHARS_PER_TOKEN);
    }

    /**
     * 估算 messages 数组的总 token 数。
     * 每条消息额外计 4 token（role、分隔符等开销）。
     */
    public static int estimateMessages(ArrayNode messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;
        for (JsonNode msg : messages) {
            total += 4; // message overhead
            String content = msg.path("content").asText("");
            total += estimateTokens(content);
            // tool_calls arguments
            JsonNode toolCalls = msg.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    total += estimateTokens(tc.path("function").path("name").asText(""));
                    total += estimateTokens(tc.path("function").path("arguments").asText(""));
                }
            }
        }
        return total;
    }

    /**
     * 估算工具定义数组的 token 数。
     */
    public static int estimateToolDefs(ArrayNode toolDefs) {
        if (toolDefs == null || toolDefs.isEmpty()) return 0;
        int total = 0;
        for (JsonNode tool : toolDefs) {
            JsonNode fn = tool.path("function");
            total += estimateTokens(fn.path("name").asText(""));
            total += estimateTokens(fn.path("description").asText(""));
            total += estimateTokens(fn.path("parameters").toString());
        }
        return total;
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
