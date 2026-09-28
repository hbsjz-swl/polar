package com.dlchm.dlc;

import com.dlchm.dlc.cli.DlcCli;
import com.dlchm.dlc.cli.DlcSetup;
import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DLC - Local AI Coding Agent
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
        boolean execMode = args.length > 0 && "exec".equalsIgnoreCase(args[0]);
        DlcSetup.ensureConfigured();

        String[] applicationArgs = execMode
                ? java.util.stream.Stream.concat(
                        java.util.Arrays.stream(java.util.Arrays.copyOfRange(args, 1, args.length)),
                        java.util.stream.Stream.of("--spring.main.web-application-type=none",
                                "--dlc.channels.wecom.enabled=false"))
                .toArray(String[]::new) : args;
        ConfigurableApplicationContext context = SpringApplication.run(DlcApplication.class, applicationArgs);

        if (execMode) {
            int exitCode = runExec(context, applicationArgs);
            int closeCode = SpringApplication.exit(context);
            System.exit(exitCode != 0 ? exitCode : closeCode);
            return;
        }

        boolean cliEnabled = java.util.Arrays.stream(args)
                .noneMatch(arg -> "--dlc.cli.enabled=false".equalsIgnoreCase(arg));
        if (!cliEnabled) {
            return;
        }
        // CLI 在独立线程运行，Netty 在主线程保持进程存活
        Thread cliThread = new Thread(() -> {
            DlcCli cli = context.getBean(DlcCli.class);
            cli.run();
            // SDK and scheduler threads may outlive the Spring context. A CLI
            // /quit must release the terminal agent process and its resources.
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }, "dlc-cli");
        cliThread.setDaemon(false);
        cliThread.start();
    }

    /** Headless, scriptable mode. Every stream event is emitted as one JSON line. */
    private static int runExec(ConfigurableApplicationContext context, String[] args) {
        boolean jsonl = false;
        String sessionId = null;
        List<String> promptParts = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--spring.") || arg.startsWith("--dlc.")) continue;
            if ("--jsonl".equals(arg)) {
                jsonl = true;
            } else if ("--session".equals(arg) && i + 1 < args.length) {
                sessionId = args[++i];
            } else if ("--help".equals(arg)) {
                System.out.println("Usage: polar exec [--jsonl] [--session ID] PROMPT");
                return 0;
            } else {
                promptParts.add(arg);
            }
        }
        String prompt = String.join(" ", promptParts).trim();
        if (prompt.isBlank()) {
            try {
                prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                System.err.println("Cannot read prompt from stdin: " + e.getMessage());
                return 2;
            }
        }
        if (prompt.isBlank()) {
            System.err.println("A prompt is required (argument or stdin).");
            return 2;
        }

        CodingAgent agent = context.getBean(CodingAgent.class);
        SessionManager sessions = context.getBean(SessionManager.class);
        Session session = sessionId == null || sessionId.isBlank()
                ? sessions.create("exec", "local")
                : sessions.resume(sessionId, "exec", "local");
        ObjectMapper mapper = new ObjectMapper();
        try {
            agent.stream(session, prompt).doOnNext(event -> {
                try {
                    String line = mapper.writeValueAsString(java.util.Map.of(
                            "type", event.type().name(), "data", event.data(),
                            "sessionId", session.getId()));
                    System.out.println(line);
                } catch (Exception e) {
                    System.out.println("{\"type\":\"ERROR\",\"data\":\"serialization error\"}");
                }
            }).blockLast();
            return 0;
        } catch (Throwable e) {
            if (jsonl) {
                try {
                    System.out.println(mapper.writeValueAsString(java.util.Map.of(
                            "type", "ERROR", "data", String.valueOf(e.getMessage()),
                            "sessionId", session.getId())));
                } catch (Exception ignored) { }
            } else {
                System.err.println("polar exec failed: " + e.getMessage());
            }
            return 1;
        }
    }

}
