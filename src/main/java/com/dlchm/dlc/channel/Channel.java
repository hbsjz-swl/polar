package com.dlchm.dlc.channel;

/**
 * Channel 生命周期接口。
 * 由各外部 Channel（如企微）实现，ChannelConfiguration 统一管理启动/停止。
 */
public interface Channel {

    /** Channel 类型标识（如 "wecom"） */
    String type();

    /** 是否启用 */
    boolean isEnabled();

    /** 启动 Channel（连接外部服务等） */
    void start();

    /** 停止 Channel（断开连接等） */
    void stop();
}
