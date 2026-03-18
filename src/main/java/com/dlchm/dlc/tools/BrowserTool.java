package com.dlchm.dlc.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 浏览器工具 - 通过 Chrome DevTools Protocol (CDP) 连接用户的浏览器。
 *
 * 核心设计：连接用户已有的 Chrome，而不是每次启动新浏览器。
 * 这样可以共享 Cookie、登录状态，用户可以随时介入（验证码等）。
 *
 * 蒂爱喜(北京)有限公司
 */
public class BrowserTool {

    private static final Path SCRIPTS_DIR = Path.of(System.getProperty("user.home"), ".dlc", "scripts");
    private static final int TIMEOUT_SECONDS = 120;
    private static final int CDP_PORT = 9222;
    private static final String CDP_URL = "http://localhost:" + CDP_PORT;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String PYTHON_CMD = IS_WINDOWS ? "python" : "python3";

    private final ToolOutputTruncator truncator;

    public BrowserTool(ToolOutputTruncator truncator) {
        this.truncator = truncator;
    }

    @Tool(name = "browser_start", description = "Start a Chrome browser with remote debugging enabled, "
            + "or connect to an existing one. The browser window is VISIBLE so the user can see and interact with it "
            + "(e.g., solve CAPTCHAs, complete 2FA). Returns the list of open tabs. "
            + "MUST call this before browser_view or browser_action.")
    public String browserStart() {
        // Check if Chrome is already running with CDP
        String tabs = listTabsViaCdp();
        if (tabs != null) {
            return "Chrome is already running.\n" + tabs;
        }

        // Detect OS and Chrome path
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd = new ArrayList<>();

        if (os.contains("mac")) {
            // macOS: try common Chrome locations
            String chromePath = findChromeMac();
            if (chromePath == null) {
                return "Error: Chrome not found on macOS. Please install Google Chrome.";
            }
            cmd.add(chromePath);
        } else if (os.contains("win")) {
            String chromePath = findChromeWindows();
            if (chromePath == null) {
                return "Error: Chrome not found on Windows. Please install Google Chrome.";
            }
            cmd.add(chromePath);
        } else {
            // Linux
            cmd.add(findChromeLinux());
        }

        String profileDir = Path.of(System.getProperty("java.io.tmpdir"), "dlc-chrome-profile").toString();
        cmd.add("--remote-debugging-port=" + CDP_PORT);
        cmd.add("--user-data-dir=" + profileDir);
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();

            // Wait for Chrome to start
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                tabs = listTabsViaCdp();
                if (tabs != null) {
                    return "Chrome started successfully.\n" + tabs;
                }
            }
            return "Chrome process started but CDP not responding. "
                    + "If Chrome was already running, close it first and retry.";
        } catch (Exception e) {
            return "Error starting Chrome: " + e.getMessage()
                    + "\nTry starting Chrome manually with: --remote-debugging-port=" + CDP_PORT;
        }
    }

    @Tool(name = "browser_view", description = "Analyze the current page in the browser. "
            + "Returns page structure: title, URL, headings, forms (fields + buttons), "
            + "buttons, links, images, tables, and main text content. "
            + "Call browser_start first, then use this to understand the page before performing actions.")
    public String browserView(
            @ToolParam(required = false, description = "Tab index to view (default: 0 = first tab)") Integer tab,
            @ToolParam(required = false, description = "Optional path to save screenshot, e.g. /tmp/page.png") String screenshot) {

        Path script = SCRIPTS_DIR.resolve("browser_cdp.py");
        if (!Files.exists(script)) {
            return "Error: browser_cdp.py not found. Restart DLC to extract built-in scripts.";
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(PYTHON_CMD);
        cmd.add(script.toString());
        cmd.add("view");
        cmd.add("--cdp-url");
        cmd.add(CDP_URL);
        if (tab != null) {
            cmd.add("--tab");
            cmd.add(String.valueOf(tab));
        }
        if (screenshot != null && !screenshot.isBlank()) {
            cmd.add("--screenshot");
            cmd.add(screenshot);
        }
        return runProcess(cmd);
    }

    @Tool(name = "browser_action", description = "Perform actions on the current browser page. "
            + "Call browser_view first to understand the page layout, then use this to interact. "
            + "Supported actions: goto (navigate to URL), click, fill, select, check, uncheck, hover, "
            + "press, scroll, screenshot, get_text, get_attr, evaluate, wait. "
            + "Actions execute in sequence. The browser stays open after actions complete - "
            + "login state, cookies, and page state are PRESERVED for next call.")
    public String browserAction(
            @ToolParam(description = "JSON array of actions. Examples:\n"
                    + "Navigate: [{\"action\":\"goto\",\"url\":\"https://example.com\"}]\n"
                    + "Fill + Click: [{\"action\":\"fill\",\"selector\":\"#username\",\"value\":\"admin\"},"
                    + "{\"action\":\"fill\",\"selector\":\"#password\",\"value\":\"123\"},"
                    + "{\"action\":\"click\",\"selector\":\"button[type=submit]\"}]\n"
                    + "Get text: [{\"action\":\"get_text\",\"selector\":\".result\"}]\n"
                    + "Scroll: [{\"action\":\"scroll\",\"direction\":\"down\",\"amount\":500}]\n"
                    + "Selectors: text=Submit, #id, .class, input[name=email], button:has-text(\"OK\")") String actions,
            @ToolParam(required = false, description = "Tab index (default: 0)") Integer tab,
            @ToolParam(required = false, description = "Optional path to save final screenshot") String screenshot) {

        Path script = SCRIPTS_DIR.resolve("browser_cdp.py");
        if (!Files.exists(script)) {
            return "Error: browser_cdp.py not found. Restart DLC to extract built-in scripts.";
        }

        // 将 JSON 写入临时文件，避免 Windows 命令行双引号转义问题
        Path actionsFile;
        try {
            actionsFile = Files.createTempFile("dlc-actions-", ".json");
            Files.writeString(actionsFile, actions, StandardCharsets.UTF_8);
            actionsFile.toFile().deleteOnExit();
        } catch (Exception e) {
            return "Error: Failed to write actions file: " + e.getMessage();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(PYTHON_CMD);
        cmd.add(script.toString());
        cmd.add("action");
        cmd.add("--cdp-url");
        cmd.add(CDP_URL);
        cmd.add("--actions-file");
        cmd.add(actionsFile.toString());
        if (tab != null) {
            cmd.add("--tab");
            cmd.add(String.valueOf(tab));
        }
        if (screenshot != null && !screenshot.isBlank()) {
            cmd.add("--screenshot");
            cmd.add(screenshot);
        }
        return runProcess(cmd);
    }

    // ==================== Helpers ====================

    /**
     * Query CDP endpoint to list open tabs. Returns null if Chrome is not running.
     */
    private String listTabsViaCdp() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    CDP_URL + "/json/list").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                    }
                }
                // Parse and format tab list
                return formatTabs(sb.toString());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String formatTabs(String jsonArray) {
        try {
            // Simple JSON array parsing without Jackson dependency
            StringBuilder result = new StringBuilder("Open tabs:\n");
            int index = 0;
            int pos = 0;
            while (pos < jsonArray.length()) {
                int titleStart = jsonArray.indexOf("\"title\":", pos);
                if (titleStart < 0) break;
                int urlStart = jsonArray.indexOf("\"url\":", pos);
                if (urlStart < 0) break;

                String title = extractJsonString(jsonArray, titleStart + 8);
                String url = extractJsonString(jsonArray, urlStart + 6);

                if (!"".equals(url) && !url.startsWith("devtools://")) {
                    result.append(String.format("  [%d] %s - %s%n", index, title, url));
                    index++;
                }
                pos = Math.max(titleStart, urlStart) + 10;
            }
            if (index == 0) {
                result.append("  (no tabs open)\n");
            }
            return result.toString();
        } catch (Exception e) {
            return "Tabs: (unable to parse)";
        }
    }

    private String extractJsonString(String json, int startPos) {
        int quote1 = json.indexOf('"', startPos);
        if (quote1 < 0) return "";
        int quote2 = json.indexOf('"', quote1 + 1);
        if (quote2 < 0) return "";
        return json.substring(quote1 + 1, quote2);
    }

    private String findChromeMac() {
        String[] paths = {
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                System.getProperty("user.home") + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        };
        for (String p : paths) {
            if (Files.exists(Path.of(p))) return p;
        }
        return null;
    }

    private String findChromeWindows() {
        String[] paths = {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String p : paths) {
            if (p != null && Files.exists(Path.of(p))) return p;
        }
        return null;
    }

    private String findChromeLinux() {
        String[] names = {"google-chrome", "google-chrome-stable", "chromium-browser", "chromium"};
        for (String name : names) {
            try {
                Process p = new ProcessBuilder("which", name)
                        .redirectErrorStream(true).start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return name;
                }
            } catch (Exception ignored) {
            }
        }
        return "google-chrome";
    }

    private String runProcess(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Error: Browser operation timed out after " + TIMEOUT_SECONDS + "s.";
            }

            String result = output.toString();

            if (process.exitValue() != 0) {
                if (result.contains("No module named 'playwright'")) {
                    return "Error: Playwright is not installed. "
                            + "Run: pip3 install playwright && playwright install chromium";
                }
                if (result.contains("connect") && result.contains("refused")) {
                    return "Error: Cannot connect to Chrome. Call browser_start first.";
                }
                return "Error (exit " + process.exitValue() + "): " + result;
            }

            return truncator.truncate(result);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
