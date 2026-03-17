package com.dlchm.dlc.cli;

import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.agent.StreamEvent;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.stereotype.Component;

/**
 * DLC 终端交互界面。
 * 蒂爱嘉(北京)有限公司
 */
@Component
public class DlcCli {

    private static final String BANNER = """

              ██████╗ ██╗      ██████╗
              ██╔══██╗██║     ██╔════╝
              ██║  ██║██║     ██║
              ██║  ██║██║     ██║
              ██████╔╝███████╗╚██████╗
              ╚═════╝ ╚══════╝ ╚═════╝
              Local AI CorWork Agent
              蒂爱喜(北京)科技有限公司-石家庄AI项目组研发
            """;

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";

    private final CodingAgent agent;
    private final SandboxPathResolver pathResolver;
    private final ToolCallbackProvider toolCallbackProvider;
    private final SessionManager sessionManager;
    private final com.dlchm.dlc.tools.MemoryTool memoryTool;

    public DlcCli(CodingAgent agent, SandboxPathResolver pathResolver,
                  ToolCallbackProvider toolCallbackProvider, SessionManager sessionManager,
                  com.dlchm.dlc.tools.MemoryTool memoryTool) {
        this.agent = agent;
        this.pathResolver = pathResolver;
        this.toolCallbackProvider = toolCallbackProvider;
        this.sessionManager = sessionManager;
        this.memoryTool = memoryTool;
    }

    public void run() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            Session session = sessionManager.getCliSession();

            System.out.println(ANSI_CYAN + BANNER + ANSI_RESET);
            System.out.println(ANSI_DIM + "Workspace: " + pathResolver.getWorkspaceRoot() + ANSI_RESET);
            // Show registered tools
            String toolNames = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                    .map(cb -> cb.getToolDefinition().name())
                    .collect(Collectors.joining(", "));
//            System.out.println(ANSI_DIM + "Tools: " + toolNames + ANSI_RESET);
            try (var files = Files.list(pathResolver.getWorkspaceRoot())) {
                files.filter(p -> p.getFileName().toString().equalsIgnoreCase("agent.md"))
                     .filter(Files::isRegularFile)
                     .findFirst()
                     .ifPresent(p -> System.out.println(
                             ANSI_GREEN + p.getFileName() + ": loaded" + ANSI_RESET));
            } catch (Exception ignored) {}
            System.out.println(ANSI_DIM + "Type your request. /quit to exit, /clear to clear, /forget to clear memory, /config to reconfigure, /install <skill> to install." + ANSI_RESET);
            System.out.println();

            while (true) {
                String input;
                try {
                    input = reader.readLine(ANSI_GREEN + "you> " + ANSI_RESET);
                } catch (UserInterruptException | EndOfFileException e) {
                    break;
                }

                if (input == null || input.isBlank()) continue;
                String trimmed = input.trim();

                if ("/quit".equalsIgnoreCase(trimmed) || "/exit".equalsIgnoreCase(trimmed)) {
                    System.out.println(ANSI_DIM + "Goodbye!" + ANSI_RESET);
                    break;
                }
                if ("/clear".equalsIgnoreCase(trimmed)) {
                    session.clearHistory();
                    System.out.print("\033[H\033[2J");
                    System.out.flush();
                    System.out.println(ANSI_DIM + "History cleared." + ANSI_RESET);
                    continue;
                }
                if ("/config".equalsIgnoreCase(trimmed)) {
                    DlcSetup.reconfigure();
                    agent.reloadConfig();
                    System.out.println(ANSI_GREEN + "配置已更新，立即生效。" + ANSI_RESET);
                    continue;
                }
                if (trimmed.toLowerCase().startsWith("/install ")) {
                    String slug = trimmed.substring(9).trim();
                    if (slug.isBlank()) {
                        System.out.println(ANSI_YELLOW + "用法: /install <技能名称>" + ANSI_RESET);
                    } else {
                        System.out.println(ANSI_DIM + "正在从 ClawHub 安装 " + slug + "..." + ANSI_RESET);
                        System.out.println(SkillInstaller.install(slug));
                    }
                    continue;
                }
                if (trimmed.toLowerCase().startsWith("/uninstall ")) {
                    String slug = trimmed.substring(11).trim();
                    if (slug.isBlank()) {
                        System.out.println(ANSI_YELLOW + "用法: /uninstall <技能名称>" + ANSI_RESET);
                    } else {
                        System.out.println(SkillInstaller.uninstall(slug));
                    }
                    continue;
                }
                if ("/skills".equalsIgnoreCase(trimmed)) {
                    System.out.println(SkillInstaller.listInstalled());
                    continue;
                }
                if ("/forget".equalsIgnoreCase(trimmed)) {
                    System.out.println(ANSI_YELLOW + memoryTool.clearAll() + ANSI_RESET);
                    continue;
                }

                processMessage(session, trimmed);
            }
        } catch (Exception e) {
            System.err.println("Terminal error: " + e.getMessage());
        }
    }

    private void processMessage(Session session, String userMessage) {
        System.out.println();
        System.out.print(ANSI_CYAN + "dlc> " + ANSI_RESET);

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullResponse = new StringBuilder();

        agent.stream(session, userMessage)
                .subscribe(
                        event -> {
                            if (event.type() == StreamEvent.Type.REASONING) {
                                System.out.print(ANSI_DIM + event.data() + ANSI_RESET);
                            } else {
                                System.out.print(event.data());
                                fullResponse.append(event.data());
                            }
                        },
                        error -> {
                            System.out.println();
                            String msg = error.getMessage() != null ? error.getMessage() : "";
                            if (isModelError(msg)) {
                                System.out.println(ANSI_YELLOW + "模型不可用或无访问权限。" + ANSI_RESET);
                                System.out.println(ANSI_DIM + "错误详情: " + msg + ANSI_RESET);
                                System.out.println(ANSI_YELLOW + "请确认模型名称是否正确，输入 /config 重新配置。" + ANSI_RESET);
                            } else if (isAuthError(msg)) {
                                System.out.println(ANSI_YELLOW + "API Key 无效或已过期。" + ANSI_RESET);
                                System.out.println(ANSI_DIM + "错误详情: " + msg + ANSI_RESET);
                                System.out.println(ANSI_YELLOW + "输入 /config 重新配置，或直接输入消息重试。" + ANSI_RESET);
                            } else if (isTransientError(msg)) {
                                System.out.println(ANSI_YELLOW + "服务暂时不可用，请稍后重试。" + ANSI_RESET);
                                System.out.println(ANSI_DIM + "错误详情: " + msg + ANSI_RESET);
                            } else {
                                System.out.println(ANSI_YELLOW + "Error: " + msg + ANSI_RESET);
                            }
                            latch.countDown();
                        },
                        () -> {
                            System.out.println();
                            System.out.println();
                            latch.countDown();
                        }
                );

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isAuthError(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("unauthorized") || lower.contains("401")
                || lower.contains("invalid api key") || lower.contains("authentication")
                || lower.contains("invalid_api_key") || lower.contains("incorrect api key");
    }

    private boolean isModelError(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("model not found") || lower.contains("model_not_found")
                || lower.contains("does not exist") || lower.contains("no such model")
                || lower.contains("unknown model") || lower.contains("not available")
                || lower.contains("access denied") || lower.contains("model access")
                || lower.contains("permission denied");
    }

    private boolean isTransientError(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("503") || lower.contains("502") || lower.contains("429")
                || lower.contains("rate limit") || lower.contains("overloaded")
                || lower.contains("connection refused") || lower.contains("connection reset");
    }
}
