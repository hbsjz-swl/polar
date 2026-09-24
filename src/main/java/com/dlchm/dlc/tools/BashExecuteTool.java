package com.dlchm.dlc.tools;

import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.BashCommandSandbox;
import com.dlchm.dlc.sandbox.PermissionMode;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.sandbox.SandboxViolationException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class BashExecuteTool {

    private final SandboxPathResolver pathResolver;
    private final BashCommandSandbox commandSandbox;
    private final ToolOutputTruncator truncator;
    private final int timeoutSeconds;
    private final PermissionMode mode;

    public BashExecuteTool(SandboxPathResolver pathResolver, BashCommandSandbox commandSandbox,
                           ToolOutputTruncator truncator, DlcProperties props) {
        this.pathResolver = pathResolver;
        this.commandSandbox = commandSandbox;
        this.truncator = truncator;
        this.timeoutSeconds = props.getBashTimeoutSeconds();
        this.mode = PermissionMode.valueOf(props.getPermissionMode());
    }

    @Tool(name = "bash_execute", description = "Execute a shell command with timeout. Subject to sandbox restrictions.")
    public String bashExecute(
            @ToolParam(description = "Shell command to execute") String command,
            @ToolParam(required = false, description = "Timeout in seconds") Integer timeoutOverride) {
        if (mode == PermissionMode.READ_ONLY) throw new SandboxViolationException("Bash disabled in READ_ONLY mode.");

        BashCommandSandbox.ValidationResult v = commandSandbox.validate(command);
        if (v.requiresConfirmation() && mode == PermissionMode.STANDARD) {
            return "CONFIRMATION_REQUIRED: " + v.reason() + "\nCommand not executed: " + command;
        }

        int timeout = (timeoutOverride != null && timeoutOverride > 0)
                ? Math.min(timeoutOverride, 300) : timeoutSeconds;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(pathResolver.getWorkspaceRoot().toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    char[] chunk = new char[4_096];
                    int count;
                    while ((count = reader.read(chunk)) != -1) {
                        synchronized (output) {
                            output.append(chunk, 0, count);
                            if (output.length() > 60_000) output.delete(20_000, output.length() - 20_000);
                        }
                    }
                } catch (Exception ignored) { }
            }, "dlc-bash-output");
            readerThread.setDaemon(true);
            readerThread.start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return truncator.truncate("Timed out after " + timeout + "s.\n" + output);
            }
            readerThread.join(1_000);
            return truncator.truncate("Exit code: " + process.exitValue() + "\n" + output);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
