package com.dlchm.dlc.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in foreground Chrome check. Uses a dedicated disposable profile. */
@EnabledIfSystemProperty(named = "dlc.browser.live", matches = "true")
class BrowserToolLiveTest {
    @TempDir Path runtime;

    @Test void visibleChromeKeepsStateAcrossCallsAndVerifiesSearch() throws Exception {
        Path html = runtime.resolve("search.html");
        Files.writeString(html, """
                <!doctype html><html lang="zh"><meta charset="utf-8"><title>DLC 浏览器验证</title>
                <h1>浏览器搜索验证</h1><form onsubmit="event.preventDefault();
                document.getElementById('results').textContent='搜索结果：'+document.getElementById('q').value">
                <label for="q">搜索关键词</label><input id="q"><button>搜索</button></form>
                <main id="results"></main></html>
                """);
        BrowserTool tool = new BrowserTool(new ToolOutputTruncator(12000), "http://127.0.0.1:9222",
                runtime, Path.of("src/main/resources/scripts/browser_cdp.py").toAbsolutePath());
        ObjectMapper json = new ObjectMapper();
        try {
            String started = tool.browserStart();
            assertTrue(started.contains("Chrome started successfully"), started);
            assertTrue(tool.browserStart().startsWith("Chrome is already running."));
            String navigation = tool.browserAction("[{\"action\":\"goto\",\"url\":\"" + html.toUri() + "\"}]", 0, null);
            assertTrue(json.readTree(navigation).path("success").asBoolean(), navigation);
            String filled = tool.browserAction("[{\"action\":\"fill\",\"label\":\"搜索关键词\",\"value\":\"黄金暴跌的原因\"}]", 0, null);
            assertTrue(json.readTree(filled).path("success").asBoolean(), filled);
            String observation = tool.browserView(0, null);
            assertTrue(observation.contains("黄金暴跌的原因"), observation);
            String searched = tool.browserAction("[{\"action\":\"click\",\"role\":\"button\",\"name\":\"搜索\"},"
                    + "{\"action\":\"assert\",\"selector\":\"#results\",\"contains\":\"黄金暴跌的原因\"}]", 0, null);
            var result = json.readTree(searched);
            assertTrue(result.path("success").asBoolean(), searched);
            assertTrue(result.path("final").path("main_text").asText().contains("搜索结果：黄金暴跌的原因"));
            assertTrue(Files.isRegularFile(Path.of(result.path("screenshot").asText().split(" \\[")[0])));
            String failed = tool.browserAction("[{\"action\":\"fill\",\"selector\":\"#missing\",\"value\":\"bad\",\"timeout\":100},"
                    + "{\"action\":\"click\",\"role\":\"button\",\"name\":\"搜索\"}]", 0, null);
            assertFalse(json.readTree(failed).path("success").asBoolean(), failed);
            assertEquals(1, json.readTree(failed).path("skipped_actions").asInt());
            assertTrue(tool.browserView(0, null).contains("搜索结果：黄金暴跌的原因"));
        } finally { tool.closeOwnedBrowser(); }
    }
}
