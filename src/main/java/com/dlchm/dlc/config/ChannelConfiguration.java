package com.dlchm.dlc.config;

import com.dlchm.dlc.channel.Channel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Channel 生命周期管理：扫描所有 Channel bean，启动已启用的 channel。
 */
@Component
public class ChannelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfiguration.class);

    private final List<Channel> channels;

    public ChannelConfiguration(List<Channel> channels) {
        this.channels = channels;
    }

    @PostConstruct
    public void startChannels() {
        if (channels.isEmpty()) {
            log.debug("No external channels configured");
            return;
        }

        for (Channel channel : channels) {
            if (channel.isEnabled()) {
                log.info("Starting channel: {}", channel.type());
                try {
                    channel.start();
                    log.info("Channel started: {}", channel.type());
                } catch (Exception e) {
                    log.error("Failed to start channel {}: {}", channel.type(), e.getMessage());
                }
            } else {
                log.debug("Channel disabled: {}", channel.type());
            }
        }
    }

    @PreDestroy
    public void stopChannels() {
        for (Channel channel : channels) {
            if (channel.isEnabled()) {
                try {
                    channel.stop();
                    log.info("Channel stopped: {}", channel.type());
                } catch (Exception e) {
                    log.error("Failed to stop channel {}: {}", channel.type(), e.getMessage());
                }
            }
        }
    }
}
