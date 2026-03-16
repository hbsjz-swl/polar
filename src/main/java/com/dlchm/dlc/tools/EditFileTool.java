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

public class EditFileTool {

    private final SandboxPathResolver pathResolver;
    private final PermissionMode mode;

    public EditFileTool(SandboxPathResolver pathResolver, DlcProperties props) {
        this.pathResolver = pathResolver;
        this.mode = PermissionMode.valueOf(props.getPermissionMode());
    }

    @Tool(name = "edit_file", description = "Replace an exact string in a file. old_string must appear exactly once (uniqueness check).")
    public String editFile(
            @ToolParam(description = "File path relative to workspace") String filePath,
            @ToolParam(description = "Exact string to find (must be unique)") String oldString,
            @ToolParam(description = "Replacement string") String newString) {
        if (mode == PermissionMode.READ_ONLY) throw new SandboxViolationException("Edit disabled in READ_ONLY mode.");
        Path resolved = pathResolver.resolve(filePath);
        if (!Files.exists(resolved)) return "Error: File not found: " + filePath;
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            int first = content.indexOf(oldString);
            if (first == -1) return "Error: old_string not found in file.";
            int second = content.indexOf(oldString, first + 1);
            if (second != -1) return "Error: old_string appears multiple times. Include more context.";

            String updated = content.substring(0, first) + newString + content.substring(first + oldString.length());
            Files.writeString(resolved, updated, StandardCharsets.UTF_8);
            return "Edited: " + filePath + " (replaced " + oldString.length() + " chars with " + newString.length() + " chars)";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
