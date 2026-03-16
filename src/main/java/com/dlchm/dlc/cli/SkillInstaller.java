package com.dlchm.dlc.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 ClawHub 下载并安装技能到 ~/.dlc/skills/。
 *
 * API: GET https://www.clawhub.ai/api/v1/download?slug=<skill-name>
 * 返回 ZIP 文件，解压到 ~/.dlc/skills/<skill-name>/
 */
public class SkillInstaller {

    private static final String CLAWHUB_API = "https://www.clawhub.ai/api/v1/download";
    private static final Path SKILLS_DIR = Path.of(System.getProperty("user.home"), ".dlc", "skills");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int MAX_RETRIES = 3;

    /**
     * 安装技能。返回安装结果描述。
     */
    public static String install(String slug) {
        // 规范化 slug：去空格、转小写、用连字符连接
        slug = slug.trim().toLowerCase().replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9._-]", "");
        if (slug.isEmpty()) {
            return "无效的技能名称。";
        }

        try {
            // 下载 ZIP（遇到 429 自动等待重试）
            String url = CLAWHUB_API + "?slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
            HttpResponse<byte[]> response = downloadWithRetry(url);

            if (response.statusCode() == 404) {
                return "未找到技能: " + slug;
            }
            if (response.statusCode() != 200) {
                return "下载失败 (HTTP " + response.statusCode() + ")";
            }

            byte[] zipBytes = response.body();
            if (zipBytes.length == 0) {
                return "下载失败: 响应为空";
            }

            // 解压到 ~/.dlc/skills/<slug>/
            Path skillDir = SKILLS_DIR.resolve(slug);
            Files.createDirectories(skillDir);

            int fileCount = 0;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;

                    // ZIP 内路径可能是 skill-name/SKILL.md 或直接 SKILL.md
                    String entryName = entry.getName();
                    // 去掉可能的顶层目录前缀
                    int slashIdx = entryName.indexOf('/');
                    String relativePath = (slashIdx >= 0) ? entryName.substring(slashIdx + 1) : entryName;
                    if (relativePath.isEmpty()) continue;

                    Path target = skillDir.resolve(relativePath);
                    Files.createDirectories(target.getParent());
                    Files.write(target, zis.readAllBytes());
                    if (relativePath.endsWith(".py") || relativePath.endsWith(".sh") || relativePath.endsWith(".js")) {
                        target.toFile().setExecutable(true);
                    }
                    fileCount++;
                }
            }

            if (fileCount == 0) {
                return "下载成功但 ZIP 为空";
            }

            // 验证 SKILL.md 存在
            boolean hasSkillMd = Files.exists(skillDir.resolve("SKILL.md"));
            StringBuilder result = new StringBuilder();
            result.append("已安装: ").append(slug).append(" (").append(fileCount).append(" 个文件)");
            if (!hasSkillMd) result.append(" [警告: 未找到 SKILL.md]");
            result.append("\n位置: ").append(skillDir);

            // 检测外部依赖
            if (hasSkillMd) {
                String depHints = detectDependencies(skillDir.resolve("SKILL.md"));
                if (!depHints.isEmpty()) {
                    result.append("\n\n").append(depHints);
                }
            }

            result.append("\n重启 DLC 后生效。");
            return result.toString();

        } catch (IOException | InterruptedException e) {
            return "安装失败: " + e.getMessage();
        }
    }

    /**
     * 卸载技能。
     */
    public static String uninstall(String slug) {
        Path skillDir = SKILLS_DIR.resolve(slug);
        if (!Files.isDirectory(skillDir)) {
            return "未找到本地技能: " + slug;
        }
        try {
            deleteRecursively(skillDir);
            return "已卸载: " + slug + "\n重启 DLC 后生效。";
        } catch (IOException e) {
            return "卸载失败: " + e.getMessage();
        }
    }

    /**
     * 列出本地已安装的技能。
     */
    public static String listInstalled() {
        if (!Files.isDirectory(SKILLS_DIR)) {
            return "暂无已安装的本地技能。";
        }
        try (var dirs = Files.list(SKILLS_DIR)) {
            var installed = dirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("SKILL.md")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
            if (installed.isEmpty()) {
                return "暂无已安装的本地技能。";
            }
            StringBuilder sb = new StringBuilder("已安装的技能:\n");
            for (String name : installed) {
                sb.append("  - ").append(name).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "列出技能失败: " + e.getMessage();
        }
    }

    /**
     * 从 SKILL.md 中检测外部工具依赖，检查本机是否已安装。
     */
    private static String detectDependencies(Path skillMd) {
        try {
            String content = Files.readString(skillMd);

            // 收集安装命令和对应的工具名
            // key=工具命令, value=安装命令
            Map<String, String> deps = new LinkedHashMap<>();

            // npm install -g <package>
            Matcher npm = Pattern.compile("npm install(?:\\s+-g)?\\s+([\\w@/.:-]+)").matcher(content);
            while (npm.find()) {
                String pkg = npm.group(1);
                deps.put(pkg, "npm install -g " + pkg);
            }

            // pip install <package>
            Matcher pip = Pattern.compile("pip3?\\s+install\\s+([\\w-]+)").matcher(content);
            while (pip.find()) {
                String pkg = pip.group(1);
                deps.put(pkg, "pip install " + pkg);
            }

            // brew install <package>
            Matcher brew = Pattern.compile("brew install\\s+([\\w-]+)").matcher(content);
            while (brew.find()) {
                String pkg = brew.group(1);
                deps.put(pkg, "brew install " + pkg);
            }

            if (deps.isEmpty()) return "";

            // 检查哪些工具缺失
            List<String> missing = new ArrayList<>();
            List<String> installed = new ArrayList<>();
            for (Map.Entry<String, String> entry : deps.entrySet()) {
                String tool = entry.getKey();
                // 取包名中最后一段作为命令名（如 @anthropic/tool -> tool）
                String cmd = tool.contains("/") ? tool.substring(tool.lastIndexOf('/') + 1) : tool;
                if (isCommandAvailable(cmd)) {
                    installed.add(tool + " ✓");
                } else {
                    missing.add(entry.getValue());
                }
            }

            if (missing.isEmpty()) {
                return "依赖检测: 全部满足 (" + String.join(", ", installed) + ")";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("⚠ 检测到缺少以下依赖，请手动安装:\n");
            for (String cmd : missing) {
                sb.append("  $ ").append(cmd).append("\n");
            }
            if (!installed.isEmpty()) {
                sb.append("已安装: ").append(String.join(", ", installed));
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process proc = new ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start();
            int exitCode = proc.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpResponse<byte[]> downloadWithRetry(String url) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 429) {
                return response;
            }

            // 从 retry-after 头读取等待秒数，默认 30 秒
            int waitSeconds = response.headers()
                    .firstValue("retry-after")
                    .map(v -> { try { return Integer.parseInt(v); } catch (Exception e) { return 30; } })
                    .orElse(30);

            if (attempt < MAX_RETRIES) {
                System.out.println("  请求频率受限，等待 " + waitSeconds + " 秒... ("
                        + (attempt + 1) + "/" + MAX_RETRIES + ")");
                Thread.sleep(waitSeconds * 1000L);
            }
        }
        // 最后一次仍然 429，返回该响应让调用方处理
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
