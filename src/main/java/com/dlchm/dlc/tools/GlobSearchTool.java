package com.dlchm.dlc.tools;

import com.dlchm.dlc.sandbox.SandboxPathResolver;
import org.springaicommunity.agent.tools.GlobTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class GlobSearchTool {

    private final SandboxPathResolver pathResolver;
    private final ToolOutputTruncator truncator;
    private final GlobTool glob;

    public GlobSearchTool(SandboxPathResolver pathResolver, ToolOutputTruncator truncator) {
        this.pathResolver = pathResolver;
        this.truncator = truncator;
        this.glob = GlobTool.builder().workingDirectory(pathResolver.getWorkspaceRoot())
                .allowedDirectory(pathResolver.getWorkspaceRoot()).maxResults(500).build();
    }

    @Tool(name = "glob_search", description = "Find files matching a glob pattern (e.g., '**/*.java'). Returns file paths relative to workspace.")
    public String globSearch(
            @ToolParam(description = "Glob pattern") String pattern,
            @ToolParam(required = false, description = "Subdirectory to search in") String path) {
        String root = path == null || path.isBlank()
                ? pathResolver.getWorkspaceRoot().toString() : pathResolver.resolve(path).toString();
        return truncator.truncate(glob.glob(pattern, root));
    }
}
