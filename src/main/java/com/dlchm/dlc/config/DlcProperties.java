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
    public ChannelsConfig getChannels() { return channels; }
    public void setChannels(ChannelsConfig channels) { this.channels = channels; }

    // ==================== Nested Config Classes ====================

    public static class ChannelsConfig {
        private WeComConfig wecom = new WeComConfig();

        public WeComConfig getWecom() { return wecom; }
        public void setWecom(WeComConfig wecom) { this.wecom = wecom; }
    }

    /**
     * 企微配置：配置了 corp-id + secret 就自动连接，无需 enabled 开关。
     */
    public static class WeComConfig {
        private String corpId = "";
        private String secret = "";

        public boolean isConfigured() {
            return corpId != null && !corpId.isBlank()
                    && secret != null && !secret.isBlank();
        }

        public String getCorpId() { return corpId; }
        public void setCorpId(String corpId) { this.corpId = corpId; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }
}
