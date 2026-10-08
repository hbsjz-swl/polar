package com.dlchm.dlc.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Locale;
import java.util.regex.Pattern;

/** Interpret structured results before truncating them for the model. */
public final class ToolResultStatus {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EXIT = Pattern.compile("^exit code:\\s*(\\d+)");
    private ToolResultStatus() { }

    public static boolean failed(String result) {
        String s = result == null ? "" : result.strip().toLowerCase(Locale.ROOT);
        var exit = EXIT.matcher(s);
        if (exit.find()) return !"0".equals(exit.group(1));
        if (s.startsWith("error") || s.startsWith("failed") || s.startsWith("timed out")
                || s.startsWith("confirmation_required") || s.contains("permission denied")) return true;
        try { return hasFailure(JSON.readTree(result)); }
        catch (Exception ignored) { return false; }
    }

    private static boolean hasFailure(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            if (node.has("success") && node.path("success").isBoolean() && !node.path("success").asBoolean()) return true;
            if (node.hasNonNull("error") && !node.path("error").asText().isBlank()) return true;
            if (node.hasNonNull("observation_error")) return true;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) if (hasFailure(child)) return true;
        }
        return false;
    }

    /** Keep status and screenshot intact while reducing verbose browser observations. */
    static String compact(String result, int max) {
        if (result == null || result.length() <= max) return result;
        try {
            JsonNode parsed = JSON.readTree(result);
            if (!parsed.isObject() || !parsed.has("success")) return null;
            ObjectNode root = (ObjectNode) parsed;
            ObjectNode page = root.path("final").isObject() ? (ObjectNode) root.path("final") : root;
            if (page.path("main_text").isTextual()) {
                String text = page.path("main_text").asText();
                page.put("main_text", text.substring(0, Math.min(text.length(), max / 4)));
            }
            for (String field : new String[]{"interactive_elements", "headings", "frames"}) {
                if (page.path(field) instanceof ArrayNode array) {
                    while (array.size() > 0 && root.toString().length() > max) array.remove(array.size() - 1);
                }
            }
            if (root.toString().length() <= max) return root.toString();
            ObjectNode brief = JSON.createObjectNode();
            for (String field : new String[]{"success", "url", "title", "tab", "failed_step", "skipped_actions",
                    "error", "observation_error", "screenshot", "screenshot_error",
                    // The obstruction verdict, not the prose around it: this is what
                    // separates "retry differently" from "hand this to the user", and it
                    // is small enough to always keep.
                    "blocked_kind", "blocked_by", "advice"}) {
                if (root.has(field)) brief.set(field, root.get(field));
            }
            if (root.has("final")) {
                ObjectNode finalPage = brief.putObject("final");
                for (String field : new String[]{"url", "title", "tab", "main_text"}) {
                    if (page.has(field)) finalPage.set(field, page.get(field));
                }
            }
            // Limit large error messages/URLs without removing status or image markers.
            // Errors keep their tail: a Playwright call log carries its verdict last,
            // so cutting the end turns "covered by a captcha iframe" into a bare
            // timeout and the model loses the one actionable fact in the message.
            for (String field : new String[]{"observation_error", "url", "title"}) {
                if (brief.path(field).isTextual()) {
                    String text = brief.path(field).asText();
                    brief.put(field, text.substring(0, Math.min(text.length(), max / 5)));
                }
            }
            if (brief.path("error").isTextual()) {
                String text = brief.path("error").asText();
                int budget = max / 5;
                if (text.length() > budget) {
                    int tail = Math.min(text.length() - (budget * 2 / 3), budget / 3);
                    brief.put("error", text.substring(0, budget * 2 / 3) + "\n…[中间省略]…\n"
                            + text.substring(text.length() - tail));
                }
            }
            if (brief.toString().length() > max) brief.remove("final");
            return brief.toString();
        } catch (Exception ignored) { return null; }
    }
}
