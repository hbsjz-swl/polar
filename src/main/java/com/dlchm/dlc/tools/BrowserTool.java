package com.dlchm.dlc.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.ConnectException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dlchm.dlc.agent.ExecutionContext;
import com.dlchm.dlc.sandbox.ProcessIsolation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 浏览器工具 - 通过 Chrome DevTools Protocol (CDP) 连接用户的浏览器。
 *
 * 核心设计：连接用户已有的 Chrome，而不是每次启动新浏览器。
 * 这样可以共享 Cookie、登录状态，用户可以随时介入（验证码等）。
 *
 */
public class BrowserTool {

    private static final Path SCRIPTS_DIR = Path.of(System.getProperty("user.home"), ".dlc", "scripts");
    private static final int TIMEOUT_SECONDS = 120;
    private static final int CDP_PORT = 9222;
    private static final String CDP_URL = "http://127.0.0.1:" + CDP_PORT;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String PYTHON_CMD = IS_WINDOWS ? "python" : "python3";

    private final ToolOutputTruncator truncator;
    private volatile Process ownedChrome;
    private String cdpUrl;
    private final boolean managedEndpoint;
    private final Path runtime;
    private final Path profile;
    private final Path script;
    private final ObjectMapper mapper = new ObjectMapper();
    private String cdpProblem;

    public BrowserTool(ToolOutputTruncator truncator) {
        this(truncator, CDP_URL, Path.of(System.getProperty("user.home"), ".dlc", "browser"));
    }

    BrowserTool(ToolOutputTruncator truncator, String cdpUrl, Path runtime) {
        this(truncator, cdpUrl, runtime, SCRIPTS_DIR.resolve("browser_cdp.py"));
    }

    BrowserTool(ToolOutputTruncator truncator, String cdpUrl, Path runtime, Path script) {
        this.truncator = truncator;
        this.cdpUrl = cdpUrl;
        this.managedEndpoint = CDP_URL.equals(cdpUrl);
        this.runtime = runtime;
        this.profile = runtime.resolve("profile");
        this.script = script;
    }

    @Tool(name = "browser_start", description = "Start a Chrome browser with remote debugging enabled, "
            + "or connect to an existing one. The browser window is VISIBLE so the user can see and interact with it "
            + "(e.g., solve CAPTCHAs, complete 2FA). Returns the list of open tabs. "
            + "MUST call this before browser_view or browser_action.")
    public synchronized String browserStart() {
        try {
            Files.createDirectories(runtime);
            // Serialize startup across DLC instances, in addition to method synchronization.
            try (FileChannel channel = FileChannel.open(runtime.resolve("launch.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (java.nio.channels.OverlappingFileLockException e) { lock = null; }
                if (lock == null) return "Error: Another DLC instance is starting Chrome. Wait and observe again.";
                try (FileLock acquired = lock) {
                    String tabs = listTabsViaCdp();
                    if (tabs != null) return "Chrome is already running.\n" + tabs;
                    if (cdpProblem != null && !managedEndpoint) return "Error: " + cdpProblem;
                    if ((ownedChrome != null && ownedChrome.isAlive()) || BrowserCacheCleaner.inUse(profile)) {
                        return "Error: DLC Chrome is running but CDP is unavailable. Do not launch another browser or delete profile locks.";
                    }
                    if (profile.equals(BrowserCacheCleaner.PROFILE)) BrowserCacheCleaner.prune();
                    String os = System.getProperty("os.name", "").toLowerCase();
                    String executable = os.contains("mac") ? findChromeMac()
                            : os.contains("win") ? findChromeWindows() : findChromeLinux();
                    if (executable == null) return "Error: Google Chrome is not installed.";
                    Files.createDirectories(profile);
                    Path tmp = runtime.resolve("tmp");
                    Files.createDirectories(tmp);
                    Path startupLog = runtime.resolve("chrome-startup.log");
                    List<String> cmd = List.of(executable,
                            "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=" + (managedEndpoint ? 0 : CDP_PORT),
                            "--user-data-dir=" + profile,
                            "--disk-cache-size=67108864", "--media-cache-size=33554432",
                            "--no-first-run", "--no-default-browser-check", "about:blank");
                    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                            .redirectOutput(startupLog.toFile());
                    setTemporaryEnvironment(pb, tmp);
                    ownedChrome = pb.start();
                    for (int i = 0; i < 30; i++) {
                        tabs = listTabsViaCdp();
                        if (tabs != null) return "Chrome started successfully.\n" + tabs;
                        if (!ownedChrome.isAlive()) break;
                        Thread.sleep(250);
                    }
                    // Only clean up the process we started. Never close the user's other Chrome.
                    ProcessIsolation.destroyTree(ownedChrome);
                    ownedChrome = null;
                    String detail = Files.readString(startupLog, StandardCharsets.UTF_8);
                    if (detail.length() > 2400) detail = detail.substring(detail.length() - 2400);
                    return "Error: Chrome did not expose a valid CDP endpoint. Startup log: "
                            + startupLog + "\n" + detail;
                }
            }
        } catch (InterruptedException e) {
            ProcessIsolation.destroyTree(ownedChrome);
            ownedChrome = null;
            Thread.currentThread().interrupt();
            return "Error: Chrome startup cancelled.";
        } catch (Exception e) {
            ProcessIsolation.destroyTree(ownedChrome);
            ownedChrome = null;
            return "Error starting Chrome: " + e.getMessage();
        }
    }

    @Tool(name = "browser_view", description = "Analyze the current page in the browser. "
            + "Returns success, title, URL, tab, viewport, screenshot, actionable element refs, "
            + "headings, iframe selectors and main text content. "
            + "Call browser_start first, then use this to understand the page before performing actions.")
    public synchronized String browserView(
            @ToolParam(required = false, description = "Tab index to view (default: 0 = first tab)") Integer tab,
            @ToolParam(required = false, description = "Optional path to save screenshot, e.g. /tmp/page.png") String screenshot) {

        if (listTabsViaCdp() == null) return "Error: " + (cdpProblem != null ? cdpProblem
                : "Chrome is not connected. Call browser_start once before operating.");
        if (!Files.exists(script)) {
            return "Error: browser_cdp.py not found. Restart DLC to extract built-in scripts.";
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(PYTHON_CMD);
        cmd.add(script.toString());
        cmd.add("view");
        cmd.add("--cdp-url");
        cmd.add(cdpUrl);
        if (tab != null) {
            cmd.add("--tab");
            cmd.add(String.valueOf(tab));
        }
        // Auto-screenshot: always capture so the model can "see" the page
        cmd.add("--screenshot");
        cmd.add((screenshot != null && !screenshot.isBlank()) ? screenshot
                : screenshotPath("view").toString());
        return runProcess(cmd);
    }

    @Tool(name = "browser_action", description = "Perform actions on the current browser page. "
            + "Call browser_view first to understand the page layout, then use this to interact. "
            + "Supported actions: goto (navigate to URL), click, click_xy (click at pixel coordinates x,y), "
            + "fill, select, check, uncheck, hover, "
            + "press, type, scroll, screenshot, get_text, get_attr, evaluate, wait, assert, back, forward, reload. "
            + "Target elements using ref from the latest observation, selector, role/name, label, or text. "
            + "Returns success, failed_step, final page observation and screenshot; stops at the first failed action. "
            + "A step may also report narrowed=true (selector matched several elements and was narrowed) "
            + "or no_op=true (URL and page text unchanged, so the click likely did not register - re-observe). "
            + "For fill/type/select the value may also be passed as \"text\". "
            + "Actions execute in sequence. The browser stays open after actions complete - "
            + "login state, cookies, and page state are PRESERVED for next call.")
    public synchronized String browserAction(
            @ToolParam(description = "JSON array of actions. Examples:\n"
                    + "Navigate: [{\"action\":\"goto\",\"url\":\"https://example.com\"}]\n"
                    + "Fill + Click: [{\"action\":\"fill\",\"selector\":\"#username\",\"value\":\"admin\"},"
                    + "{\"action\":\"fill\",\"selector\":\"#password\",\"value\":\"123\"},"
                    + "{\"action\":\"click\",\"selector\":\"button[type=submit]\"}]\n"
                    + "Click coordinates: [{\"action\":\"click_xy\",\"x\":120,\"y\":340}]\n"
                    + "Get text: [{\"action\":\"get_text\",\"selector\":\".result\"}]\n"
                    + "Scroll: [{\"action\":\"scroll\",\"direction\":\"down\",\"amount\":500}]\n"
                    + "Selectors: text=Submit, #id, .class, input[name=email], button:has-text(\"OK\")") String actions,
            @ToolParam(required = false, description = "Tab index (default: 0)") Integer tab,
            @ToolParam(required = false, description = "Optional path to save final screenshot") String screenshot) {

        if (listTabsViaCdp() == null) return "Error: " + (cdpProblem != null ? cdpProblem
                : "Chrome is not connected. Call browser_start once before operating.");
        if (!Files.exists(script)) {
            return "Error: browser_cdp.py not found. Restart DLC to extract built-in scripts.";
        }

        // 将 JSON 写入临时文件，避免 Windows 命令行双引号转义问题
        Path actionsFile;
        try {
            var parsed = mapper.readTree(actions);
            if (parsed == null || !parsed.isArray() || parsed.isEmpty() || parsed.size() > 20) {
                return "Error: actions must be a JSON array with 1..20 actions.";
            }
            Files.createDirectories(runtime.resolve("tmp"));
            actionsFile = Files.createTempFile(runtime.resolve("tmp"), "dlc-actions-", ".json");
            Files.writeString(actionsFile, actions, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Error: Failed to write actions file: " + e.getMessage();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(PYTHON_CMD);
        cmd.add(script.toString());
        cmd.add("action");
        cmd.add("--cdp-url");
        cmd.add(cdpUrl);
        cmd.add("--actions-file");
        cmd.add(actionsFile.toString());
        if (tab != null) {
            cmd.add("--tab");
            cmd.add(String.valueOf(tab));
        }
        // Auto-screenshot: always capture so the model can "see" the result
        cmd.add("--screenshot");
        cmd.add((screenshot != null && !screenshot.isBlank()) ? screenshot
                : screenshotPath("action").toString());
        try {
            return runProcess(cmd);
        } finally {
            try { Files.deleteIfExists(actionsFile); } catch (Exception ignored) { }
        }
    }

    // ==================== Helpers ====================

    /**
     * Query CDP endpoint to list open tabs. Returns null if Chrome is not running.
     */
    private String listTabsViaCdp() {
        cdpProblem = null;
        try {
            discoverManagedEndpoint();
            var version = readCdpJson("/json/version");
            URI websocket = URI.create(version.path("webSocketDebuggerUrl").asText(""));
            if (!"ws".equals(websocket.getScheme()) || websocket.getPath() == null
                    || !websocket.getPath().startsWith("/devtools/browser/")) {
                cdpProblem = "Endpoint is occupied by a service without a Chrome CDP endpoint.";
                return null;
            }
            return formatTabs(readCdpJson("/json/list").toString());
        } catch (ConnectException ignored) {
            return null;
        } catch (Exception e) {
            cdpProblem = "CDP endpoint is invalid or unavailable: " + e.getMessage();
            return null;
        }
    }

    private void discoverManagedEndpoint() throws Exception {
        if (!managedEndpoint) return;
        Path active = profile.resolve("DevToolsActivePort");
        if (!Files.isRegularFile(active) || Files.size(active) > 1024) return;
        List<String> lines = Files.readAllLines(active, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !lines.get(1).startsWith("/devtools/browser/")) return;
        int port = Integer.parseInt(lines.get(0));
        if (port > 0 && port <= 65535) cdpUrl = "http://127.0.0.1:" + port;
    }

    private com.fasterxml.jackson.databind.JsonNode readCdpJson(String endpoint) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(cdpUrl + endpoint).toURL().openConnection();
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(1500);
        try {
            if (conn.getResponseCode() != 200) throw new java.io.IOException(endpoint + " returned HTTP " + conn.getResponseCode());
            try (var input = conn.getInputStream()) { return mapper.readTree(input); }
        } finally { conn.disconnect(); }
    }

    String formatTabs(String jsonArray) throws Exception {
        var targets = mapper.readTree(jsonArray);
        if (targets == null || !targets.isArray()) throw new java.io.IOException("CDP tab list must be a JSON array");
        StringBuilder result = new StringBuilder("Open tabs:\n");
        int index = 0;
        for (var target : targets) {
            if (!"page".equals(target.path("type").asText())) continue;
            result.append("  [").append(index++).append("] ")
                    .append(target.path("title").asText()).append(" - ")
                    .append(target.path("url").asText()).append('\n');
        }
        if (index == 0) result.append("  (no tabs open)\n");
        return result.toString();
    }

    private Path screenshotPath(String operation) {
        String session = Integer.toHexString(String.valueOf(ExecutionContext.sessionId()).hashCode());
        return runtime.resolve("screenshots").resolve(session).resolve(operation + ".png");
    }

    private static void setTemporaryEnvironment(ProcessBuilder pb, Path tmp) {
        for (String name : List.of("TMPDIR", "TMP", "TEMP")) pb.environment().put(name, tmp.toString());
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
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Path tmp = runtime.resolve("tmp");
            Files.createDirectories(tmp);
            setTemporaryEnvironment(pb, tmp);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            process = pb.start();
            Process running = process;

            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] chunk = new char[4_096];
                    int count;
                    while ((count = reader.read(chunk)) != -1) {
                        synchronized (output) {
                            output.append(chunk, 0, count);
                            if (output.length() > 60_000) output.delete(20_000, output.length() - 20_000);
                        }
                    }
                } catch (Exception ignored) { }
            }, "dlc-browser-output");
            readerThread.setDaemon(true);
            readerThread.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ProcessIsolation.destroyTree(process);
                return "Error: Browser operation timed out after " + TIMEOUT_SECONDS + "s.";
            }
            readerThread.join(1_000);

            String result = normalizeProcessOutput(output.toString());

            if (process.exitValue() != 0) {
                if (result.contains("No module named 'playwright'")) {
                    return "Error: Playwright is not installed. "
                            + "Run: pip3 install playwright && playwright install chromium";
                }
                if (result.contains("connect") && result.contains("refused")) {
                    return "Error: Cannot connect to Chrome. Call browser_start first.";
                }
                // Structured Python failures retain the screenshot and actual page state.
                if (result.stripLeading().startsWith("{")) return result;
                return "Error (exit " + process.exitValue() + "): " + result;
            }

            // Let AgentLoop interpret structured status before it compacts the result.
            return result.stripLeading().startsWith("{") ? result : truncator.truncate(result);
        } catch (InterruptedException e) {
            ProcessIsolation.destroyTree(process);
            Thread.currentThread().interrupt();
            return "Error: Browser operation cancelled.";
        } catch (Exception e) {
            ProcessIsolation.destroyTree(process);
            return "Error: " + e.getMessage();
        }
    }

    String normalizeProcessOutput(String output) {
        String result = output.strip();
        // Playwright's Node driver writes deprecation warnings to stderr. The
        // Python protocol emits one JSON line, which must remain machine-readable.
        int jsonLine = result.lastIndexOf("\n{");
        String candidate = jsonLine >= 0 ? result.substring(jsonLine + 1) : result;
        try {
            if (mapper.readTree(candidate).isObject()) return candidate;
        } catch (Exception ignored) { }
        return result;
    }

    @PreDestroy
    public synchronized void closeOwnedBrowser() {
        ProcessIsolation.destroyTree(ownedChrome);
        ownedChrome = null;
        if (profile.equals(BrowserCacheCleaner.PROFILE)) BrowserCacheCleaner.prune();
    }
}
