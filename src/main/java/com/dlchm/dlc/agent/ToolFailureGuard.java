package com.dlchm.dlc.agent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Turn-scoped circuit breaker; changing a random profile path cannot reset it. */
final class ToolFailureGuard {
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Integer> observations = new HashMap<>();
    private int consecutive;
    private int browserStartupFailures;
    private boolean exhausted;

    boolean exhausted() { return exhausted; }

    String blockedReason(String name, String arguments) {
        if (exhausted) return "Error: Tool retry budget exhausted. Report observed progress and the blocking error.";
        if (browserStartupFailures >= 2 && ("browser_start".equals(name) || launchesBrowser(name, arguments))) {
            return "Error: Browser startup failed twice. Further launches are blocked this turn. Use browser_view to check an existing connection; do not change profiles, delete locks or open more windows.";
        }
        return null;
    }

    void record(String name, String arguments, String result) {
        if (!ToolResultStatus.failed(result)) {
            consecutive = 0;
            if (name.startsWith("browser_") && !"browser_start".equals(name)) browserStartupFailures = 0;
            if (name.startsWith("browser_")) {
                String key = name + "|" + arguments + "|" + result;
                exhausted |= observations.merge(key, 1, Integer::sum) >= 4;
            }
            return;
        }
        consecutive++;
        String category = category(result);
        boolean startup = "browser_start".equals(name) || launchesBrowser(name, arguments)
                && (category.equals("permission") || category.equals("profile")
                || category.equals("cdp") || category.equals("launch-arguments"));
        if (startup) browserStartupFailures++;
        String family = startup ? "browser-startup" : name;
        int count = failures.merge(family + "|" + category, 1, Integer::sum);
        exhausted |= consecutive >= 6 || count >= 4;
    }

    private boolean launchesBrowser(String name, String arguments) {
        if (!"bash_execute".equals(name)) return false;
        String a = arguments == null ? "" : arguments.toLowerCase(Locale.ROOT);
        return a.contains(".launch(") || a.contains(".launch_persistent_context(")
                || a.contains("--remote-debugging-port") || a.contains("open -a") || a.contains("open -na");
    }

    static String category(String result) {
        String s = result.toLowerCase(Locale.ROOT);
        if (s.contains("eperm") || s.contains("operation not permitted") || s.contains("permission denied")) return "permission";
        if (s.contains("singleton") || s.contains("profile is already in use")) return "profile";
        if (s.contains("cdp") || s.contains("connect_over_cdp") || s.contains("connection refused")) return "cdp";
        if (s.contains("user_data_dir") || s.contains("unsupported action") || s.contains("invalid element ref")) return "launch-arguments";
        if (s.contains("timeout") || s.contains("timed out")) return "timeout";
        // Normalize incidental timestamps, PIDs, random directories and targets.
        String normalized = s.replaceAll("[0-9]+", "#").replaceAll("[a-z0-9]{12,}", "*")
                .replaceAll("(/[^\\s\\\"']+)", "<path>").replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(160, normalized.length()));
    }
}
