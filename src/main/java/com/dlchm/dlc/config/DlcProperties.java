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
