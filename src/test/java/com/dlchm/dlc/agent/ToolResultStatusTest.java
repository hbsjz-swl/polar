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
}
