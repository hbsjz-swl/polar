package com.dlchm.dlc.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

/**
 * SkillsJars 集成工具。
 * 扫描两个来源的 SKILL.md：
 * 1. classpath JAR 中 META-INF/skills/（SkillsJars Maven 依赖）
 * 2. 本地目录 ~/.dlc/skills/（兼容 ClawHub/OpenClaw 技能）
 * 提取脚本到 ~/.dlc/skills/ 目录供 bash_execute 调用。
 */
public class SkillsTool {

    private static final Logger log = LoggerFactory.getLogger(SkillsTool.class);
    private static final Path SKILLS_DIR = Path.of(System.getProperty("user.home"), ".dlc", "skills");

    /**
     * pattern[0] = glob pattern relative to SKILL.md directory
     * pattern[1] = relative path prefix for the key
     */
    private static final String[][] COMPANION_PATTERNS = {
            {"*.md", ""},
            {"scripts/*.py", "scripts/"},
            {"scripts/*.sh", "scripts/"},
            {"scripts/*.js", "scripts/"},
            {"examples/*.py", "examples/"},
            {"examples/*.sh", "examples/"},
            {"examples/*.js", "examples/"},
    };

    private final Map<String, SkillEntry> skills = new LinkedHashMap<>();

    public SkillsTool() {
        scanSkills();
    }

    private void scanSkills() {
        // 1. 扫描 classpath JAR（SkillsJars Maven 依赖）
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        String[] patterns = {
                "classpath*:META-INF/skills/**/SKILL.md",
                "classpath*:META-INF/resources/skills/**/SKILL.md"
        };

        for (String pattern : patterns) {
            try {
                Resource[] resources = resolver.getResources(pattern);
                for (Resource resource : resources) {
                    loadSkill(resource);
                }
            } catch (IOException e) {
                log.debug("No skills found for pattern: {}", pattern);
            }
        }

        // 2. 扫描本地目录 ~/.dlc/skills/（兼容 ClawHub/OpenClaw 技能）
        scanLocalSkills();

        if (!skills.isEmpty()) {
            log.info("Loaded {} skill(s): {}", skills.size(), skills.keySet());
        }
    }

    /**
     * 扫描 ~/.dlc/skills/ 下的本地技能。
     * 支持两种目录结构：
     *   ~/.dlc/skills/<name>/SKILL.md          （标准目录结构）
     *   ~/.dlc/skills/<author>/<name>/SKILL.md  （ClawHub 安装结构）
     */
    private void scanLocalSkills() {
        if (!Files.isDirectory(SKILLS_DIR)) return;

        try (var topLevel = Files.list(SKILLS_DIR)) {
            topLevel.filter(Files::isDirectory).forEach(dir -> {
                Path skillMd = dir.resolve("SKILL.md");
                if (Files.isRegularFile(skillMd)) {
                    loadLocalSkill(skillMd);
                } else {
                    // 可能是 author/name 两级结构
                    try (var subLevel = Files.list(dir)) {
                        subLevel.filter(Files::isDirectory).forEach(subDir -> {
                            Path nested = subDir.resolve("SKILL.md");
                            if (Files.isRegularFile(nested)) {
                                loadLocalSkill(nested);
                            }
                        });
                    } catch (IOException ignored) {}
                }
            });
        } catch (IOException e) {
            log.debug("Failed to scan local skills directory: {}", e.getMessage());
        }
    }

    private void loadLocalSkill(Path skillMdPath) {
        try {
            String content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
            String dirName = skillMdPath.getParent().getFileName().toString();
            String name = extractFrontmatterField(content, "name");
            if (name == null || name.isBlank()) {
                name = dirName;
            }

            // 已从 classpath 加载过同名技能则跳过
            if (skills.containsKey(name)) {
                log.debug("Skipping local skill '{}', already loaded from classpath", name);
                return;
            }

            String description = extractDescription(content);
            Map<String, String> companions = loadLocalCompanionFiles(skillMdPath.getParent());
            Path scriptsDir = extractScriptsToFileSystem(name, companions);

            skills.put(name, new SkillEntry(name, description, content, companions, scriptsDir));
            log.info("Registered local skill: {} ({})", name, skillMdPath);
        } catch (IOException e) {
            log.warn("Failed to load local skill from {}: {}", skillMdPath, e.getMessage());
        }
    }

    private Map<String, String> loadLocalCompanionFiles(Path skillDir) {
        Map<String, String> companions = new LinkedHashMap<>();
        for (String[] pattern : COMPANION_PATTERNS) {
            String prefix = pattern[1];
            Path searchDir = prefix.isEmpty() ? skillDir : skillDir.resolve(prefix.replace("/", ""));
            if (!Files.isDirectory(searchDir)) continue;

            try (var files = Files.list(searchDir)) {
                String ext = pattern[0].substring(pattern[0].lastIndexOf('.'));
                files.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(ext))
                        .filter(f -> !f.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                        .filter(f -> !f.getFileName().toString().equalsIgnoreCase("LICENSE.txt"))
                        .forEach(f -> {
                            try {
                                String key = prefix.isEmpty()
                                        ? f.getFileName().toString()
                                        : prefix + f.getFileName().toString();
                                companions.put(key, Files.readString(f, StandardCharsets.UTF_8));
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }
        return companions;
    }

    private void loadSkill(Resource resource) {
        try (InputStream is = resource.getInputStream()) {
            String content = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
            String name = extractName(content, resource);
            String description = extractDescription(content);

            Map<String, String> companions = loadCompanionFiles(resource);
            Path scriptsDir = extractScriptsToFileSystem(name, companions);

            skills.put(name, new SkillEntry(name, description, content, companions, scriptsDir));
            log.info("Registered skill: {} (scripts: {})", name,
                    scriptsDir != null ? scriptsDir : "none");
        } catch (IOException e) {
            log.warn("Failed to load skill from {}: {}", resource, e.getMessage());
        }
    }

    private Map<String, String> loadCompanionFiles(Resource skillMdResource) {
        Map<String, String> companions = new LinkedHashMap<>();
        try {
            String uri = skillMdResource.getURI().toString();
            String dirUri = uri.substring(0, uri.lastIndexOf('/'));

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (String[] pattern : COMPANION_PATTERNS) {
                try {
                    Resource[] siblings = resolver.getResources(dirUri + "/" + pattern[0]);
                    for (Resource sibling : siblings) {
                        String filename = sibling.getFilename();
                        if (filename != null && !filename.equalsIgnoreCase("SKILL.md")
                                && !filename.equalsIgnoreCase("LICENSE.txt")) {
                            String key = pattern[1].isEmpty() ? filename : pattern[1] + filename;
                            try (InputStream in = sibling.getInputStream()) {
                                companions.put(key, StreamUtils.copyToString(in, StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return companions;
    }

    /**
     * 将脚本文件从 JAR 提取到 ~/.dlc/skills/<name>/ 目录，使 bash_execute 可以直接运行。
     */
    private Path extractScriptsToFileSystem(String skillName, Map<String, String> companions) {
        Path skillDir = SKILLS_DIR.resolve(skillName);
        boolean extracted = false;
        for (Map.Entry<String, String> entry : companions.entrySet()) {
            String relPath = entry.getKey();
            if (relPath.endsWith(".py") || relPath.endsWith(".sh") || relPath.endsWith(".js")) {
                Path target = skillDir.resolve(relPath);
                try {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                    target.toFile().setExecutable(true);
                    extracted = true;
                } catch (IOException e) {
                    log.warn("Failed to extract script {}: {}", relPath, e.getMessage());
                }
            }
        }
        return extracted ? skillDir : null;
    }

    // ==================== Frontmatter ====================

    private String extractName(String content, Resource resource) {
        String name = extractFrontmatterField(content, "name");
        if (name != null) return name;
        try {
            String path = resource.getURI().toString();
            String[] parts = path.split("/skills/");
            if (parts.length > 1) {
                String skillPath = parts[parts.length - 1].replace("/SKILL.md", "");
                return skillPath.replace("/", ".");
            }
        } catch (IOException ignored) {}
        return "unknown";
    }

    private String extractDescription(String content) {
        String desc = extractFrontmatterField(content, "description");
        return desc != null ? desc : "";
    }

    private String extractFrontmatterField(String content, String field) {
        if (!content.startsWith("---")) return null;
        int end = content.indexOf("---", 3);
        if (end < 0) return null;
        String frontmatter = content.substring(3, end);
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(field + ":")) {
                return trimmed.substring(field.length() + 1).trim();
            }
        }
        return null;
    }

    // ==================== Tool Methods ====================

    public boolean hasSkills() {
        return !skills.isEmpty();
    }

    @Tool(name = "list_skills", description = "List all available skills. Returns skill names and descriptions.")
    public String listSkills() {
        if (skills.isEmpty()) {
            return "No skills available.";
        }
        StringBuilder sb = new StringBuilder("Available skills:\n\n");
        for (SkillEntry entry : skills.values()) {
            sb.append("- **").append(entry.name).append("**: ").append(entry.description).append("\n");
        }
        sb.append("\nUse the `use_skill` tool with the skill name to get detailed instructions.");
        return sb.toString();
    }

    @Tool(name = "use_skill", description = "Get the full instructions for a specific skill. "
            + "Call list_skills first to see available skills, then use this to load a skill's instructions.")
    public String useSkill(
            @ToolParam(description = "Name of the skill to use") String skillName,
            @ToolParam(required = false, description = "Name of a companion file to read, e.g. 'scripts/with_server.py' or 'examples/element_discovery.py'") String file) {
        SkillEntry entry = skills.get(skillName);
        if (entry == null) {
            entry = skills.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(skillName))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
        if (entry == null) {
            return "Skill not found: " + skillName + ". Use list_skills to see available skills.";
        }

        if (file != null && !file.isBlank()) {
            String content = entry.companions.get(file);
            if (content == null) {
                content = entry.companions.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(file))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(null);
            }
            if (content != null) {
                return content;
            }
            return "File '" + file + "' not found in skill '" + skillName
                    + "'. Available files: " + entry.companions.keySet();
        }

        // Return SKILL.md with scripts directory info prepended
        StringBuilder result = new StringBuilder();
        if (entry.scriptsDir != null) {
            result.append("## Scripts Location\n\n");
            result.append("Scripts have been extracted to: `").append(entry.scriptsDir).append("`\n\n");
            result.append("Available scripts:\n");
            for (String key : entry.companions.keySet()) {
                if (key.endsWith(".py") || key.endsWith(".sh") || key.endsWith(".js")) {
                    result.append("- `").append(entry.scriptsDir).append("/").append(key).append("`\n");
                }
            }
            result.append("\nUse full paths when running scripts via bash_execute.\n\n---\n\n");
        }
        result.append(entry.content);
        return result.toString();
    }

    private record SkillEntry(String name, String description, String content,
                               Map<String, String> companions, Path scriptsDir) {}
}
