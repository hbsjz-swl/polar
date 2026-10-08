package com.dlchm.dlc.cli;

import com.dlchm.dlc.agent.CodingAgent;
import com.dlchm.dlc.agent.ApprovalManager;
import com.dlchm.dlc.agent.ExecutionContext;
import com.dlchm.dlc.agent.SubagentManager;
import com.dlchm.dlc.agent.StreamEvent;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.session.Session;
import com.dlchm.dlc.session.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
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

    // Empty strings when the terminal cannot render escapes, so every call site
    // stays a plain concatenation instead of guarding each print.
    private static final String ANSI_RESET = AnsiSupport.ansi("0m");
    private static final String ANSI_CYAN = AnsiSupport.ansi("36m");
    private static final String ANSI_DIM = AnsiSupport.ansi("2m");
    private static final String ANSI_ITALIC = AnsiSupport.ansi("3m");
    private static final String ANSI_GREEN = AnsiSupport.ansi("32m");
    private static final String ANSI_YELLOW = AnsiSupport.ansi("33m");

    private static String ansi(String code) {
        return AnsiSupport.ansi(code);
    }

    private final CodingAgent agent;
    private final SandboxPathResolver pathResolver;
    private final ToolCallbackProvider toolCallbackProvider;
    private final SessionManager sessionManager;
    private final com.dlchm.dlc.tools.MemoryTool memoryTool;
    private final ApprovalManager approvalManager;
    private final SubagentManager subagentManager;
    private final com.dlchm.dlc.config.DlcProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Cap a console line without dropping the end of it.
     *
     * <p>A Playwright call log is chronological and puts its verdict last, so a
     * head-only cut is what made a captcha interception print as a bare timeout: the
     * operator saw no cause at all. The marker keeps it obvious that a cut happened.</p>
     */
    static String clipDetail(String detail, int max) {
        if (detail == null || detail.length() <= max) return detail;
        String marker = "\n…[中间省略]…\n";
        int tail = Math.min(200, max / 3);
        // The marker is part of the budget, not an extra: overshooting it is how the
        // original version quietly stopped bounding the line at all.
        int head = Math.max(0, max - tail - marker.length());
        return detail.substring(0, head) + marker + detail.substring(detail.length() - tail);
    }

    private void printToolOutcome(String eventData) {
        try {
            JsonNode event = objectMapper.readTree(eventData);
            String name = event.path("name").asText();
            String result = event.path("result").asText();
            boolean failed = com.dlchm.dlc.agent.ToolResultStatus.failed(result);
            if (!failed && !name.startsWith("browser_")) return;
            String detail = result;
            try {
                JsonNode output = objectMapper.readTree(result);
                if (failed) detail = output.path("error").asText(output.path("observation_error").asText(result));
                else {
                    JsonNode page = output.has("final") ? output.path("final") : output;
                    detail = page.path("title").asText() + " " + page.path("url").asText();
                }
            } catch (Exception ignored) { }
            String shown = clipDetail(detail, 500);
            System.out.println((failed ? ANSI_YELLOW : ANSI_DIM) + "[tool " + name + "] "
                    + (failed ? "失败: " : "完成: ") + shown + ANSI_RESET);
        } catch (Exception ignored) { }
    }

    public DlcCli(CodingAgent agent, SandboxPathResolver pathResolver,
                  ToolCallbackProvider toolCallbackProvider, SessionManager sessionManager,
                  com.dlchm.dlc.tools.MemoryTool memoryTool, ApprovalManager approvalManager,
                  SubagentManager subagentManager,
                  com.dlchm.dlc.config.DlcProperties properties) {
        this.agent = agent;
        this.pathResolver = pathResolver;
        this.toolCallbackProvider = toolCallbackProvider;
        this.sessionManager = sessionManager;
        this.memoryTool = memoryTool;
        this.approvalManager = approvalManager;
        this.subagentManager = subagentManager;
        this.properties = properties;
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
            System.out.println(ANSI_DIM + "Type your request. /quit to exit, /clear to clear, /sessions to list, /resume <id> to resume, /fork to branch, /forget to clear memory, /sub <task> to delegate to a subagent, /config to reconfigure." + ANSI_RESET);
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
                    System.out.print(ansi("H") + ansi("2J"));
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
                    var running = subagentManager.list(session.getId());
                    if (running.isEmpty()) {
                        System.out.println(ANSI_DIM + "No subagents for this session."
                                + (subagentManager.isEnabled() ? "" : " (dlc.subagent.enabled=false)") + ANSI_RESET);
                    } else {
                        running.forEach(task ->
                                System.out.println(task.id() + "  " + task.status()
                                        + "  depth=" + task.depth()
                                        + "  child=" + task.childSessionId()));
                    }
                    continue;
                }
                if (trimmed.toLowerCase().startsWith("/sub")) {
                    if (handleSubCommand(session, trimmed, reader)) continue;
                }
                if ("/config".equalsIgnoreCase(trimmed)) {
                    Properties updated = DlcSetup.reconfigure();
                    agent.reloadConfig();
                    // The tool callback list is fixed at startup, so the switch is
                    // enforced inside SubagentManager; pushing the values into
                    // the live bean is what makes it take effect now.
                    DlcSetup.applyToRuntime(updated, properties);
                    var sub = properties.getSubagent();
                    System.out.println(ANSI_GREEN + "配置已更新，立即生效。" + ANSI_RESET);
                    System.out.println(ANSI_DIM + "  子 Agent: "
                            + (sub.isEnabled() ? "启用" : "停用")
                            + "  深度上限=" + sub.getMaxDepth()
                            + "  并发=" + sub.getMaxConcurrent()
                            + "  超时=" + sub.getTimeoutSeconds() + "s"
                            + " (硬上限 " + sub.getMaxTimeoutSeconds() + "s)" + ANSI_RESET);
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
                                        + ANSI_RESET + ansi("K"));
                                System.out.flush();
                            } else if (event.type() == StreamEvent.Type.APPROVAL_REQUIRED) {
                                handleApproval(event.data(), reader);
                            } else if (event.type() == StreamEvent.Type.TOOL_CALL_STARTED) {
                                System.out.println("\n" + ANSI_DIM + "[tool] " + event.data() + ANSI_RESET);
                            } else if (event.type() == StreamEvent.Type.TOOL_OUTPUT) {
                                printToolOutcome(event.data());
                            } else if (event.type() == StreamEvent.Type.TOOL_CALL_FINISHED) {
                                // Outcome is printed once with the corresponding tool output.
                            } else if (event.type() == StreamEvent.Type.CANCELLED) {
                                System.out.println("\n" + ANSI_YELLOW + "Turn cancelled." + ANSI_RESET);
                            } else {
                                if (inReasoning[0]) {
                                    inReasoning[0] = false;
                                    System.out.print("\r" + ansi("K"));
                                    System.out.print(ANSI_CYAN + "polar> " + ANSI_RESET);
                                }
                                System.out.print(event.data());
                                fullResponse.append(event.data());
                            }
                        },
                        error -> {
                            if (inReasoning[0]) {
                                System.out.print("\r" + ansi("K"));
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
                                System.out.print("\r" + ansi("K"));
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

    /**
     * Handles {@code /sub ...}: the manual counterpart to the model's own
     * delegation.
     *
     * <p>Auto-delegation only fires when the model decides a task splits, which
     * is exactly the case a user knows better than the model does — "run these
     * three checks in parallel" needs no judgement call. This path bypasses the
     * model entirely and calls the manager directly, so it also works when the
     * task is too small for the model to bother decomposing.</p>
     *
     * @return true when the input was consumed as a {@code /sub} command
     */
    private boolean handleSubCommand(Session session, String input, LineReader reader) {
        String rest = input.length() > 4 ? input.substring(4).trim() : "";
        if (rest.isEmpty() || rest.equalsIgnoreCase("help")) {
            printSubHelp();
            return true;
        }
        if (rest.equalsIgnoreCase("list")) {
            printSubagents(session.getId());
            return true;
        }
        if (rest.toLowerCase().startsWith("cancel ")) {
            String id = rest.substring(7).trim();
            boolean cancelled = subagentManager.cancel(id);
            System.out.println(cancelled
                    ? ANSI_GREEN + "已取消子代理 " + id + ANSI_RESET
                    : ANSI_YELLOW + "无法取消 " + id + "（不存在或已结束）" + ANSI_RESET);
            return true;
        }
        if (!subagentManager.isEnabled()) {
            System.out.println(ANSI_YELLOW + "子代理已禁用（dlc.subagent.enabled=false）。" + ANSI_RESET);
            return true;
        }
        delegateManually(session, rest, reader);
        return true;
    }

    private void printSubHelp() {
        System.out.println(ANSI_DIM + "  /sub <任务描述>        手动派发一个子代理并等待其总结");
        System.out.println("  /sub list              列出本会话的子代理");
        System.out.println("  /sub cancel <id>       取消运行中的子代理");
        System.out.println();
        System.out.println(ANSI_DIM + "  提示：子代理没有浏览器工具，也无法请求人工审批；" + ANSI_RESET);
        System.out.println(ANSI_DIM + "  任务描述需自包含（子代理看不到当前对话）。" + ANSI_RESET);
    }

    private void printSubagents(String sessionId) {
        var tasks = subagentManager.list(sessionId);
        if (tasks.isEmpty()) {
            System.out.println(ANSI_DIM + "本会话没有子代理记录。" + ANSI_RESET);
            return;
        }
        tasks.forEach(task -> System.out.println(task.id() + "  " + task.status()
                + "  depth=" + task.depth() + "  child=" + task.childSessionId()));
    }

    /**
     * Runs one delegated task on a worker thread and prints the child's summary.
     *
     * <p>{@code delegate} is synchronous by design — the model path needs the
     * answer before it can continue — so the CLI blocks on a thread here rather
     * than subscribing. Printing before the wait is what keeps a multi-minute
     * delegation from reading as a hang.</p>
     */
    private void delegateManually(Session session, String prompt, LineReader reader) {
        System.out.println();
        System.out.println(ANSI_DIM + "子代理已派发（无浏览器权限，无法请求审批），等待结果…" + ANSI_RESET);
        System.out.flush();
        // The depth is set for the duration of the call so the manager records a
        // level-1 task and the child's own delegation stays blocked.
        ExecutionContext.run(session, 0, () ->
                System.out.println(subagentManager.delegate(prompt, null)));
    }

    private void handleApproval(String data, LineReader reader) {        try {
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
