package com.dlchm.dlc.tools;

import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.PermissionMode;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.sandbox.SandboxViolationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WriteFileTool {

    private final SandboxPathResolver pathResolver;
    private final PermissionMode mode;

    public WriteFileTool(SandboxPathResolver pathResolver, DlcProperties props) {
        this.pathResolver = pathResolver;
        this.mode = PermissionMode.valueOf(props.getPermissionMode());
    }

    @Tool(name = "write_file", description = "Create or overwrite a file. Creates parent directories if needed.")
    public String writeFile(
            @ToolParam(description = "File path relative to workspace") String filePath,
            @ToolParam(description = "Complete file content") String content) {
        if (mode == PermissionMode.READ_ONLY) throw new SandboxViolationException("Write disabled in READ_ONLY mode.");
        Path resolved = pathResolver.resolve(filePath);
        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            long lines = content.chars().filter(c -> c == '\n').count() + 1;
            return "Written: " + filePath + " (" + lines + " lines, " + content.length() + " chars)";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
