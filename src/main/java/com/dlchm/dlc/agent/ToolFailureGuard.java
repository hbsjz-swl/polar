package com.dlchm.dlc.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turn-scoped circuit breaker; changing a random profile path cannot reset it. */
final class ToolFailureGuard {
    /**
     * Tools that only observe. They stay usable after the budget is spent so the
     * model can still look at the real state instead of guessing on a dead turn.
     */
    static final Set<String> READ_ONLY = Set.of(
            "browser_view", "read_file", "glob_search", "grep_search", "list_skills", "memory_read");

    /** Failures this turn that each ask for a full re-observation before more guessing. */
    private static final int REOBSERVE_AT = 4;
    /** Failures this turn that spend the budget: stop exploring, deliver what exists. */
    private static final int WRAP_UP_AT = 7;

    private static final String REOBSERVE_HINT =
            "[系统提示] 本轮工具失败已累计 " + REOBSERVE_AT + " 次。这通常说明判断依据本身是错的，"
                    + "而不是参数微调得不够。停止继续试新参数：先调用 browser_view（或 read_file / grep_search）"
                    + "重新读取真实现状，再依据返回的具体内容决定下一步。";

    private static final String WRAP_UP_HINT =
            "[系统提示] 本轮失败已达上限 " + WRAP_UP_AT + " 次，写入与操作类工具已停用，只保留只读观察。"
                    + "立即收尾：用已经取证的数据给出当前能给出的最好结论，并逐项列出哪些数据没拿到实时值、原因是什么。"
                    + "不要再开启新的查询路径，也不要用估算值填补缺口。";

    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Integer> observations = new HashMap<>();
    private final Set<String> blockedFamilies = new HashSet<>();
    private int consecutive;
    private int browserStartupFailures;
    private int turnFailures;
    private int escalationLevel;
    private int emittedEscalation;
    private boolean exhausted;

    boolean exhausted() { return exhausted; }

    /** Failed tool results this turn, counted regardless of tool name or error kind. */
    int turnFailures() { return turnFailures; }

    /**
     * One-shot escalation guidance for a turn that is going nowhere.
     *
     * <p>The per-family counters below cannot see this case. An agent whose strategy
     * is to vary the selector, the URL and the site produces a fresh key on almost
     * every attempt, so no single key ever reaches its threshold — eight failures
     * can spread over six keys and leave the breaker cold. This counter is
     * indifferent to both dimensions, which is what lets it catch flailing.</p>
     *
     * @return the guidance to inject once, or {@code null} when there is nothing new.
     */
    String escalateHint() {
        if (exhausted) escalationLevel = Math.max(escalationLevel, 2);
        if (escalationLevel <= emittedEscalation) return null;
        emittedEscalation = escalationLevel;
        return escalationLevel >= 2 ? WRAP_UP_HINT : REOBSERVE_HINT;
    }

    /** True when a browser action failed because its element target never resolved. */
    static boolean locatorMiss(String result) {
        String s = result == null ? "" : result.toLowerCase(Locale.ROOT);
        return s.contains("waiting for locator") || s.contains("waiting for get_by")
                || s.contains("target is hidden") || s.contains("missing target")
                || s.contains("invalid element ref") || s.contains("strict mode violation");
    }

    String blockedReason(String name, String arguments) {
        if (browserStartupFailures >= 2 && ("browser_start".equals(name) || launchesBrowser(name, arguments))) {
            return "Error: Browser startup failed twice. Further launches are blocked this turn. Use browser_view to check an existing connection; do not change profiles, delete locks or open more windows.";
        }
        if (blockedFamilies.contains(familyOf(name, arguments))) {
            return "Error: '" + name + "' has failed repeatedly with the same kind of error this turn. "
                    + "Do not retry it with another variation. Switch to a different approach, or use a read-only "
                    + "tool (browser_view / read_file / grep_search) to re-observe the real state first.";
        }
        if (exhausted && !READ_ONLY.contains(name)) {
            return "Error: Tool retry budget exhausted for this turn; only read-only observation remains. "
                    + "Report observed progress and the blocking error without claiming a failed action succeeded.";
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
        turnFailures++;
        if (turnFailures >= WRAP_UP_AT) {
            escalationLevel = 2;
            exhausted = true;
        } else if (turnFailures >= REOBSERVE_AT) {
            escalationLevel = 1;
        }
        String category = category(result);
        boolean startup = "browser_start".equals(name) || launchesBrowser(name, arguments)
                && (category.equals("permission") || category.equals("profile")
                || category.equals("cdp") || category.equals("launch-arguments"));
        if (startup) browserStartupFailures++;
        String family = startup ? "browser-startup" : name;
        int count = failures.merge(family + "|" + category, 1, Integer::sum);
        if (count >= 4) blockedFamilies.add(family);
        exhausted |= consecutive >= 6 || count >= 4;
    }

    private static String familyOf(String name, String arguments) {
        return "browser_start".equals(name) || launchesBrowser(name, arguments) ? "browser-startup" : name;
    }

    private static boolean launchesBrowser(String name, String arguments) {
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
