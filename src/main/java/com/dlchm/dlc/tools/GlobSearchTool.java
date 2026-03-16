package com.dlchm.dlc.tools;

import com.dlchm.dlc.sandbox.SandboxPathResolver;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class GlobSearchTool {

    private final SandboxPathResolver pathResolver;
    private final ToolOutputTruncator truncator;

    public GlobSearchTool(SandboxPathResolver pathResolver, ToolOutputTruncator truncator) {
        this.pathResolver = pathResolver;
        this.truncator = truncator;
    }

    @Tool(name = "glob_search", description = "Find files matching a glob pattern (e.g., '**/*.java'). Returns file paths relative to workspace.")
    public String globSearch(
            @ToolParam(description = "Glob pattern") String pattern,
            @ToolParam(required = false, description = "Subdirectory to search in") String path) {
        Path root = (path != null && !path.isBlank()) ? pathResolver.resolve(path) : pathResolver.getWorkspaceRoot();
        if (!Files.isDirectory(root)) return "Error: Not a directory: " + path;
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(root.relativize(file))) {
                        matches.add(pathResolver.getWorkspaceRoot().relativize(file).toString());
                        if (matches.size() >= 500) return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            if (matches.isEmpty()) return "No files matching: " + pattern;
            return truncator.truncate("Found " + matches.size() + " files matching '" + pattern + "':\n"
                    + matches.stream().collect(Collectors.joining("\n")));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
