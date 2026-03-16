package com.dlchm.dlc.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 持久化记忆工具。
 * 全局记忆：~/.dlc/memory.md
 * 项目记忆：<workspace>/.dlc/memory.md
 *
 * 启动时自动加载到系统提示词，LLM 可通过 memory_save 主动记忆。
 */
public class MemoryTool {

    private final Path globalMemoryPath;
    private final Path projectMemoryPath;

    public MemoryTool(Path workspaceRoot) {
        this.globalMemoryPath = Path.of(System.getProperty("user.home"), ".dlc", "memory.md");
        this.projectMemoryPath = workspaceRoot.resolve(".dlc").resolve("memory.md");
    }

    // ==================== LLM Tools ====================

    @Tool(name = "memory_save", description = "Save important information to memory for future sessions. "
            + "Use for: user preferences, project conventions, key architectural decisions, "
            + "solutions to recurring problems, or anything worth remembering across sessions. "
            + "Memories persist in a markdown file and are loaded automatically at the start of each session.")
    public String memorySave(
            @ToolParam(description = "Content to remember. Be concise and specific.") String content,
            @ToolParam(description = "Scope: 'global' (applies to all projects, e.g. user preferences) "
                    + "or 'project' (applies to current project only, e.g. project conventions). "
                    + "Default: 'project'")
            String scope) {

        boolean isGlobal = "global".equalsIgnoreCase(scope);
        Path path = isGlobal ? globalMemoryPath : projectMemoryPath;

        try {
            Files.createDirectories(path.getParent());

            // Initialize file with header if it doesn't exist
            if (!Files.exists(path)) {
                String header = isGlobal
                        ? "# DLC Global Memory\n\n"
                        : "# DLC Project Memory\n\n";
                Files.writeString(path, header, StandardCharsets.UTF_8);
            }

            // Check for duplicate (exact same content already exists)
            String existing = Files.readString(path, StandardCharsets.UTF_8);
            if (existing.contains(content.trim())) {
                return "Memory already exists, skipped.";
            }

            // Append with date tag
            String entry = "\n- [" + LocalDate.now() + "] " + content.trim() + "\n";
            Files.writeString(path, existing + entry, StandardCharsets.UTF_8);

            return "Saved to " + (isGlobal ? "global" : "project") + " memory.";
        } catch (IOException e) {
            return "Failed to save memory: " + e.getMessage();
        }
    }

    @Tool(name = "memory_read", description = "Read current memories. Use to check what has been remembered before saving.")
    public String memoryRead(
            @ToolParam(description = "Scope: 'global', 'project', or 'all'. Default: 'all'")
            String scope) {

        StringBuilder sb = new StringBuilder();
        if (!"project".equalsIgnoreCase(scope)) {
            sb.append("## Global Memory\n\n");
            sb.append(readFile(globalMemoryPath));
            sb.append("\n\n");
        }
        if (!"global".equalsIgnoreCase(scope)) {
            sb.append("## Project Memory\n\n");
            sb.append(readFile(projectMemoryPath));
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "No memories saved yet." : result;
    }

    @Tool(name = "memory_delete", description = "Delete a specific memory entry by its content (or a substring). "
            + "Use to remove outdated or incorrect memories.")
    public String memoryDelete(
            @ToolParam(description = "Text to match (the memory entry to delete)") String match,
            @ToolParam(description = "Scope: 'global' or 'project'. Default: 'project'") String scope) {

        boolean isGlobal = "global".equalsIgnoreCase(scope);
        Path path = isGlobal ? globalMemoryPath : projectMemoryPath;

        if (!Files.exists(path)) {
            return "No memory file found.";
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> kept = new ArrayList<>();
            int removed = 0;
            for (String line : lines) {
                if (line.contains(match.trim())) {
                    removed++;
                } else {
                    kept.add(line);
                }
            }
            if (removed == 0) {
                return "No matching memory found for: " + match;
            }
            Files.writeString(path, String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
            return "Removed " + removed + " memory entry(s).";
        } catch (IOException e) {
            return "Failed to delete memory: " + e.getMessage();
        }
    }

    @Tool(name = "memory_clear", description = "Clear all memories. Use when user explicitly asks to forget everything or reset memory.")
    public String memoryClear(
            @ToolParam(description = "Scope: 'global', 'project', or 'all'. Default: 'all'") String scope) {

        boolean clearGlobal = "global".equalsIgnoreCase(scope) || "all".equalsIgnoreCase(scope);
        boolean clearProject = "project".equalsIgnoreCase(scope) || "all".equalsIgnoreCase(scope);
        List<String> cleared = new ArrayList<>();

        if (clearGlobal && Files.exists(globalMemoryPath)) {
            try {
                Files.deleteIfExists(globalMemoryPath);
                cleared.add("全局记忆");
            } catch (IOException e) {
                return "清除全局记忆失败: " + e.getMessage();
            }
        }
        if (clearProject && Files.exists(projectMemoryPath)) {
            try {
                Files.deleteIfExists(projectMemoryPath);
                cleared.add("项目记忆");
            } catch (IOException e) {
                return "清除项目记忆失败: " + e.getMessage();
            }
        }

        if (cleared.isEmpty()) {
            return "没有需要清除的记忆。";
        }
        return "已清除: " + String.join("、", cleared);
    }

    /**
     * 供 CLI /forget 命令直接调用。
     */
    public String clearAll() {
        return memoryClear("all");
    }

    // ==================== Loading for System Prompt ====================

    /**
     * Load all memories for injection into system prompt.
     * Called by CodingAgent at the start of each conversation turn.
     */
    public String loadAllMemory() {
        String global = readFile(globalMemoryPath);
        String project = readFile(projectMemoryPath);

        if (global.isEmpty() && project.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n## Memory (from previous sessions)\n\n");
        if (!global.isEmpty()) {
            sb.append("### Global\n").append(global).append("\n\n");
        }
        if (!project.isEmpty()) {
            sb.append("### Project\n").append(project).append("\n");
        }
        return sb.toString();
    }

    private String readFile(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            // Strip the header line if it starts with "# DLC"
            if (content.startsWith("# DLC")) {
                int nl = content.indexOf('\n');
                if (nl > 0) {
                    content = content.substring(nl + 1).trim();
                }
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }
}
