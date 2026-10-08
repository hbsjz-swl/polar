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

    /**
     * A verification widget cannot be clicked through, retried past, or waited out.
     *
     * <p>This is the one stop condition the goal-completion rule already allows
     * ("缺少凭据：登录、验证码、2FA"), so the hint says so explicitly — otherwise the
     * model reads "keep going" from the surrounding prompts and spends the rest of the
     * turn re-observing a page it cannot operate.</p>
     */
    private static final String CAPTCHA_HINT =
            "[系统提示] 当前页面被验证码/滑块验证组件遮挡，这不是选择器问题，重试、换 ref、改坐标都无效，"
                    + "也不允许用 force 强穿（那只会点到验证组件本身）。这类阻塞按规则允许停下等用户："
                    + "保持浏览器窗口打开，明确告知用户是哪一步需要他本人完成验证，"
                    + "并列出已经拿到的数据和仍缺的数据——不要说成「数据不可用」，它只是被挡住了。"
                    + "用户确认已完成后，调用 browser_view 重新观察再继续。";

    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Integer> observations = new HashMap<>();
    private final Set<String> blockedFamilies = new HashSet<>();
    private int consecutive;
    private int browserStartupFailures;
    private int turnFailures;
    private int captchaFailures;
    private int escalationLevel;
    private int emittedEscalation;
    private boolean exhausted;

    boolean exhausted() { return exhausted; }

    /** Failed tool results this turn, counted regardless of tool name or error kind. */
    int turnFailures() { return turnFailures; }

    /**
     * True when a verification widget is confirmed to be blocking the page.
     *
     * <p>Consulted before the "don't stop to ask" redrive: that prompt tells the model
     * to keep going, and a captcha is one of the few conditions it is allowed to wait
     * on. It needs the tool budget closed first, or the model reads the surrounding
     * "finish the task" pressure and keeps clicking a page it cannot touch.</p>
     */
    boolean captchaBlocked() { return captchaFailures >= 2; }

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
        if (captchaFailures >= 2) {
            // Higher than the turn budget: a captcha is a legitimate reason to stop and
            // wait for the user, so the wrap-up advice ("deliver what you have, this
            // turn is over") would be wrong — the turn is blocked, not finished.
            if (emittedEscalation < 3) {
                emittedEscalation = 3;
                return CAPTCHA_HINT;
            }
            return null;
        }
        if (exhausted) escalationLevel = Math.max(escalationLevel, 2);
        if (escalationLevel <= emittedEscalation) return null;
        emittedEscalation = escalationLevel;
        return escalationLevel >= 2 ? WRAP_UP_HINT : REOBSERVE_HINT;
    }

    /**
     * True when a browser action failed because its element target never resolved.
     *
     * <p>Deliberately narrow. Playwright's call log opens with "waiting for
     * locator(…)" on <em>every</em> action, including ones that went on to resolve the
     * element and then refuse the click because something covered it. Matching that
     * prefix alone filed a slider-captcha interception as a bad selector, and the
     * model's corrective prompt then told it to stop using refs — sending it round the
     * observation loop against a page it could not click at all.</p>
     *
     * <p>So a resolved element or a named interceptor proves the locator did its job,
     * and the failure belongs to {@link #obstructed(String)} instead.</p>
     */
    static boolean locatorMiss(String result) {
        String s = result == null ? "" : result.toLowerCase(Locale.ROOT);
        if (obstructed(result)) return false;
        // "resolved to" means the element was found; only a log that never reaches it
        // describes a target that is genuinely missing.
        if (s.contains("resolved to") || s.contains("strict mode violation")) return false;
        return s.contains("waiting for locator") || s.contains("waiting for get_by")
                || s.contains("target is hidden") || s.contains("missing target")
                || s.contains("invalid element ref");
    }

    /**
     * True when the interaction was refused by something covering the target.
     *
     * <p>Either the tool labelled the failure ({@code blocked_kind} in the structured
     * result) or Playwright named an interceptor in its call log. Both mean the
     * element exists and is usable, so re-picking a ref cannot help.</p>
     */
    static boolean obstructed(String result) {
        String s = result == null ? "" : result.toLowerCase(Locale.ROOT);
        return s.contains("\"blocked_kind\"") || s.contains("blocked_kind=")
                || s.contains("intercepts pointer events") || s.contains("is covered by")
                || s.contains("obscured\":true");
    }

    /** True when the page is gated behind a verification widget only a human clears. */
    static boolean captchaBlocked(String result) {
        String s = result == null ? "" : result.toLowerCase(Locale.ROOT);
        return obstructed(result) && (s.contains("\"captcha\"") || s.contains("blocked_kind=captcha")
                || s.contains("slidervalid") || s.contains("captcha"));
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
        // A verification widget is the one blocker that retrying provably cannot clear,
        // and clicking through it is not an option either. Two attempts are enough to
        // establish it; the correct move is to hand the step back to the user, so the
        // turn stops burning its budget re-picking refs against an unclickable page.
        if (captchaFailures >= 2 && writesPage(name)) {
            return "Error: This page is behind a CAPTCHA/verification widget, and clicking through it is "
                    + "not possible. Do not try other refs, selectors or coordinates. Tell the user which "
                    + "step needs their verification, keep the browser window open, summarise what has already "
                    + "been collected, and stop this turn to wait for them.";
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
        if (captchaBlocked(result)) captchaFailures++;
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

    /**
     * Tools whose action happens on the page the captcha is covering.
     *
     * <p>browser_view stays usable on purpose: it is how the model confirms the
     * widget is still there after the user says they finished it.</p>
     */
    private static boolean writesPage(String name) {
        return "browser_action".equals(name) || "browser_start".equals(name);
    }

    static String category(String result) {
        String s = result.toLowerCase(Locale.ROOT);
        if (s.contains("eperm") || s.contains("operation not permitted") || s.contains("permission denied")) return "permission";
        if (s.contains("singleton") || s.contains("profile is already in use")) return "profile";
        if (s.contains("cdp") || s.contains("connect_over_cdp") || s.contains("connection refused")) return "cdp";
        if (s.contains("user_data_dir") || s.contains("unsupported action") || s.contains("invalid element ref")) return "launch-arguments";
        // Ahead of "timeout": a covered target always times out, so leaving it in the
        // timeout bucket made four blocked clicks indistinguishable from four slow
        // pages — and a slow page is worth retrying while a captcha is not.
        if (captchaBlocked(result)) return "captcha";
        if (obstructed(result)) return "obscured";
        if (s.contains("timeout") || s.contains("timed out")) return "timeout";
        // Normalize incidental timestamps, PIDs, random directories and targets.
        String normalized = s.replaceAll("[0-9]+", "#").replaceAll("[a-z0-9]{12,}", "*")
                .replaceAll("(/[^\\s\\\"']+)", "<path>").replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(160, normalized.length()));
    }
}
