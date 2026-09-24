package com.dlchm.dlc.tools;

import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.PermissionMode;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.sandbox.SandboxViolationException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class EditFileTool {

    private final SandboxPathResolver pathResolver;
    private final PermissionMode mode;
    private final FileSystemTools files;

    public EditFileTool(SandboxPathResolver pathResolver, DlcProperties props) {
        this.pathResolver = pathResolver;
        this.mode = PermissionMode.valueOf(props.getPermissionMode());
        this.files = FileSystemTools.builder().allowedDirectory(pathResolver.getWorkspaceRoot()).build();
    }

    @Tool(name = "edit_file", description = "Replace an exact string in a file. old_string must appear exactly once (uniqueness check).")
    public String editFile(
            @ToolParam(description = "File path relative to workspace") String filePath,
            @ToolParam(description = "Exact string to find (must be unique)") String oldString,
            @ToolParam(description = "Replacement string") String newString) {
        if (mode == PermissionMode.READ_ONLY) throw new SandboxViolationException("Edit disabled in READ_ONLY mode.");
        Path resolved = pathResolver.resolve(filePath);
        if (!Files.exists(resolved)) return "Error: File not found: " + filePath;
        return files.edit(resolved.toString(), oldString, newString, false);
    }
}
