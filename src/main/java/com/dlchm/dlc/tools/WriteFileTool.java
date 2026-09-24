package com.dlchm.dlc.tools;

import com.dlchm.dlc.config.DlcProperties;
import com.dlchm.dlc.sandbox.PermissionMode;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.sandbox.SandboxViolationException;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WriteFileTool {

    private final SandboxPathResolver pathResolver;
    private final PermissionMode mode;
    private final FileSystemTools files;

    public WriteFileTool(SandboxPathResolver pathResolver, DlcProperties props) {
        this.pathResolver = pathResolver;
        this.mode = PermissionMode.valueOf(props.getPermissionMode());
        this.files = FileSystemTools.builder().allowedDirectory(pathResolver.getWorkspaceRoot()).build();
    }

    @Tool(name = "write_file", description = "Create or overwrite a file. Creates parent directories if needed.")
    public String writeFile(
            @ToolParam(description = "File path relative to workspace") String filePath,
            @ToolParam(description = "Complete file content") String content) {
        if (mode == PermissionMode.READ_ONLY) throw new SandboxViolationException("Write disabled in READ_ONLY mode.");
        return files.write(pathResolver.resolve(filePath).toString(), content);
    }
}
