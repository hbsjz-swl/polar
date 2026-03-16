package com.dlchm.dlc.tools;

import com.dlchm.dlc.sandbox.SandboxPathResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class ReadFileTool {

    private final SandboxPathResolver pathResolver;
    private final ToolOutputTruncator truncator;

    public ReadFileTool(SandboxPathResolver pathResolver, ToolOutputTruncator truncator) {
        this.pathResolver = pathResolver;
        this.truncator = truncator;
    }

    @Tool(name = "read_file", description = "Read file contents with line numbers. Supports offset and limit for large files.")
    public String readFile(
            @ToolParam(description = "File path relative to workspace") String filePath,
            @ToolParam(required = false, description = "Start line (1-based), default 1") Integer offset,
            @ToolParam(required = false, description = "Max lines to read, default 2000") Integer limit) {
        Path resolved = pathResolver.resolve(filePath);
        if (!Files.exists(resolved)) return "Error: File not found: " + filePath;
        if (Files.isDirectory(resolved)) return "Error: Is a directory: " + filePath;
        try {
            List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            int start = (offset != null && offset > 0) ? offset - 1 : 0;
            int max = (limit != null && limit > 0) ? limit : 2000;
            int end = Math.min(start + max, lines.size());
            if (start >= lines.size()) return "File has " + lines.size() + " lines. Offset beyond end.";

            String content = IntStream.range(start, end)
                    .mapToObj(i -> String.format("%6d | %s", i + 1, lines.get(i)))
                    .collect(Collectors.joining("\n"));
            return truncator.truncate("File: " + filePath + " (" + lines.size() + " lines, showing " + (start + 1) + "-" + end + ")\n" + content);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
