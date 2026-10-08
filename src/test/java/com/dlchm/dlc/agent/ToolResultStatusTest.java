package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ToolResultStatusTest {
    @Test void recognizesNestedActionFailureWithSuccessfulProcessExit() {
        assertTrue(ToolResultStatus.failed("{\"actions\":[{\"error\":\"Timeout\"}],\"final\":{\"url\":\"https://example.com\"}}"));
        assertTrue(ToolResultStatus.failed("{\"success\":false,\"failed_step\":2}"));
        assertTrue(ToolResultStatus.failed("{\"success\":true,\"observation_error\":\"Target closed\"}"));
    }
    @Test void doesNotMistakeOptionalScreenshotWarningForFailedAction() {
        assertFalse(ToolResultStatus.failed("{\"success\":true,\"screenshot_error\":\"unavailable\"}"));
        assertFalse(ToolResultStatus.failed("Exit code: 0\nnormal output"));
        assertTrue(ToolResultStatus.failed("Exit code: 10\nfailed"));
        assertTrue(ToolResultStatus.failed("Exit code: 01\nfailed"));
    }

    @Test void compactionRetainsFailureStateAndImageMarkerAsValidJson() throws Exception {
        String result = "{\"success\":false,\"failed_step\":2,\"error\":\"missing input\","
                + "\"screenshot\":\"/tmp/test.png [SCREENSHOT:/tmp/test.png]\","
                + "\"final\":{\"url\":\"https://example.com\",\"interactive_elements\":[],"
                + "\"main_text\":\"" + "content ".repeat(2000) + "\"}}";
        String compact = ToolResultStatus.compact(result, 800);
        assertTrue(compact.length() <= 800);
        assertTrue(ToolResultStatus.failed(compact));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(compact);
        assertEquals(2, json.path("failed_step").asInt());
        assertTrue(json.path("screenshot").asText().contains("[SCREENSHOT:/tmp/test.png]"));
    }

    /**
     * A Playwright call log ends with its verdict, so trimming the error from the end
     * turned "covered by a captcha iframe" into a bare timeout — the model then had no
     * way to tell an obstruction from a slow page and burned its budget retrying.
     */
    @Test void compactionKeepsTheVerdictAtTheEndOfALongError() throws Exception {
        String verdict = "<iframe class=popwscps_d_iframe src=.../slidervalid></iframe> intercepts pointer events";
        String result = "{\"success\":false,\"failed_step\":1,\"error\":\"Locator.click: Timeout 7000ms exceeded.\\n"
                + "- filler line\\n".repeat(120) + verdict + "\","
                + "\"final\":{\"url\":\"https://example.com\",\"main_text\":\""
                + "content ".repeat(2000) + "\"}}";
        String compact = ToolResultStatus.compact(result, 900);
        assertTrue(compact.length() <= 900);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(compact);
        assertTrue(json.path("error").asText().contains("intercepts pointer events"),
                "the last call-log line is the diagnosis and must survive compaction");
    }

    /** The obstruction verdict is small and decides retry-vs-ask, so it is never dropped. */
    @Test void compactionRetainsTheObstructionVerdict() throws Exception {
        String result = "{\"success\":false,\"failed_step\":1,\"blocked_kind\":\"captcha\","
                + "\"blocked_by\":\"iframe.popwscps_d_iframe src=https://i.eastmoney.com/websitecaptcha/slidervalid\","
                + "\"advice\":\"the user must complete it\",\"error\":\"covered\","
                + "\"final\":{\"url\":\"https://example.com\",\"main_text\":\""
                + "content ".repeat(4000) + "\"}}";
        String compact = ToolResultStatus.compact(result, 800);
        assertTrue(compact.length() <= 800);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(compact);
        assertEquals("captcha", json.path("blocked_kind").asText());
        assertTrue(json.path("blocked_by").asText().contains("slidervalid"));
        assertTrue(json.path("advice").asText().contains("the user must complete it"));
        assertTrue(ToolFailureGuard.captchaBlocked(compact),
                "the compacted form must still classify as a captcha, or the loop resumes");
    }
}
