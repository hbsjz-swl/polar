package com.dlchm.dlc.tools;

import com.dlchm.dlc.sandbox.SandboxPathResolver;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class ReadFileTool {

    private final SandboxPathResolver pathResolver;
    private final ToolOutputTruncator truncator;
    private final FileSystemTools files;

    public ReadFileTool(SandboxPathResolver pathResolver, ToolOutputTruncator truncator) {
        this.pathResolver = pathResolver;
        this.truncator = truncator;
        this.files = FileSystemTools.builder().allowedDirectory(pathResolver.getWorkspaceRoot()).build();
    }

    @Tool(name = "read_file", description = "Read file contents with line numbers. Supports offset and limit for large files.")
    public String readFile(
            @ToolParam(description = "File path relative to workspace") String filePath,
            @ToolParam(required = false, description = "Start line (1-based), default 1") Integer offset,
            @ToolParam(required = false, description = "Max lines to read, default 2000") Integer limit) {
        return truncator.truncate(files.read(pathResolver.resolve(filePath).toString(), offset, limit));
    }
}
