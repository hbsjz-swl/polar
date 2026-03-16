package com.dlchm.dlc.tools;

import com.dlchm.dlc.sandbox.SandboxPathResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class GrepSearchTool {

    private final SandboxPathResolver pathResolver;
    private final ToolOutputTruncator truncator;

    public GrepSearchTool(SandboxPathResolver pathResolver, ToolOutputTruncator truncator) {
        this.pathResolver = pathResolver;
        this.truncator = truncator;
    }

    @Tool(name = "grep_search", description = "Search file contents by regex. Returns matching lines with file path and line number.")
    public String grepSearch(
            @ToolParam(description = "Regex pattern") String pattern,
            @ToolParam(required = false, description = "Glob filter, e.g. '*.java'") String fileGlob,
            @ToolParam(required = false, description = "Subdirectory to search in") String path,
            @ToolParam(required = false, description = "Case insensitive, default false") Boolean caseInsensitive) {
        Path root = (path != null && !path.isBlank()) ? pathResolver.resolve(path) : pathResolver.getWorkspaceRoot();
        Pattern regex;
        try {
            int flags = (caseInsensitive != null && caseInsensitive) ? Pattern.CASE_INSENSITIVE : 0;
            regex = Pattern.compile(pattern, flags);
        } catch (Exception e) {
            return "Error: Invalid regex: " + e.getMessage();
        }
        PathMatcher fileMatcher = (fileGlob != null && !fileGlob.isBlank())
                ? FileSystems.getDefault().getPathMatcher("glob:" + fileGlob) : null;

        List<String> matches = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= 200) return FileVisitResult.TERMINATE;
                    if (attrs.size() > 1_000_000) return FileVisitResult.CONTINUE;
                    if (fileMatcher != null && !fileMatcher.matches(file.getFileName())) return FileVisitResult.CONTINUE;
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        String rel = pathResolver.getWorkspaceRoot().relativize(file).toString();
                        for (int i = 0; i < lines.size() && matches.size() < 200; i++) {
                            if (regex.matcher(lines.get(i)).find()) {
                                matches.add(rel + ":" + (i + 1) + ": " + lines.get(i).trim());
                            }
                        }
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        if (matches.isEmpty()) return "No matches for: " + pattern;
        return truncator.truncate("Found " + matches.size() + " matches for '" + pattern + "':\n"
                + matches.stream().collect(Collectors.joining("\n")));
    }
}
