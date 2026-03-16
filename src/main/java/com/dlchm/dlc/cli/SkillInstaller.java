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
            return "Invalid skill name.";
        }

        try {
            // 下载 ZIP（遇到 429 自动等待重试）
            String url = CLAWHUB_API + "?slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
            HttpResponse<byte[]> response = downloadWithRetry(url);

            if (response.statusCode() == 404) {
                return "Skill not found: " + slug;
            }
            if (response.statusCode() != 200) {
                return "Download failed (HTTP " + response.statusCode() + ")";
            }

            byte[] zipBytes = response.body();
            if (zipBytes.length == 0) {
                return "Download failed: empty response";
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
                return "Download succeeded but ZIP was empty";
            }

            // 验证 SKILL.md 存在
            boolean hasSkillMd = Files.exists(skillDir.resolve("SKILL.md"));
            return "Installed: " + slug + " (" + fileCount + " files)"
                    + (hasSkillMd ? "" : " [warning: SKILL.md not found]")
                    + "\nLocation: " + skillDir
                    + "\nRestart DLC to load the new skill.";

        } catch (IOException | InterruptedException e) {
            return "Install failed: " + e.getMessage();
        }
    }

    /**
     * 卸载技能。
     */
    public static String uninstall(String slug) {
        Path skillDir = SKILLS_DIR.resolve(slug);
        if (!Files.isDirectory(skillDir)) {
            return "Skill not found locally: " + slug;
        }
        try {
            deleteRecursively(skillDir);
            return "Uninstalled: " + slug + "\nRestart DLC to apply.";
        } catch (IOException e) {
            return "Uninstall failed: " + e.getMessage();
        }
    }

    /**
     * 列出本地已安装的技能。
     */
    public static String listInstalled() {
        if (!Files.isDirectory(SKILLS_DIR)) {
            return "No local skills installed.";
        }
        try (var dirs = Files.list(SKILLS_DIR)) {
            var installed = dirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("SKILL.md")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
            if (installed.isEmpty()) {
                return "No local skills installed.";
            }
            StringBuilder sb = new StringBuilder("Installed skills:\n");
            for (String name : installed) {
                sb.append("  - ").append(name).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "Failed to list skills: " + e.getMessage();
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
                System.out.println("  Rate limited, waiting " + waitSeconds + "s... ("
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
