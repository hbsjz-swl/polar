package com.dlchm.dlc.tools;

import com.dlchm.dlc.sandbox.SandboxPathResolver;
import org.springaicommunity.agent.tools.GrepTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class GrepSearchTool {

    private final SandboxPathResolver pathResolver;
    private final ToolOutputTruncator truncator;
    private final GrepTool grep;

    public GrepSearchTool(SandboxPathResolver pathResolver, ToolOutputTruncator truncator) {
        this.pathResolver = pathResolver;
        this.truncator = truncator;
        this.grep = GrepTool.builder().workingDirectory(pathResolver.getWorkspaceRoot())
                .allowedDirectory(pathResolver.getWorkspaceRoot()).maxOutputLength(30_000).build();
    }

    @Tool(name = "grep_search", description = "Search file contents by regex. Returns matching lines with file path and line number.")
    public String grepSearch(
            @ToolParam(description = "Regex pattern") String pattern,
            @ToolParam(required = false, description = "Glob filter, e.g. '*.java'") String fileGlob,
            @ToolParam(required = false, description = "Subdirectory to search in") String path,
            @ToolParam(required = false, description = "Case insensitive, default false") Boolean caseInsensitive) {
        String root = path == null || path.isBlank()
                ? pathResolver.getWorkspaceRoot().toString() : pathResolver.resolve(path).toString();
        return truncator.truncate(grep.grep(pattern, root, fileGlob, GrepTool.OutputMode.content,
                null, null, null, true, caseInsensitive, null, 200, 0, false));
    }
}
