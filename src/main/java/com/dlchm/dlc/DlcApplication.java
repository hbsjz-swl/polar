package com.dlchm.dlc;

import com.dlchm.dlc.cli.DlcCli;
import com.dlchm.dlc.cli.DlcSetup;
import java.io.IOException;
import java.net.ServerSocket;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DLC - Local AI Coding Agent
 * 蒂爱喜(北京)科技有限公司
 *
 * 启动后同时运行：
 * - CLI 终端交互（独立线程）
 * - HTTP/WebSocket 服务器（Netty）
 * - 企微机器人（如已配置 botId + secret，自动连接）
 */
@SpringBootApplication
@EnableScheduling
public class DlcApplication {

    public static void main(String[] args) {
        DlcSetup.ensureConfigured();

        // 启动前先清理占用端口的旧进程
        killProcessOnPort(Integer.parseInt(System.getProperty("server.port", "19869")));

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

    /**
     * 检测端口是否被占用，如果是则杀掉占用进程。
     */
    private static void killProcessOnPort(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            // 端口可用，无需处理
        } catch (IOException e) {
            // 端口被占用，查找并杀掉进程
//            System.err.println("Port " + port + " is in use, killing old process...");
            try {
                Process proc = Runtime.getRuntime().exec(new String[]{
                        "/bin/sh", "-c", "lsof -ti:" + port + " | xargs kill -9 2>/dev/null"
                });
                proc.waitFor();
                // 等待端口释放
                Thread.sleep(1000);
            } catch (Exception ex) {
                System.err.println("Failed to kill process on port " + port + ": " + ex.getMessage());
            }
        }
    }
}
