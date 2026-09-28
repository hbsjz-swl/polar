package com.dlchm.dlc.cli;

import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.agent.ApprovalManager;
import com.dlchm.dlc.agent.SubagentManager;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 终端交互界面。
 */
@Component
public class DlcCli {

    private static final String BANNER = """

              POLAR
              Local AI CorWork Agent
              Viliam
            """;

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final String ANSI_ITALIC = "\u001B[3m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";

    private final CodingAgent agent;
    private final SandboxPathResolver pathResolver;
    private final ToolCallbackProvider toolCallbackProvider;
    private final SessionManager sessionManager;
    private final com.dlchm.dlc.tools.MemoryTool memoryTool;
    private final ApprovalManager approvalManager;
    private final SubagentManager subagentManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DlcCli(CodingAgent agent, SandboxPathResolver pathResolver,
                  ToolCallbackProvider toolCallbackProvider, SessionManager sessionManager,
                  com.dlchm.dlc.tools.MemoryTool memoryTool, ApprovalManager approvalManager,
                  SubagentManager subagentManager) {
        this.agent = agent;
        this.pathResolver = pathResolver;
        this.toolCallbackProvider = toolCallbackProvider;
        this.sessionManager = sessionManager;
        this.memoryTool = memoryTool;
        this.approvalManager = approvalManager;
        this.subagentManager = subagentManager;
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
            System.out.println(ANSI_DIM + "Type your request. /quit to exit, /clear to clear, /sessions to list, /resume <id> to resume, /fork to branch, /forget to clear memory, /config to reconfigure." + ANSI_RESET);
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
                    sessionManager.clear(session.getId());
                    System.out.print("\033[H\033[2J");
                    System.out.flush();
                    System.out.println(ANSI_DIM + "History cleared." + ANSI_RESET);
                    continue;
                }
                if ("/sessions".equalsIgnoreCase(trimmed)) {
                    var persisted = sessionManager.listPersistedSessions();
                    if (persisted.isEmpty()) {
                        System.out.println(ANSI_DIM + "No persisted sessions." + ANSI_RESET);
                    } else {
                        persisted.forEach(item -> System.out.println(item.id() + "  " + item.updatedAt()));
                    }
                    continue;
                }
                if (trimmed.toLowerCase().startsWith("/resume ")) {
                    String id = trimmed.substring(8).trim();
                    if (id.isBlank()) {
                        System.out.println(ANSI_YELLOW + "用法: /resume <session-id>" + ANSI_RESET);
                    } else {
                        session = sessionManager.resume(id, "cli", "local");
                        System.out.println(ANSI_GREEN + "已恢复会话 " + session.getId()
                                + "（" + session.getHistory().size() + " 条消息）" + ANSI_RESET);
                    }
                    continue;
                }
                if ("/fork".equalsIgnoreCase(trimmed)) {
                    session = sessionManager.fork(session.getId(), "cli", "local");
                    System.out.println(ANSI_GREEN + "已创建分支会话 " + session.getId() + ANSI_RESET);
                    continue;
                }
                if ("/status".equalsIgnoreCase(trimmed)) {
                    System.out.println(ANSI_DIM + "session=" + session.getId()
                            + ", messages=" + session.getHistory().size()
                            + ", active=" + sessionManager.getActiveSessionCount()
                            + ", approvals=" + approvalManager.pending(session.getId()).size()
                            + ANSI_RESET);
                    continue;
                }
                if ("/agents".equalsIgnoreCase(trimmed)) {
                    subagentManager.list(session.getId()).forEach(task ->
                            System.out.println(task.id() + "  " + task.status()
                                    + "  child=" + task.childSessionId()));
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

                processMessage(session, trimmed, reader);
            }
        } catch (Exception e) {
            System.err.println("Terminal error: " + e.getMessage());
        }
    }

    private void processMessage(Session session, String userMessage, LineReader reader) {
        System.out.println();
        System.out.print(ANSI_CYAN + "polar> " + ANSI_RESET);

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullResponse = new StringBuilder();

        boolean[] inReasoning = {false};
        long[] thinkStart = {0};
        int[] thinkCount = {0};
        String[] usageInfo = {null};
        String[] spinner = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

        agent.stream(session, userMessage)
                .subscribe(
                        event -> {
                            if (event.type() == StreamEvent.Type.TURN_STARTED
                                    || event.type() == StreamEvent.Type.TURN_COMPLETED) return;
                            if (event.type() == StreamEvent.Type.USAGE) {
                                usageInfo[0] = event.data();
                                return;
                            }
                            if (event.type() == StreamEvent.Type.REASONING) {
                                if (!inReasoning[0]) {
                                    inReasoning[0] = true;
                                    thinkStart[0] = System.currentTimeMillis();
                                    thinkCount[0] = 0;
                                }
                                thinkCount[0]++;
                                long elapsed = (System.currentTimeMillis() - thinkStart[0]) / 1000;
                                String spin = spinner[thinkCount[0] % spinner.length];
                                System.out.print("\r" + ANSI_CYAN + "polar> "
                                        + ANSI_DIM + spin + " thinking... (" + elapsed + "s)"
                                        + ANSI_RESET + "\033[K");
                                System.out.flush();
                            } else if (event.type() == StreamEvent.Type.APPROVAL_REQUIRED) {
                                handleApproval(event.data(), reader);
                            } else if (event.type() == StreamEvent.Type.TOOL_CALL_STARTED) {
                                System.out.println("\n" + ANSI_DIM + "[tool] " + event.data() + ANSI_RESET);
                            } else if (event.type() == StreamEvent.Type.TOOL_OUTPUT
                                    || event.type() == StreamEvent.Type.TOOL_CALL_FINISHED) {
                                // Tool details are available in the structured event stream;
                                // keep the interactive transcript readable.
                            } else if (event.type() == StreamEvent.Type.CANCELLED) {
                                System.out.println("\n" + ANSI_YELLOW + "Turn cancelled." + ANSI_RESET);
                            } else {
                                if (inReasoning[0]) {
                                    inReasoning[0] = false;
                                    System.out.print("\r\033[K");
                                    System.out.print(ANSI_CYAN + "polar> " + ANSI_RESET);
                                }
                                System.out.print(event.data());
                                fullResponse.append(event.data());
                            }
                        },
                        error -> {
                            if (inReasoning[0]) {
                                System.out.print("\r\033[K");
                            }
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
                            if (inReasoning[0]) {
                                System.out.print("\r\033[K");
                                System.out.print(ANSI_CYAN + "polar> " + ANSI_RESET);
                            }
                            System.out.println();
                            if (usageInfo[0] != null) {
                                System.out.println(ANSI_DIM + usageInfo[0] + ANSI_RESET);
                            }
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

    private void handleApproval(String data, LineReader reader) {
        try {
            JsonNode node = objectMapper.readTree(data);
            String id = node.path("approvalId").asText("");
            String summary = node.path("summary").asText("");
            String answer = reader.readLine("\n" + ANSI_YELLOW + "Approve " + summary
                    + "? [y/N] " + ANSI_RESET);
            if (answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"))) {
                approvalManager.approve(id);
            } else {
                approvalManager.deny(id);
            }
        } catch (Exception e) {
            System.out.println(ANSI_YELLOW + "Approval input failed: " + e.getMessage() + ANSI_RESET);
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
