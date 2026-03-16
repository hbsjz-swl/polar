package com.dlchm.dlc;

import com.dlchm.dlc.cli.DlcCli;
import com.dlchm.dlc.cli.DlcSetup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DLC - Local AI Coding Agent
 * 蒂爱嘉(北京)有限公司
 *
 * 启动后同时运行：
 * - CLI 终端交互（独立线程）
 * - HTTP/WebSocket 服务器（Netty，端口 8080）
 * - 企微机器人（如已配置 corp-id + secret，自动连接）
 */
@SpringBootApplication
@EnableScheduling
public class DlcApplication {

    public static void main(String[] args) {
        DlcSetup.ensureConfigured();

        ConfigurableApplicationContext context = SpringApplication.run(DlcApplication.class, args);

        // CLI 在独立线程运行，Netty 在主线程保持进程存活
        Thread cliThread = new Thread(() -> {
            DlcCli cli = context.getBean(DlcCli.class);
            cli.run();
            context.close();
        }, "dlc-cli");
        cliThread.setDaemon(false);
        cliThread.start();
    }
}
