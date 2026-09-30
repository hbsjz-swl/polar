package com.dlchm.dlc.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserToolTest {
    @TempDir Path runtime;

    @Test void parsesEscapedTitlesAndExcludesWorkerTargets() throws Exception {
        BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), "http://127.0.0.1:1", runtime);
        String tabs = tool.formatTabs("[{\"type\":\"service_worker\",\"title\":\"worker\",\"url\":\"worker.js\"},"
                + "{\"type\":\"page\",\"title\":\"A \\\"quoted\\\" title\",\"url\":\"https://example.com?q=中文\"}]");
        assertTrue(tabs.contains("[0] A \"quoted\" title - https://example.com?q=中文"));
        assertFalse(tabs.contains("worker"));
        assertThrows(Exception.class, () -> tool.formatTabs("{}"));
    }

    @Test void restoresOnlyLiveHttpTabsAndCapsTheReplay() throws Exception {
        BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), "http://127.0.0.1:1", runtime);
        assertTrue(tool.rememberedTabs().isEmpty(), "no snapshot means no replay");

        java.util.List<String> written = new java.util.ArrayList<>();
        written.add("about:blank");
        written.add("chrome://settings");
        for (int i = 0; i < 12; i++) written.add("https://flights.ctrip.com/online/list/oneway-xmn-tsn?d=" + i);
        java.nio.file.Files.write(runtime.resolve("last-tabs.txt"), written, StandardCharsets.UTF_8);

        java.util.List<String> reopened = tool.rememberedTabs();
        assertEquals(8, reopened.size(), "a relaunch must not open the whole browsing history");
        assertTrue(reopened.stream().allMatch(url -> url.startsWith("https://")));
    }

    @Test void driverWarningsDoNotHideStructuredActionFailure() {
        BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), "http://127.0.0.1:1", runtime);
        String json = "{\"success\":false,\"error\":\"missing input\"}";
        assertEquals(json, tool.normalizeProcessOutput("(node:123) [DEP0169] DeprecationWarning: url.parse()\n" + json + "\n"));
    }

    @Test void refusesPortOccupiedByNonCdpServiceWithoutLaunchingChrome() throws Exception {
        HttpServer server = server(404, "missing");
        try {
            BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), url(server), runtime);
            assertTrue(tool.browserStart().startsWith("Error:"));
            assertFalse(java.nio.file.Files.exists(runtime.resolve("chrome-startup.log")));
        } finally { server.stop(0); }
    }

    @Test void reusesValidCdpConnectionOnRepeatedStarts() throws Exception {
        HttpServer server = server(200, "{\"webSocketDebuggerUrl\":\"ws://127.0.0.1/devtools/browser/test\"}");
        server.createContext("/json/list", exchange -> {
            byte[] response = "[{\"type\":\"page\",\"title\":\"Test\",\"url\":\"about:blank\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        try {
            BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), url(server), runtime);
            assertTrue(tool.browserStart().startsWith("Chrome is already running."));
            assertTrue(tool.browserStart().contains("[0] Test"));
            assertFalse(java.nio.file.Files.exists(runtime.resolve("chrome-startup.log")));
            tool.closeOwnedBrowser(); // An attached browser is not an owned process.
            assertTrue(tool.browserStart().startsWith("Chrome is already running."));
        } finally { server.stop(0); }
    }

    @Test void doesNotReplayTabsThatNeverRendered() throws Exception {
        HttpServer server = server(200, "{\"webSocketDebuggerUrl\":\"ws://127.0.0.1/devtools/browser/test\"}");
        server.createContext("/json/list", exchange -> {
            byte[] response = ("[{\"type\":\"page\",\"title\":\"\",\"url\":\"https://dead.example/search?k=1\"},"
                    + "{\"type\":\"page\",\"title\":\"石家庄到厦门\",\"url\":\"https://live.example/list\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        try {
            BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), url(server), runtime);
            assertTrue(tool.browserStart().startsWith("Chrome is already running."));

            java.util.List<String> remembered = tool.rememberedTabs();
            assertTrue(remembered.contains("https://live.example/list"), "a loaded page must survive a restart");
            assertFalse(remembered.contains("https://dead.example/search?k=1"),
                    "an error/blank tab must not become a zombie tab in the next session");
        } finally { server.stop(0); }
    }

    @Test void viewOnALiveEndpointNeverReportsADisconnectedBrowser() throws Exception {
        HttpServer server = server(200, "{\"webSocketDebuggerUrl\":\"ws://127.0.0.1/devtools/browser/test\"}");
        server.createContext("/json/list", exchange -> {
            byte[] response = "[{\"type\":\"page\",\"title\":\"Test\",\"url\":\"https://example.com\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        try {
            // The script is deliberately absent, so the call stops at the script check.
            // Reaching that far proves the connect step let a live session through
            // instead of short-circuiting on a spurious "not connected".
            BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), url(server), runtime,
                    runtime.resolve("absent-browser_cdp.py"));
            String result = tool.browserView(null, null);
            assertTrue(result.contains("not found"), result);
            assertFalse(result.contains("not connected"), result);
        } finally { server.stop(0); }
    }

    @Test void viewOnAnExternalDeadEndpointDoesNotLaunchChrome() throws Exception {
        // Non-managed endpoints are never started on demand: the user's own browser
        // is not DLC's to launch, so the actionable error must survive.
        BrowserTool tool = new BrowserTool(new ToolOutputTruncator(4000), "http://127.0.0.1:1", runtime,
                runtime.resolve("absent-browser_cdp.py"));
        String result = tool.browserView(null, null);
        assertTrue(result.startsWith("Error:"), result);
        assertFalse(java.nio.file.Files.exists(runtime.resolve("chrome-startup.log")));
    }

    private HttpServer server(int status, String data) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json/version", exchange -> {
            byte[] response = data.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        return server;
    }
    private String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
}
