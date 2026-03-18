package com.dlchm.dlc.agent;

/**
 * 单轮对话的 token 用量跟踪。
 * 累加每次 API 迭代的 prompt/completion/total tokens。
 */
public class TokenUsage {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    public void add(int prompt, int completion, int total) {
        this.promptTokens += prompt;
        this.completionTokens += completion;
        this.totalTokens += total;
    }

    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return totalTokens; }

    public boolean hasData() {
        return totalTokens > 0;
    }

    @Override
    public String toString() {
        return "tokens: " + totalTokens + " (prompt=" + promptTokens + ", completion=" + completionTokens + ")";
    }
}
