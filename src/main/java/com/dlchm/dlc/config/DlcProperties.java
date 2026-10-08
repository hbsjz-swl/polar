package com.dlchm.dlc.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DLC 配置。
 */
@ConfigurationProperties(prefix = "dlc")
public class DlcProperties {

    /** 工作区根目录（默认当前目录） */
    private String workspace = ".";

    /** 权限模式：READ_ONLY / STANDARD / AUTONOMOUS */
    private String permissionMode = "STANDARD";

    /** 禁止访问的路径（相对于工作区） */
    private List<String> blockedPaths = new ArrayList<>(List.of(".env"));

    /** Bash 命令超时（秒） */
    private int bashTimeoutSeconds = 30;

    /** 工具输出最大字符数 */
    private int maxToolOutputChars = 30000;

    /** 上下文窗口 token 数上限（应匹配实际模型的上下文长度） */
    private int contextWindowTokens = 131072;

    /** 是否启用上下文自动压缩 */
    private boolean contextCompressionEnabled = true;

    /** 是否启用视觉能力（截图自动注入多模态消息） */
    private boolean visionEnabled = true;

    /** 单次 API 调用最大 completion tokens（0 表示不设置，使用 API 默认值） */
    private int maxCompletionTokens = 8192;

    /** Subagent 配置 */
    private SubagentConfig subagent = new SubagentConfig();

    public SubagentConfig getSubagent() { return subagent; }
    public void setSubagent(SubagentConfig subagent) { this.subagent = subagent; }

    /**
     * Subagent（子代理委派）配置。
     *
     * <p>子代理跑在独立会话里，共享父代理的工具集但不共享历史。默认开启，
     * 因为它是把「大任务拆成互不依赖的并行块」的唯一手段。</p>
     */
    public static class SubagentConfig {
        /** 是否启用 delegate_task 工具 */
        private boolean enabled = true;
        /** 最大嵌套深度：1 = 只允许父代理派子代理，子代理不能再派 */
        private int maxDepth = 1;
        /** 同时运行的子代理上限 */
        private int maxConcurrent = 4;
        /** 子代理默认超时（秒），实际取 min(入参, maxTimeoutSeconds) */
        private int timeoutSeconds = 300;
        /** 硬上限（秒），防止入参传入超大值把线程池占死 */
        private int maxTimeoutSeconds = 600;
        /** 单次委派 prompt 的最大字符数 */
        private int maxPromptChars = 32000;
        /**
         * 子代理可用的工具名前缀黑名单。
         * browser_* 会与父代理争抢同一个 CDP 标签页，delegate_task 会让嵌套深度失控。
         */
        private List<String> deniedToolPrefixes = new ArrayList<>(
                List.of("browser_", "delegate_task"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
        public void setMaxTimeoutSeconds(int maxTimeoutSeconds) { this.maxTimeoutSeconds = maxTimeoutSeconds; }
        public int getMaxPromptChars() { return maxPromptChars; }
        public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
        public List<String> getDeniedToolPrefixes() { return deniedToolPrefixes; }
        public void setDeniedToolPrefixes(List<String> deniedToolPrefixes) { this.deniedToolPrefixes = deniedToolPrefixes; }
    }

    /** Channels 配置 */
    private ChannelsConfig channels = new ChannelsConfig();

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    public List<String> getBlockedPaths() { return blockedPaths; }
    public void setBlockedPaths(List<String> blockedPaths) { this.blockedPaths = blockedPaths; }
    public int getBashTimeoutSeconds() { return bashTimeoutSeconds; }
    public void setBashTimeoutSeconds(int bashTimeoutSeconds) { this.bashTimeoutSeconds = bashTimeoutSeconds; }
    public int getMaxToolOutputChars() { return maxToolOutputChars; }
    public void setMaxToolOutputChars(int maxToolOutputChars) { this.maxToolOutputChars = maxToolOutputChars; }
    public int getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }
    public boolean isContextCompressionEnabled() { return contextCompressionEnabled; }
    public void setContextCompressionEnabled(boolean contextCompressionEnabled) { this.contextCompressionEnabled = contextCompressionEnabled; }
    public boolean isVisionEnabled() { return visionEnabled; }
    public void setVisionEnabled(boolean visionEnabled) { this.visionEnabled = visionEnabled; }
    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int maxCompletionTokens) { this.maxCompletionTokens = maxCompletionTokens; }
    public ChannelsConfig getChannels() { return channels; }
    public void setChannels(ChannelsConfig channels) { this.channels = channels; }

    // ==================== Nested Config Classes ====================

    public static class ChannelsConfig {
        private WeComConfig wecom = new WeComConfig();

        public WeComConfig getWecom() { return wecom; }
        public void setWecom(WeComConfig wecom) { this.wecom = wecom; }
    }

    /**
     * 企微配置：enabled=true 且配置了 corp-id + secret 时自动连接。
     */
    public static class WeComConfig {
        private boolean enabled = true;
        private String corpId = "";
        private String secret = "";

        public boolean isConfigured() {
            return enabled
                    && corpId != null && !corpId.isBlank()
                    && secret != null && !secret.isBlank();
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCorpId() { return corpId; }
        public void setCorpId(String corpId) { this.corpId = corpId; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }
}
